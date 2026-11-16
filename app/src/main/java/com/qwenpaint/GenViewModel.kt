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
    val useGpu: Boolean = true,
    val deviceNames: List<Pair<String, String>> = emptyList(),
    val engineReady: Boolean = false,
    val loadingModel: Boolean = false,
    val generating: Boolean = false,
    val progressCompleted: Int = 0,
    val progressTotal: Int = 0,
    val progressStage: String = "",
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

    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

    init {
        SdEngine.setProgressListener { completed, total, stage ->
            _state.update {
                it.copy(progressCompleted = completed, progressTotal = total, progressStage = stage)
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
                it.copy(gpuAvailable = hasGpu, useGpu = hasGpu, deviceNames = devices)
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
                )
            }
            val bitmap = withContext(Dispatchers.Default) {
                try {
                    SdEngine.generate(prompt, negative, width, height, steps, cfg, actualSeed, "euler")
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
