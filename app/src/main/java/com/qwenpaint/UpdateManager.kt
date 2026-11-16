package com.qwenpaint

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * App 内在线升级：版本清单检查 → 断点续传下载 → 校验 → 调起安装。
 *
 * 覆盖安装（签名一致）不会清空 filesDir，模型文件保留。
 *
 * 版本清单 version.json 托管在 GitHub 仓库 release 分支/Release 附件：
 * {
 *   "versionCode": 3, "versionName": "1.0.2",
 *   "url": "https://github.com/.../releases/download/v1.0.2/app.apk",
 *   "mirrors": ["https://gh-proxy.com/https://github.com/..."],
 *   "sha256": "...", "size": 34603008,
 *   "notes": "更新说明", "force": false, "minSupport": 2
 * }
 */
object UpdateManager {

    /** 版本清单地址，按顺序尝试，全部走 HTTPS，超时短快速失败 */
    private val MANIFEST_URLS = listOf(
        "https://gh-proxy.com/https://raw.githubusercontent.com/dayouAI777/phone-image-huawei/main/release/version.json",
        "https://ghfast.top/https://raw.githubusercontent.com/dayouAI777/phone-image-huawei/main/release/version.json",
        "https://raw.githubusercontent.com/dayouAI777/phone-image-huawei/main/release/version.json",
    )

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val urls: List<String>,   // 主地址 + 镜像，按序尝试
        val sha256: String,
        val size: Long,
        val notes: String,
        val force: Boolean,
    )

    sealed class CheckResult {
        data class Update(val release: Release) : CheckResult()
        object UpToDate : CheckResult()
        data class Failed(val reason: String) : CheckResult()
    }

    fun currentVersionCode(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    } catch (_: PackageManager.NameNotFoundException) { 0 }

    /** 检查更新。网络操作，须在 IO 线程调用。 */
    fun check(context: Context): CheckResult {
        var lastError = "无法连接更新服务器"
        for (url in MANIFEST_URLS) {
            try {
                val body = httpGet(url, connectMs = 8_000, readMs = 10_000) ?: continue
                val o = JSONObject(body)
                val release = Release(
                    versionCode = o.getInt("versionCode"),
                    versionName = o.optString("versionName", "?"),
                    urls = buildList {
                        o.optString("url").takeIf { it.isNotBlank() }?.let { add(it) }
                        val arr = o.optJSONArray("mirrors")
                        if (arr != null) for (i in 0 until arr.length()) add(arr.getString(i))
                    },
                    sha256 = o.optString("sha256", "").lowercase(),
                    size = o.optLong("size", -1),
                    notes = o.optString("notes", ""),
                    force = o.optBoolean("force", false),
                )
                return if (release.versionCode > currentVersionCode(context) && release.urls.isNotEmpty()) {
                    CheckResult.Update(release)
                } else {
                    CheckResult.UpToDate
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return CheckResult.Failed(lastError)
    }

    sealed class DownloadResult {
        data class Ok(val apk: File) : DownloadResult()
        data class Failed(val reason: String) : DownloadResult()
    }

    /**
     * 下载 APK：断点续传 + 镜像轮替 + 每镜像最多 3 次重试。
     * 下载完成后按 size / sha256 校验，不通过即删除重下（最多 2 轮）。
     * onProgress: (已下载字节, 总字节[-1 未知])
     */
    fun download(
        context: Context,
        release: Release,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): DownloadResult {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val apk = File(dir, "update-${release.versionCode}.apk")

        // 已有完整校验通过的缓存直接用
        if (apk.exists() && verify(apk, release)) {
            onProgress(apk.length(), release.size)
            return DownloadResult.Ok(apk)
        }

        var lastError = "下载失败"
        repeat(2) { // 校验失败整体重下一轮
            for (mirror in release.urls) {
                if (isCancelled()) return DownloadResult.Failed("已取消")
                repeat(3) { attempt ->
                    try {
                        resumeDownload(mirror, apk, release.size, onProgress, isCancelled)
                        if (verify(apk, release)) return DownloadResult.Ok(apk)
                        lastError = "文件校验失败，重新下载"
                        apk.delete()
                        return@repeat // 换下一个镜像
                    } catch (e: CancelledException) {
                        return DownloadResult.Failed("已取消")
                    } catch (e: Exception) {
                        lastError = e.message ?: e.javaClass.simpleName
                        if (attempt < 2) Thread.sleep(1500L * (attempt + 1)) // 退避
                    }
                }
            }
        }
        apk.delete()
        return DownloadResult.Failed(lastError)
    }

    private class CancelledException : Exception()

    /** 断点续传：已存在部分用 Range 追加；服务器不支持续传则从头下 */
    private fun resumeDownload(
        url: String,
        dest: File,
        expectedSize: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        var downloaded = if (dest.exists()) dest.length() else 0L
        // 已超预期大小，说明文件损坏，从头来
        if (expectedSize > 0 && downloaded >= expectedSize) {
            dest.delete()
            downloaded = 0
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "QwenPaint-Updater")
            if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
        }
        try {
            val code = conn.responseCode
            val append: Boolean
            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> append = true          // 支持续传
                HttpURLConnection.HTTP_OK -> {                           // 不支持，覆盖重下
                    append = false
                    downloaded = 0
                }
                416 -> { // Range 越界：文件已完整或损坏，交给外层校验判断
                    append = true
                    conn.disconnect()
                    return
                }
                else -> throw Exception("HTTP $code")
            }

            val total = conn.contentLengthLong.let { if (it >= 0) it + if (append) downloaded else 0 else expectedSize }

            conn.inputStream.use { input ->
                (if (append) java.io.FileOutputStream(dest, true) else java.io.FileOutputStream(dest)).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 200) {
                            onProgress(downloaded, total)
                            lastReport = now
                        }
                    }
                }
            }
            onProgress(downloaded, total)
        } finally {
            conn.disconnect()
        }
    }

    private fun verify(apk: File, release: Release): Boolean {
        if (!apk.exists() || apk.length() == 0L) return false
        if (release.size > 0 && apk.length() != release.size) return false
        if (release.sha256.isNotBlank()) return sha256(apk) == release.sha256
        return true
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 调起系统安装器。需要 FileProvider + REQUEST_INSTALL_PACKAGES。 */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    private fun httpGet(url: String, connectMs: Int, readMs: Int): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "QwenPaint-Updater")
        }
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
