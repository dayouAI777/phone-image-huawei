package com.qwenpaint

import android.graphics.Bitmap

/**
 * 一次加载所需的完整模型配置。
 * SD1.5 只需 modelPath；Qwen-Image / FLUX 这类 DiT 模型需要 DiT + 文本编码器 + VAE 分离挂载。
 */
data class ModelConfig(
    val modelPath: String = "",
    val diffusionPath: String = "",
    val vaePath: String = "",
    val llmPath: String = "",
    val llmVisionPath: String = "",
    val clipLPath: String = "",
    val clipGPath: String = "",
    val t5Path: String = "",
    val threads: Int = 4,
    val wtype: String = "auto",
    val flashAttn: Boolean = false,
    val enableMmap: Boolean = true,
    /** ggml 设备规范，如 "vulkan" / "cpu"；空串交给库自动选择 */
    val backend: String = "",
    val paramsBackend: String = "",
    val maxVram: String = "",
) {
    val hasModel: Boolean get() = modelPath.isNotEmpty() || diffusionPath.isNotEmpty()
}

/** stable-diffusion.cpp 的 JNI 封装，so 产物为 libqwenpaint.so */
object SdEngine {

    @Volatile
    private var progressListener: ((completed: Int, total: Int, seconds: Float, stage: String) -> Unit)? = null

    /** 由 native 层回调，勿改名（JNI 按名称绑定）。seconds = 本步耗时，sdcpp 只在采样阶段给值 */
    @JvmStatic
    fun onNativeProgress(completed: Int, total: Int, seconds: Float, stage: String) {
        progressListener?.invoke(completed, total, seconds, stage)
    }

    fun setProgressListener(listener: ((Int, Int, Float, String) -> Unit)?) {
        progressListener = listener
    }

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("qwenpaint")
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    fun isAvailable(): Boolean = loaded

    private var handle: Long = 0L

    val isReady: Boolean get() = handle != 0L

    @Synchronized
    fun loadModel(cfg: ModelConfig): Result<Unit> {
        if (!loaded) return Result.failure(IllegalStateException("native 库加载失败"))
        if (!cfg.hasModel) return Result.failure(IllegalArgumentException("未指定模型文件"))
        if (handle != 0L) release()
        return try {
            handle = nativeInit(
                cfg.modelPath,
                cfg.diffusionPath,
                cfg.vaePath,
                cfg.llmPath,
                cfg.llmVisionPath,
                cfg.clipLPath,
                cfg.clipGPath,
                cfg.t5Path,
                cfg.threads,
                cfg.wtype,
                cfg.flashAttn,
                cfg.enableMmap,
                cfg.paramsBackend,
                cfg.backend,
                cfg.maxVram,
            )
            if (handle == 0L) {
                Result.failure(IllegalStateException("模型加载失败"))
            } else {
                Result.success(Unit)
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun generate(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        method: String = "euler",
    ): Bitmap? {
        val h = handle
        if (h == 0L) return null
        val pixels = nativeGenerate(h, prompt, negativePrompt, width, height, steps, cfg, seed, method)
            ?: return null
        if (pixels.isEmpty()) return null
        return try {
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    fun cancel() {
        val h = handle
        if (h != 0L) nativeCancel(h)
    }

    @Synchronized
    fun release() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    fun version(): String = if (loaded) nativeVersion() else "native 未加载"

    /** 上一次生成的耗时明细，例如「总 41.2s · 文本编码 3.1s · 采样 4 步 30.5s…」 */
    fun lastTiming(): String = try {
        if (loaded) nativeLastTiming() else ""
    } catch (t: Throwable) {
        ""
    }

    // ---- native（参数顺序必须与 sd_jni.cpp 严格一致）----
    private external fun nativeInit(
        modelPath: String,
        diffusionPath: String,
        vaePath: String,
        llmPath: String,
        llmVisionPath: String,
        clipLPath: String,
        clipGPath: String,
        t5Path: String,
        threads: Int,
        wtype: String,
        flashAttn: Boolean,
        enableMmap: Boolean,
        paramsBackend: String,
        backend: String,
        maxVram: String,
    ): Long

    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        method: String,
    ): IntArray?

    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)
    private external fun nativeVersion(): String
    private external fun nativeLastTiming(): String
    private external fun nativeProbeHardware(): String
    private external fun nativeListDevices(): String

    /** ggml 实际编译进 so 的可用设备（"名称<TAB>说明" 每行一个） */
    fun listDevices(): List<Pair<String, String>> = try {
        nativeListDevices()
            .lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val tab = trimmed.indexOf('\t')
                if (tab < 0) trimmed to "" else trimmed.substring(0, tab) to trimmed.substring(tab + 1)
            }
            .toList()
    } catch (t: Throwable) {
        emptyList()
    }

    /** 探测容器暴露的硬件接口（Vulkan/OpenCL），结果可直接贴给开发者 */
    fun probeHardware(): String = try {
        nativeProbeHardware()
    } catch (t: Throwable) {
        "探测失败：${t.message}"
    }
}
