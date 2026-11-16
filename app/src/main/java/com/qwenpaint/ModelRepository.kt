package com.qwenpaint

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 模型文件在推理管线里的角色 */
enum class ModelRole {
    SINGLE,      // 单文件全量（SD1.5 / SD-Turbo）
    DIFFUSION,   // 分离式 DiT 主干（Qwen-Image / FLUX）
    VAE,
    LLM,         // 语言侧文本编码器（Qwen3-VL 等）
    LLM_VISION,  // 视觉投影层 mmproj
    CLIP_L,
    CLIP_G,
    T5,
}

/** 内存策略：16G 机器上这是能不能跑大模型的唯一杠杆 */
enum class MemPolicy(val label: String, val hint: String) {
    SAFE("省内存", "权重走 mmap + 文本编码器卸载到磁盘，最省但最慢"),
    BALANCED("均衡", "权重走 mmap，速度与内存兼顾（推荐）"),
    FAST("最快", "权重全载入内存，最快，大模型容易 OOM"),
}

/** 一个可直接加载的模型组合 */
data class ModelSet(
    val single: File? = null,
    val diffusion: File? = null,
    val vae: File? = null,
    val llm: File? = null,
    val llmVision: File? = null,
    val clipL: File? = null,
    val clipG: File? = null,
    val t5: File? = null,
) {
    val files: List<File>
        get() = listOfNotNull(single, diffusion, vae, llm, llmVision, clipL, clipG, t5)

    val totalBytes: Long get() = files.sumOf { it.length() }

    val ready: Boolean get() = single != null || diffusion != null

    val isDiffusion: Boolean get() = diffusion != null

    val displayName: String
        get() = single?.name ?: diffusion?.name ?: "未配置"

    val subtitle: String
        get() {
            val parts = mutableListOf<String>()
            parts += ModelRepository.readableSize(totalBytes)
            if (diffusion != null) parts += "DiT"
            if (llm != null) parts += "文本编码器"
            if (vae != null) parts += "VAE"
            return parts.joinToString(" · ")
        }

    fun toConfig(policy: MemPolicy, threads: Int, backend: String = ""): ModelConfig {
        val base = ModelConfig(
            modelPath = single?.absolutePath ?: "",
            diffusionPath = diffusion?.absolutePath ?: "",
            vaePath = vae?.absolutePath ?: "",
            llmPath = llm?.absolutePath ?: "",
            llmVisionPath = llmVision?.absolutePath ?: "",
            clipLPath = clipL?.absolutePath ?: "",
            clipGPath = clipG?.absolutePath ?: "",
            t5Path = t5?.absolutePath ?: "",
            backend = backend,
        )
        return when (policy) {
            MemPolicy.SAFE -> base.copy(
                threads = threads.coerceAtMost(4),
                enableMmap = true,
                flashAttn = false,
                paramsBackend = "te=disk",
                maxVram = "4G",
            )
            MemPolicy.BALANCED -> base.copy(
                threads = threads,
                enableMmap = true,
                flashAttn = false,
                paramsBackend = "",
                maxVram = "",
            )
            MemPolicy.FAST -> base.copy(
                threads = threads,
                enableMmap = false,
                flashAttn = true,
                paramsBackend = "",
                maxVram = "",
            )
        }
    }
}

/** 本地模型文件管理：导入、角色识别、组装模型组 */
object ModelRepository {

    /** 端侧真正跑得动的默认模型：SD-Turbo，4 步出图，q8_0 约 1.9GB */
    const val RECOMMENDED_NAME = "sd_turbo-q8_0.gguf"
    private const val RECOMMENDED_URL =
        "https://hf-mirror.com/Green-Sky/SD-Turbo-GGUF/resolve/main/sd_turbo-f16-q8_0.gguf"
    private const val RECOMMENDED_FALLBACK_URL =
        "https://huggingface.co/Green-Sky/SD-Turbo-GGUF/resolve/main/sd_turbo-f16-q8_0.gguf"

    private val exts = setOf("gguf", "safetensors", "ckpt", "bin", "pt", "pth")

    fun modelDir(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listFiles(context: Context): List<File> =
        modelDir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in exts && it.length() > 512 * 1024 }
            ?.sortedByDescending { it.length() }
            ?: emptyList()

    /** 按文件名判断角色。顺序敏感：vae / mmproj 必须先于 llm 判断 */
    fun roleOf(name: String): ModelRole {
        val n = name.lowercase()
        return when {
            n.contains("vae") || n.contains("ae.safetensors") -> ModelRole.VAE
            n.contains("mmproj") || n.contains("vision_proj") || n.contains("llm_vision") -> ModelRole.LLM_VISION
            n.contains("t5xxl") || n.contains("umt5") || n.contains("t5-") -> ModelRole.T5
            n.contains("clip_g") || n.contains("clip-g") -> ModelRole.CLIP_G
            n.contains("clip_l") || n.contains("clip-l") -> ModelRole.CLIP_L
            n.contains("qwen") && n.contains("vl") -> ModelRole.LLM
            n.contains("llm") || n.contains("text_encoder") -> ModelRole.LLM
            n.contains("qwen") || n.contains("dit") || n.contains("flux") || n.contains("z-image") -> ModelRole.DIFFUSION
            else -> ModelRole.SINGLE
        }
    }

