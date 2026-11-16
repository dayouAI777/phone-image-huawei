package com.qwenpaint

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val modelSets: List<ModelSet> = emptyList(),
    val currentSet: ModelSet? = null,
    val policy: MemPolicy = MemPolicy.BALANCED,
    // ---- 计算设备（GPU / CPU）----
    val gpuAvailable: Boolean = false,
    // GPU 默认关闭：Vulkan 后端在 Maleoon 上实测没有加速，反而因为算子回退更慢，
    // 先按 CPU 出图，等自检数据出来再决定是否默认打开。
    val useGpu: Boolean = false,
    val deviceNames: List<Pair<String, String>> = emptyList(),
    val engineReady: Boolean = false,
    val loadingModel: Boolean = false,
    val generating: Boolean = false,
    val progressCompleted: Int = 0,
    val progressTotal: Int = 0,
    val progressStage: String = "",
    val stepSeconds: Float = 0f,
    val lastTiming: String = "",
    val lastStepSeconds: Float = 0f,
    val lastPixels: Int = 0,
    val userThreads: Int = 4,
    // ---- 性能自检 ----
    val selfTesting: Boolean = false,
    val selfTestReport: String? = null,
    val image: Bitmap? = null,
    val lastSeed: Long = -1L,
    val isTurbo: Boolean = true,
    val importProgress: Float? = null,
    val downloadProgress: Float? = null,
    // ---- 在线升级 ----
    val checkingUpdate: Boolean = false,
    val updateInfo: UpdateManager.Release? = null,
    val updateProgress: Float? = null,
    val updateReadyApk: File? = null,
    val updateCancel: Boolean = false,
    // ---- 硬件探测 ----
    val hwProbe: String? = null,
    val error: String? = null,
    val message: String? = null,
)

class GenViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // 麒麟 9020 是 4 大核 + 4 小核，ggml 每个算子都要等全部线程到齐，
    // 线程数超过性能核数反而被小核拖慢。默认 4，可手动加到 6/8 对比。
    private var threads: Int = 4

    init {
        SdEngine.setProgressListener { completed, total, seconds, stage ->
            // 自检期间也在走同一个回调，别让它搅乱正式生成的进度显示
            if (_state.value.selfTesting) return@setProgressListener
            _state.update {
                it.copy(
                    progressCompleted = completed,
                    progressTotal = total,
                    progressStage = stage,
                    stepSeconds = seconds,
                )
            }
        }
        refreshModels()
        refreshDevices()
    }

    /** 读取 so 里实际编译进去的 ggml 后端（CPU 恒定存在，Vulkan 视构建而定） */
    fun refreshDevices() {
        viewModelScope.launch {
            val devices = withContext(Dispatchers.IO) { SdEngine.listDevices() }
            val hasGpu = devices.any { it.first.contains("vulkan", ignoreCase = true) }
            _state.update {
                // 注意不要在这里把 useGpu 打开：GPU 路径在 Maleoon 上实测更慢，
                // 只有「性能自检」测出 GPU 更快、或用户手动勾选时才用。
                it.copy(gpuAvailable = hasGpu, useGpu = it.useGpu && hasGpu, deviceNames = devices)
            }
        }
    }

    fun setUseGpu(useGpu: Boolean) {
        _state.update { if (it.gpuAvailable) it.copy(useGpu = useGpu, engineReady = false) else it }
    }

    fun refreshModels() {
        val sets = ModelRepository.buildModelSets(ModelRepository.listFiles(getApplication()))
        _state.update { st ->
            val current = st.currentSet?.takeIf { it.ready && it.files.all { f -> f.exists() } }
                ?: sets.firstOrNull()
            st.copy(modelSets = sets, currentSet = current, isTurbo = ModelRepository.isTurbo(current))
        }
    }

    fun selectSet(set: ModelSet) {
        _state.update {
            it.copy(currentSet = set, isTurbo = ModelRepository.isTurbo(set), engineReady = false)
        }
    }

    fun setPolicy(policy: MemPolicy) {
        _state.update { it.copy(policy = policy, engineReady = false) }
    }

    /** 线程数在加载模型时喂给 ggml，改了要重新加载才生效 */
    fun setThreads(n: Int) {
        threads = n.coerceIn(2, 8)
        _state.update {
            it.copy(userThreads = threads, message = "线程数设为 ${threads}，重新加载模型后生效")
        }
    }

    /**
     * 一键自检：把「CPU/GPU × 线程数」几组候选各跑 2 步 256×256，直接量出
     * 每步耗时 / 出图耗时 / 加载耗时，最后自动把最快的一组设成当前配置。
     * 384×384 以下的单步耗时基本和面积成正比，所以小图测出来的比例可以直接外推。
     */
    fun selfTest() {
        val st = _state.value
        val set = st.currentSet
        if (set == null || !set.ready) {
            _state.update { it.copy(error = "请先导入模型") }
            return
        }
        if (st.selfTesting || st.generating || st.loadingModel) return

        // 候选组合：CPU 是基本盘，GPU 只在 so 里真的编进去时才测
        val candidates = mutableListOf(
            Triple("cpu", 4, "CPU · 4 线程"),
            Triple("cpu", 8, "CPU · 8 线程"),
        )
        if (st.gpuAvailable) candidates += Triple("vulkan", 4, "GPU · Vulkan · 4 线程")

        viewModelScope.launch {
            _state.update {
                it.copy(
                    selfTesting = true, selfTestReport = null, error = null, engineReady = false,
                    message = "性能自检中（${candidates.size} 组配置），请勿锁屏…",
                )
            }
            val lines = mutableListOf<String>()
            var bestBackend = "cpu"
            var bestThreads = 4
            var bestStep = Float.MAX_VALUE

            for ((backend, th, label) in candidates) {
                val cfg = set.toConfig(st.policy, th, backend)
                val t0 = System.currentTimeMillis()
                val loaded = withContext(Dispatchers.Default) { SdEngine.loadModel(cfg) }
                val loadSec = (System.currentTimeMillis() - t0) / 1000f
                if (loaded.isFailure) {
                    val msg = loaded.exceptionOrNull()?.message?.replace('\n', ' ')?.take(70) ?: "未知原因"
                    lines += "$label\n    加载失败：$msg"
                    continue
                }
                _state.update { it.copy(message = "自检中：$label 出图中…") }

                val g0 = System.currentTimeMillis()
                val bmp = withContext(Dispatchers.Default) {
                    try {
                        SdEngine.generate("a cat", "", 256, 256, 2, 1.0f, 42L, "euler", false)
                    } catch (t: Throwable) {
                        null
                    }
                }
                val genSec = (System.currentTimeMillis() - g0) / 1000f
                if (bmp == null) {
                    lines += "$label\n    出图失败"
                    continue
                }
                val sep = SdEngine.lastStepSec()
                val tail = SdEngine.lastTailSec()
                // 折算到 512×512：面积比 4 倍、4 步，用来横向比较
                val est512 = if (sep > 0f) sep * 4f * (512f * 512f / (256f * 256f)) else -1f
                lines += buildString {
                    append(label)
                    append("\n    ").append(fmt(sep)).append("s/步")
                    append(" · 出图 ").append(fmt(tail)).append("s")
                    append(" · 加载 ").append(fmt(loadSec)).append("s")
                    if (est512 > 0f) append("\n    折算 512×512×4 步 ≈ ").append(fmt(est512 + tail)).append("s")
                }
                if (sep > 0f && sep < bestStep) {
                    bestStep = sep
                    bestBackend = backend
                    bestThreads = th
                }
            }

            // 用最快的一组重新加载，省得用户还得再等一次
            var ready = false
            if (bestStep < Float.MAX_VALUE) {
                _state.update { it.copy(message = "自检完成，正在加载最快配置…") }
                val bestCfg = set.toConfig(st.policy, bestThreads, bestBackend)
                ready = withContext(Dispatchers.Default) { SdEngine.loadModel(bestCfg) }.isSuccess
                threads = bestThreads
            }

            val header = if (bestStep < Float.MAX_VALUE) {
                val name = if (bestBackend == "vulkan") "GPU (Vulkan)" else "CPU"
                "最快：$name · $bestThreads 线程（${fmt(bestStep)}s/步 @256×256）"
            } else {
                "全部配置都失败，请把下面的信息发我"
            }
            _state.update {
                it.copy(
                    selfTesting = false,
                    engineReady = ready,
                    useGpu = bestBackend == "vulkan",
                    userThreads = bestThreads,
                    selfTestReport = header + "\n\n" + lines.joinToString("\n\n") +
                        "\n\n注：256×256 仅用于横向比较，实际 512×512 约为其 4 倍面积。",
                    message = if (ready) "自检完成，已切到最快配置" else null,
                )
            }
        }
    }

    private fun fmt(v: Float): String =
        if (v < 0f) "—" else String.format(java.util.Locale.US, "%.1f", v)

    fun dismissSelfTest() {
        _state.update { it.copy(selfTestReport = null) }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(importProgress = 0f, error = null) }
            val result = ModelRepository.importFromUri(getApplication(), uri) { copied, total ->
                if (total > 0) {
                    _state.update { it.copy(importProgress = (copied.toFloat() / total).coerceIn(0f, 1f)) }
                }
            }
            result.onSuccess {
                _state.update { it.copy(importProgress = null) }
                refreshModels()
            }.onFailure { e ->
                _state.update { it.copy(importProgress = null, error = e.message ?: "导入失败") }
            }
        }
    }

    fun downloadRecommended() {
        if (_state.value.downloadProgress != null) return
        viewModelScope.launch {
            _state.update {
                it.copy(downloadProgress = 0f, error = null, message = "开始下载 SD-Turbo（约 1.9 GB），建议用 WiFi…")
            }
            val result = ModelRepository.downloadRecommended(getApplication()) { copied, total ->
                if (total > 0) {
                    _state.update {
                        it.copy(
                            downloadProgress = (copied.toFloat() / total).coerceIn(0f, 1f),
                            message = "已下载 ${ModelRepository.readableSize(copied)} / ${ModelRepository.readableSize(total)}",
                        )
                    }
                }
            }
            result.onSuccess {
                _state.update { s -> s.copy(downloadProgress = null, message = "模型下载完成") }
                refreshModels()
            }.onFailure { e ->
                _state.update { s ->
                    s.copy(downloadProgress = null, error = "下载失败：${e.message}")
                }
            }
        }
    }

    fun loadModel() {
        val set = _state.value.currentSet
        if (set == null || !set.ready) {
            _state.update { it.copy(error = "请先导入模型文件") }
            return
        }
        if (_state.value.loadingModel || _state.value.generating) return

        val wantGpu = _state.value.useGpu && _state.value.gpuAvailable
        val cfg = set.toConfig(_state.value.policy, threads, if (wantGpu) "vulkan" else "cpu")
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loadingModel = true, error = null,
                    message = if (wantGpu) "正在加载模型到 GPU（Vulkan）…" else "正在加载模型（CPU），大模型可能需 1-3 分钟…",
                )
            }
            var result = withContext(Dispatchers.Default) { SdEngine.loadModel(cfg) }
            // GPU 路径失败时自动退回 CPU，避免一次显存不足就把用户卡死
            if (result.isFailure && wantGpu) {
                _state.update { it.copy(message = "GPU 加载失败，正在回退 CPU 重试…") }
                val fallback = withContext(Dispatchers.Default) {
                    SdEngine.loadModel(cfg.copy(backend = "cpu"))
                }
                if (fallback.isSuccess) {
                    _state.update { it.copy(useGpu = false) }
                    result = fallback
                }
            }
            result.onSuccess {
                _state.update {
                    it.copy(
                        loadingModel = false,
                        engineReady = true,
                        message = "模型已加载 · ${if (wantGpu) "GPU (Vulkan)" else "CPU"} · ${cfg.threads} 线程 · ${_state.value.policy.label}",
                    )
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        loadingModel = false,
                        engineReady = false,
                        error = "加载失败：${e.message}\n若为内存不足，请切到「省内存」档、或关闭 GPU 加速再试。",
                    )
                }
            }
        }
    }

    fun generate(prompt: String, negative: String, width: Int, height: Int, steps: Int, cfg: Float, seed: Long) {
        val st = _state.value
        if (!st.engineReady) {
            _state.update { it.copy(error = "模型尚未加载") }
            return
        }
        if (st.generating) return
        if (prompt.isBlank()) {
            _state.update { it.copy(error = "请输入提示词") }
            return
        }

        val actualSeed = if (seed < 0) (System.currentTimeMillis() % 1_000_000_000L) else seed

        viewModelScope.launch {
            _state.update {
                it.copy(
                    generating = true, error = null, message = null,
                    progressCompleted = 0, progressTotal = steps, progressStage = "准备中",
                    lastSeed = actualSeed,
                    lastPixels = width * height,
                )
            }
            val bitmap = withContext(Dispatchers.Default) {
                try {
                    SdEngine.generate(
                        prompt, negative, width, height, steps, cfg, actualSeed, "euler",
                        // 只有「省内存」档才开 VAE 分块；分块在 CPU 上要重算重叠区，纯亏时间
                        vaeTiling = st.policy == MemPolicy.SAFE,
                    )
                } catch (t: Throwable) {
                    _state.update { it.copy(error = t.message ?: "生成异常") }
                    null
                }
            }
            _state.update {
                it.copy(
                    generating = false,
                    image = bitmap ?: it.image,
                    progressStage = if (bitmap != null) "完成" else "",
                    stepSeconds = 0f,
                    lastStepSeconds = if (it.stepSeconds > 0f) it.stepSeconds else it.lastStepSeconds,
                    lastTiming = if (bitmap != null) SdEngine.lastTiming() else it.lastTiming,
                    message = if (bitmap != null) "seed = $actualSeed" else it.message,
                )
            }
        }
    }

    fun cancel() {
        SdEngine.cancel()
        _state.update { it.copy(progressStage = "正在取消…") }
    }

    fun saveImage(): Uri? {
        val bitmap = _state.value.image ?: return null
        return try {
            val resolver = getApplication<Application>().contentResolver
            val name = "qwenpaint_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QwenPaint")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            _state.update { it.copy(message = "已保存到相册 Pictures/QwenPaint") }
            uri
        } catch (t: Throwable) {
            _state.update { it.copy(error = "保存失败: ${t.message}") }
            null
        }
    }

    // ---------------- 在线升级 ----------------

    private var updateCancelled = false

    /** 检查更新（手动触发或启动时自动触发） */
    fun checkUpdate(silent: Boolean = false) {
        if (_state.value.checkingUpdate || _state.value.updateProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(checkingUpdate = true, error = null) }
            val result = withContext(Dispatchers.IO) { UpdateManager.check(getApplication()) }
            _state.update { st ->
                when (result) {
                    is UpdateManager.CheckResult.Update -> st.copy(
                        checkingUpdate = false,
                        updateInfo = result.release,
                        message = "发现新版本 v${result.release.versionName}",
                    )
                    UpdateManager.CheckResult.UpToDate -> st.copy(
                        checkingUpdate = false,
                        updateInfo = null,
                        message = if (silent) null else "已是最新版本",
                    )
                    is UpdateManager.CheckResult.Failed -> st.copy(
                        checkingUpdate = false,
                        message = if (silent) null else st.message,
                        error = if (silent) null else "检查更新失败：${result.reason}",
                    )
                }
            }
        }
    }

    /** 下载新版本 APK，完成后自动调起安装 */
    fun downloadUpdate() {
        val release = _state.value.updateInfo ?: return
        if (_state.value.updateProgress != null) return
        updateCancelled = false
        viewModelScope.launch {
            _state.update {
                it.copy(updateProgress = 0f, updateCancel = false, error = null,
                    message = "正在下载 v${release.versionName}（约 ${ModelRepository.readableSize(release.size)}）…")
            }
            val result = withContext(Dispatchers.IO) {
                UpdateManager.download(
                    context = getApplication(),
                    release = release,
                    onProgress = { done, total ->
                        val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                        _state.update { st ->
                            st.copy(
                                updateProgress = p,
                                message = "已下载 ${ModelRepository.readableSize(done)}" +
                                    if (total > 0) " / ${ModelRepository.readableSize(total)}" else "",
                            )
                        }
                    },
                    isCancelled = { updateCancelled },
                )
            }
            when (result) {
                is UpdateManager.DownloadResult.Ok -> {
                    _state.update {
                        it.copy(updateProgress = null, updateReadyApk = result.apk,
                            message = "下载完成，正在调起安装…")
                    }
                    // Android 8+ 需要用户授权「安装未知应用」，授权页返回后用户可再点一次
                    runCatching { UpdateManager.install(getApplication(), result.apk) }
                        .onFailure { e ->
                            _state.update { it.copy(error = "无法调起安装器：${e.message}") }
                        }
                }
                is UpdateManager.DownloadResult.Failed -> {
                    _state.update {
                        it.copy(updateProgress = null, error = "升级包下载失败：${result.reason}")
                    }
                }
            }
        }
    }

    fun cancelUpdate() {
        updateCancelled = true
        _state.update { it.copy(message = "正在取消下载…") }
    }

    /** 用户授权「安装未知应用」回来后再次点击安装 */
    fun installReadyUpdate() {
        val apk = _state.value.updateReadyApk ?: return
        runCatching { UpdateManager.install(getApplication(), apk) }
            .onFailure { e -> _state.update { it.copy(error = "无法调起安装器：${e.message}") } }
    }

    fun dismissUpdate() {
        _state.update { it.copy(updateInfo = null) }
    }

    /** 硬件探测：Vulkan/OpenCL 可用性，结果弹窗展示 */
    fun probeHardware() {
        viewModelScope.launch {
            _state.update { it.copy(message = "正在探测硬件接口…") }
            val report = withContext(Dispatchers.IO) { SdEngine.probeHardware() }
            _state.update { it.copy(hwProbe = report, message = null) }
        }
    }

    fun dismissProbe() {
        _state.update { it.copy(hwProbe = null) }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, message = null) }
    }

    fun deleteFile(file: File) {
        if (_state.value.currentSet?.files?.contains(file) == true) {
            SdEngine.release()
            _state.update { it.copy(engineReady = false) }
        }
        ModelRepository.delete(file)
        refreshModels()
    }

    override fun onCleared() {
        SdEngine.setProgressListener(null)
        SdEngine.release()
        super.onCleared()
    }
}