    /**
     * 把目录里的文件组装成可加载的模型组：
     * 每个 DiT 主干配一套共享组件；没有 DiT 时，每个单文件各自成组。
     */
    fun buildModelSets(files: List<File>): List<ModelSet> {
        val byRole = files.groupBy { roleOf(it.name) }
        val vae = byRole[ModelRole.VAE].orEmpty().firstOrNull()
        val llm = byRole[ModelRole.LLM].orEmpty().firstOrNull()
        val vision = byRole[ModelRole.LLM_VISION].orEmpty().firstOrNull()
        val clipL = byRole[ModelRole.CLIP_L].orEmpty().firstOrNull()
        val clipG = byRole[ModelRole.CLIP_G].orEmpty().firstOrNull()
        val t5 = byRole[ModelRole.T5].orEmpty().firstOrNull()

        val sets = mutableListOf<ModelSet>()

        byRole[ModelRole.DIFFUSION].orEmpty().forEach { dit ->
            sets += ModelSet(
                diffusion = dit,
                vae = vae,
                llm = llm,
                llmVision = vision,
                clipL = clipL,
                clipG = clipG,
                t5 = t5,
            )
        }
        byRole[ModelRole.SINGLE].orEmpty().forEach { f ->
            sets += ModelSet(single = f)
        }
        // 分离式模型排前面（能力更强），同类型按体积降序
        return sets.sortedWith(compareByDescending<ModelSet> { it.isDiffusion }.thenByDescending { it.totalBytes })
    }

    fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }

    /** 把用户选中的模型复制进应用私有目录，返回本地文件 */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(context, uri) ?: "model.gguf"
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(modelDir(context), if (safeName.contains('.')) safeName else "$safeName.gguf")
            val tmp = File(target.parentFile, "${target.name}.part")

            val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L

            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastTick = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastTick >= 8L * 1024 * 1024) {
                            lastTick = copied
                            onProgress(copied, total)
                        }
                    }
                    out.flush()
                }
            } ?: return@withContext Result.failure(IllegalStateException("无法读取所选文件"))

            if (tmp.length() < 512 * 1024) {
                tmp.delete()
                return@withContext Result.failure(IllegalStateException("文件过小，可能不是完整的模型"))
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext Result.failure(IllegalStateException("模型保存失败"))
            }
            onProgress(target.length(), target.length())
            Result.success(target)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun delete(file: File): Boolean = file.delete()

    /**
     * App 内直接下载推荐模型，支持断点续传。
     * 先走 hf-mirror（国内快），失败再回源 huggingface。
     */
    suspend fun downloadRecommended(
        context: Context,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = File(modelDir(context), RECOMMENDED_NAME)
        if (target.exists() && target.length() > 100L * 1024 * 1024) {
            return@withContext Result.success(target)
        }
        var lastError = "下载失败"
        for (url in listOf(RECOMMENDED_URL, RECOMMENDED_FALLBACK_URL)) {
            val r = tryDownload(url, target, onProgress)
            if (r.isSuccess) return@withContext r
            lastError = r.exceptionOrNull()?.message ?: lastError
        }
        Result.failure(IllegalStateException(lastError))
    }

    private fun tryDownload(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): Result<File> {
        val tmp = File(target.parentFile, "${target.name}.part")
        var existing = if (tmp.exists()) tmp.length() else 0L
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            conn.connect()
            val code = conn.responseCode
            if (code != 200 && code != 206) {
                throw IllegalStateException("服务器返回 $code")
            }
            // 服务器忽略了 Range，从头下
            if (code == 200 && existing > 0) {
                tmp.delete()
                existing = 0L
            }
            val total = if (conn.contentLengthLong > 0) existing + conn.contentLengthLong else 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(tmp, existing > 0).use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = existing
                    var lastTick = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastTick >= 8L * 1024 * 1024) {
                            lastTick = copied
                            onProgress(copied, total)
                        }
                    }
                    out.flush()
                }
            }
            if (total > 0 && tmp.length() < total * 95 / 100) {
                throw IllegalStateException("下载不完整（${tmp.length()}/$total）")
            }
            if (tmp.length() < 100L * 1024 * 1024) {
                throw IllegalStateException("文件过小，不是完整模型")
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IllegalStateException("保存失败")
            onProgress(target.length(), target.length())
            Result.success(target)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            conn?.disconnect()
        }
    }

    fun readableSize(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        return if (gb >= 1.0) String.format("%.2f GB", gb) else String.format("%.0f MB", bytes / 1024.0 / 1024.0)
    }

    /** 文件名含 turbo / lcm / lightning 视为少步数蒸馏模型 */
    fun isTurbo(set: ModelSet?): Boolean {
        val n = set?.displayName?.lowercase() ?: return true
        return n.contains("turbo") || n.contains("lcm") || n.contains("lightning") || n.contains("z-image")
    }
}
