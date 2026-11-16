// JNI bridge for stable-diffusion.cpp (new key/value C API, 2026 refactor).
// Verified line-by-line against include/stable-diffusion.h in the vendored sdcpp tree.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#define LOG_TAG "QwenPaint"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM* g_vm = nullptr;
jclass g_clsEngine = nullptr;
jmethodID g_midProgress = nullptr;

std::atomic<bool> g_cancelRequested{false};

// ---- 耗时统计 ----
// sdcpp 的进度回调是进程级的，采样在生成线程里同步跑，所以这几个全局量
// 只有「生成线程写、UI 线程读」两种访问，用 mutex 保住最后一次结果即可。
std::mutex g_timingMutex;
std::string g_lastTiming;
std::chrono::steady_clock::time_point g_genStart;
std::atomic<bool>  g_firstStepSeen{false};
std::atomic<float> g_encodeSec{-1.f};
std::atomic<float> g_lastStepAt{-1.f};
// 采样阶段平均每步耗时，供「性能自检」直接读取，不必解析字符串
std::atomic<float> g_lastStepAvg{-1.f};
std::atomic<float> g_lastTailSec{-1.f};
// sdcpp 进度回调里给的 time 就是 s/it（src/core/util.cpp），最权威的单步耗时
std::atomic<float> g_sdcppStepSec{-1.f};

struct EngineHandle {
    sd_ctx_t* ctx = nullptr;
};

std::string lastError() {
    const char* e = sd_get_last_error();
    return (e != nullptr && e[0] != '\0') ? std::string(e) : std::string("(no error info)");
}

// ---------------------------------------------------------------- progress

void pushProgress(int completed, int total, float seconds, const char* stage) {
    if (g_vm == nullptr || g_clsEngine == nullptr || g_midProgress == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    if (env != nullptr) {
        jstring jStage = env->NewStringUTF(stage);
        env->CallStaticVoidMethod(g_clsEngine, g_midProgress,
                                  static_cast<jint>(completed),
                                  static_cast<jint>(total),
                                  static_cast<jfloat>(seconds),
                                  jStage);
        if (jStage != nullptr) env->DeleteLocalRef(jStage);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (attached) g_vm->DetachCurrentThread();
}

// sdcpp installs this process-wide, not per-context.
// time 是「本步耗时」（秒），sdcpp 内部按 s/it 显示；第一次回调代表文本编码已结束。
void onProgress(int step, int steps, float time, void* /*data*/) {
    if (g_cancelRequested.load()) return;
    if (!g_firstStepSeen.exchange(true)) {
        const float enc = std::chrono::duration<float>(
            std::chrono::steady_clock::now() - g_genStart).count();
        g_encodeSec.store(enc);
    }
    g_lastStepAt.store(std::chrono::duration<float>(
        std::chrono::steady_clock::now() - g_genStart).count());
    if (time > 0.f) g_sdcppStepSec.store(time);
    pushProgress(step, steps, time, "sampling");
}

// ---------------------------------------------------------------- helpers

void throwRuntime(JNIEnv* env, const std::string& msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, msg.c_str());
        env->DeleteLocalRef(cls);
    }
}

std::string jstr(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string out(p != nullptr ? p : "");
    if (p != nullptr) env->ReleaseStringUTFChars(s, p);
    return out;
}

bool setStr(sd_opt_t* opt, const char* key, const std::string& val) {
    if (val.empty()) return true;  // keep library default
    sd_status_t st = sd_opt_set_string(opt, key, val.c_str());
    if (st != SD_STATUS_OK) {
        LOGE("set %s failed: %d (%s)", key, static_cast<int>(st), lastError().c_str());
        return false;
    }
    return true;
}

bool setInt(sd_opt_t* opt, const char* key, int64_t val) {
    sd_status_t st = sd_opt_set_int(opt, key, val);
    if (st != SD_STATUS_OK) {
        LOGE("set %s failed: %d (%s)", key, static_cast<int>(st), lastError().c_str());
        return false;
    }
    return true;
}

bool setBool(sd_opt_t* opt, const char* key, bool val) {
    sd_status_t st = sd_opt_set_bool(opt, key, val);
    if (st != SD_STATUS_OK) {
        LOGE("set %s failed: %d (%s)", key, static_cast<int>(st), lastError().c_str());
        return false;
    }
    return true;
}

bool setFloat(sd_opt_t* opt, const char* key, double val) {
    sd_status_t st = sd_opt_set_float(opt, key, val);
    if (st != SD_STATUS_OK) {
        LOGE("set %s failed: %d (%s)", key, static_cast<int>(st), lastError().c_str());
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_qwenpaint_SdEngine_nativeVersion(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("sdcpp-kv-api/2026");
}

// 最近一次生成的耗时明细（文本编码 / 采样 / 出图），由 nativeGenerate 汇总
extern "C" JNIEXPORT jstring JNICALL
Java_com_qwenpaint_SdEngine_nativeLastTiming(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lk(g_timingMutex);
    return env->NewStringUTF(g_lastTiming.c_str());
}

// 单步采样耗时（秒），-1 表示本次没有采到；供「性能自检」用，免去解析字符串
extern "C" JNIEXPORT jfloat JNICALL
Java_com_qwenpaint_SdEngine_nativeLastStepSec(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jfloat>(g_lastStepAvg.load());
}

// 采样结束到出图（VAE 解码）那段耗时，秒
extern "C" JNIEXPORT jfloat JNICALL
Java_com_qwenpaint_SdEngine_nativeLastTailSec(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jfloat>(g_lastTailSec.load());
}

// Enumerates the ggml devices compiled into this .so (CPU always, Vulkan when the
// GPU build is in use). Format: "name<TAB>description" per line.
extern "C" JNIEXPORT jstring JNICALL
Java_com_qwenpaint_SdEngine_nativeListDevices(JNIEnv* env, jobject /*thiz*/) {
    size_t need = sd_list_devices(nullptr, 0);
    if (need == 0) return env->NewStringUTF("");
    std::vector<char> buf(need + 1, '\0');
    size_t got = sd_list_devices(buf.data(), buf.size());
    if (got > buf.size()) got = buf.size();
    return env->NewStringUTF(std::string(buf.data(), got).c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_qwenpaint_SdEngine_nativeInit(JNIEnv* env, jobject /*thiz*/,
                                       jstring jModelPath,
                                       jstring jDiffusionPath,
                                       jstring jVaePath,
                                       jstring jLlmPath,
                                       jstring jLlmVisionPath,
                                       jstring jClipLPath,
                                       jstring jClipGPath,
                                       jstring jT5Path,
                                       jint    jThreads,
                                       jstring jWtype,
                                       jboolean jFlashAttn,
                                       jboolean jEnableMmap,
                                       jstring jParamsBackend,
                                       jstring jBackend,
                                       jstring jMaxVram) {
    if (g_clsEngine == nullptr) {
        jclass local = env->FindClass("com/qwenpaint/SdEngine");
        if (local == nullptr) {
            env->ExceptionClear();
            throwRuntime(env, "com.qwenpaint.SdEngine not found");
            return 0;
        }
        g_clsEngine = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        g_midProgress = env->GetStaticMethodID(g_clsEngine, "onNativeProgress",
                                              "(IIFLjava/lang/String;)V");
        if (g_midProgress == nullptr) {
            env->ExceptionClear();
            LOGE("onNativeProgress signature mismatch; progress disabled");
        }
    }

    const std::string modelPath     = jstr(env, jModelPath);
    const std::string diffusionPath = jstr(env, jDiffusionPath);
    const std::string vaePath       = jstr(env, jVaePath);
    const std::string llmPath       = jstr(env, jLlmPath);
    const std::string llmVisionPath = jstr(env, jLlmVisionPath);
    const std::string clipLPath     = jstr(env, jClipLPath);
    const std::string clipGPath     = jstr(env, jClipGPath);
    const std::string t5Path        = jstr(env, jT5Path);
    const std::string wtype         = jstr(env, jWtype);
    const std::string paramsBackend = jstr(env, jParamsBackend);
    const std::string backend       = jstr(env, jBackend);
    const std::string maxVram       = jstr(env, jMaxVram);

    if (modelPath.empty() && diffusionPath.empty()) {
        throwRuntime(env, "no model file specified");
        return 0;
    }

    sd_ctx_opt_t* opt = sd_ctx_opt_create();
    if (opt == nullptr) {
        throwRuntime(env, "sd_ctx_opt_create failed");
        return 0;
    }

    bool ok = true;
    ok = ok && setStr(opt, "model_path", modelPath);
    ok = ok && setStr(opt, "diffusion_model_path", diffusionPath);
    ok = ok && setStr(opt, "vae_path", vaePath);
    ok = ok && setStr(opt, "llm_path", llmPath);
    ok = ok && setStr(opt, "llm_vision_path", llmVisionPath);
    ok = ok && setStr(opt, "clip_l_path", clipLPath);
    ok = ok && setStr(opt, "clip_g_path", clipGPath);
    ok = ok && setStr(opt, "t5xxl_path", t5Path);
    ok = ok && setStr(opt, "params_backend", paramsBackend);
    ok = ok && setStr(opt, "backend", backend);
    ok = ok && setStr(opt, "max_vram", maxVram);
    ok = ok && setInt(opt, "n_threads", jThreads > 0 ? jThreads : 4);
    ok = ok && setStr(opt, "wtype", wtype.empty() ? std::string("auto") : wtype);
    setBool(opt, "flash_attn", jFlashAttn == JNI_TRUE);
    setBool(opt, "enable_mmap", jEnableMmap == JNI_TRUE);

    sd_ctx_t* ctx = nullptr;
    sd_status_t st = sd_ctx_create(opt, &ctx);
    sd_opt_free(opt);

    if (!ok || st != SD_STATUS_OK || ctx == nullptr) {
        throwRuntime(env, "model load failed (" + std::to_string(static_cast<int>(st)) + "): " + lastError());
        return 0;
    }

    EngineHandle* h = new (std::nothrow) EngineHandle();
    if (h == nullptr) {
        sd_ctx_free(ctx);
        throwRuntime(env, "out of memory allocating engine handle");
        return 0;
    }
    h->ctx = ctx;

    sd_set_progress_callback(onProgress, nullptr);
    LOGI("engine ready: model=%s dit=%s threads=%d backend=%s params_backend=%s",
         modelPath.c_str(), diffusionPath.c_str(), jThreads,
         backend.empty() ? "auto" : backend.c_str(),
         paramsBackend.empty() ? "auto" : paramsBackend.c_str());
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_qwenpaint_SdEngine_nativeGenerate(JNIEnv* env, jobject /*thiz*/,
                                           jlong   jHandle,
                                           jstring jPrompt,
                                           jstring jNegativePrompt,
                                           jint    jWidth,
                                           jint    jHeight,
                                           jint    jSteps,
                                           jfloat  jCfg,
                                           jlong   jSeed,
                                           jstring jMethod,
                                           jboolean jVaeTiling) {
    EngineHandle* h = reinterpret_cast<EngineHandle*>(jHandle);
    if (h == nullptr || h->ctx == nullptr) {
        throwRuntime(env, "engine not initialized");
        return nullptr;
    }

    const std::string prompt = jstr(env, jPrompt);
    const std::string negative = jstr(env, jNegativePrompt);
    const std::string method = jstr(env, jMethod);

    if (prompt.empty()) {
        throwRuntime(env, "empty prompt");
        return nullptr;
    }

    g_cancelRequested.store(false);

    sd_img_gen_opt_t* opt = sd_img_gen_opt_create();
    if (opt == nullptr) {
        throwRuntime(env, "sd_img_gen_opt_create failed");
        return nullptr;
    }

    bool ok = true;
    ok = ok && setStr(opt, "prompt", prompt);
    ok = ok && setStr(opt, "negative_prompt", negative);
    ok = ok && setInt(opt, "width", jWidth > 0 ? jWidth : 512);
    ok = ok && setInt(opt, "height", jHeight > 0 ? jHeight : 512);
    ok = ok && setInt(opt, "sample.steps", jSteps > 0 ? jSteps : 4);
    ok = ok && setInt(opt, "seed", jSeed);
    ok = ok && setInt(opt, "batch_count", 1);
    ok = ok && setStr(opt, "sample.method", method.empty() ? std::string("euler") : method);
    setFloat(opt, "sample.guidance.txt_cfg", static_cast<double>(jCfg));
    setFloat(opt, "sample.guidance.distilled_guidance", static_cast<double>(jCfg));
    // 分块解码在 CPU 上是净损失（每块都要重叠重算），只有真的内存吃紧才值得开
    setBool(opt, "vae_tiling.enabled", jVaeTiling == JNI_TRUE);

    if (!ok) {
        sd_opt_free(opt);
        throwRuntime(env, "invalid generation params: " + lastError());
        return nullptr;
    }

    g_cancelRequested.store(false);
    g_genStart = std::chrono::steady_clock::now();
    g_firstStepSeen.store(false);
    g_encodeSec.store(-1.f);
    g_lastStepAt.store(-1.f);
    pushProgress(0, jSteps, 0.f, "text-encode");

    sd_result_t* result = nullptr;
    sd_status_t st = sd_generate_image(h->ctx, opt, &result);
    sd_opt_free(opt);

    if (st != SD_STATUS_OK || result == nullptr) {
        throwRuntime(env, "generation failed (" + std::to_string(static_cast<int>(st)) + "): " + lastError());
        if (result != nullptr) sd_result_free(result);
        return nullptr;
    }

    const float totalSec = std::chrono::duration<float>(
        std::chrono::steady_clock::now() - g_genStart).count();
    pushProgress(jSteps, jSteps, 0.f, "vae-decode");

    const sd_tensor_t* img = sd_result_get_image(result, 0);
    if (img == nullptr || sd_tensor_rank(img) < 3) {
        sd_result_free(result);
        throwRuntime(env, "no image in result");
        return nullptr;
    }

    const int64_t channels = sd_tensor_dim(img, 0);  // U8 [C,W,H]
    const int64_t width    = sd_tensor_dim(img, 1);
    const int64_t height   = sd_tensor_dim(img, 2);
    const uint8_t* src     = static_cast<const uint8_t*>(sd_tensor_data(img));

    if (channels < 3 || src == nullptr || width <= 0 || height <= 0) {
        sd_result_free(result);
        throwRuntime(env, "unexpected image tensor layout");
        return nullptr;
    }

    const jsize pixelCount = static_cast<jsize>(width * height);
    jintArray out = env->NewIntArray(pixelCount);
    if (out == nullptr) {
        sd_result_free(result);
        throwRuntime(env, "out of memory allocating bitmap buffer");
        return nullptr;
    }

    std::vector<jint> argb(static_cast<size_t>(pixelCount));
    const bool hasAlpha = channels >= 4;
    // Layout: row-major interleaved U8, offset(c,x,y) = (y * width + x) * channels + c
    // (matches src/core/util.cpp:813 and src/detailer.cpp:531 upstream)
    for (int64_t y = 0; y < height; ++y) {
        for (int64_t x = 0; x < width; ++x) {
            const int64_t base = (y * width + x) * channels;
            const uint8_t r = src[base];
            const uint8_t g = src[base + 1];
            const uint8_t b = src[base + 2];
            const uint8_t a = hasAlpha ? src[base + 3] : 255;
            argb[static_cast<size_t>(y * width + x)] =
                (static_cast<jint>(a) << 24) | (static_cast<jint>(r) << 16) |
                (static_cast<jint>(g) << 8) | static_cast<jint>(b);
        }
    }

    env->SetIntArrayRegion(out, 0, pixelCount, argb.data());
    sd_result_free(result);

    // 汇总耗时，供 UI 显示：把「慢」拆成编码 / 采样 / 出图三段，才知道该往哪优化
    {
        const float enc  = g_encodeSec.load();
        const float last = g_lastStepAt.load();
        // 首步回调时 enc 已经把第 1 步算完了，所以 last-enc 是 (steps-1) 步的时间
        const float sampleSec = (last > 0.f && enc >= 0.f) ? (last - enc) : -1.f;
        const float tailSec   = (last > 0.f) ? (totalSec - last) : totalSec;
        float sep = g_sdcppStepSec.load();
        if (sep <= 0.f && jSteps > 1 && sampleSec > 0.f) {
            sep = sampleSec / static_cast<float>(jSteps - 1);
        }
        const float sampleTotal = sep > 0.f ? sep * static_cast<float>(jSteps) : sampleSec;
        g_lastStepAvg.store(sep);
        g_lastTailSec.store(tailSec);
        char buf[192];
        if (enc >= 0.f) {
            std::snprintf(buf, sizeof(buf),
                          "总 %.1fs · 文本编码 %.1fs · 采样 %d 步 %.1fs（%.1fs/步）· 出图 %.1fs",
                          totalSec, enc, static_cast<int>(jSteps),
                          sampleTotal < 0.f ? 0.f : sampleTotal,
                          sep > 0.f ? sep : 0.f,
                          tailSec < 0.f ? 0.f : tailSec);
        } else {
            std::snprintf(buf, sizeof(buf), "总 %.1fs", totalSec);
        }
        std::lock_guard<std::mutex> lk(g_timingMutex);
        g_lastTiming = buf;
        LOGI("timing: %s", buf);
    }

    LOGI("generated %lldx%lld ch=%lld", static_cast<long long>(width),
         static_cast<long long>(height), static_cast<long long>(channels));
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_qwenpaint_SdEngine_nativeCancel(JNIEnv* /*env*/, jobject /*thiz*/, jlong jHandle) {
    EngineHandle* h = reinterpret_cast<EngineHandle*>(jHandle);
    if (h == nullptr || h->ctx == nullptr) return;
    g_cancelRequested.store(true);
    sd_cancel_generation(h->ctx, SD_CANCEL_ALL);
    LOGI("cancel requested");
}

extern "C" JNIEXPORT void JNICALL
Java_com_qwenpaint_SdEngine_nativeFree(JNIEnv* /*env*/, jobject /*thiz*/, jlong jHandle) {
    EngineHandle* h = reinterpret_cast<EngineHandle*>(jHandle);
    if (h == nullptr) return;
    sd_set_progress_callback(nullptr, nullptr);
    if (h->ctx != nullptr) {
        sd_ctx_free(h->ctx);
        h->ctx = nullptr;
    }
    delete h;
    LOGI("engine freed");
}

// ---------------------------------------------------------------- hardware probe
// 探测卓易通容器到底暴露了什么硬件接口：Vulkan 计算 / OpenCL。
// 全部走 dlopen 运行时加载，没有对应驱动就明确报“不存在”，不猜。

#include <dlfcn.h>
#include <vulkan/vulkan.h>
#include <sstream>

namespace {

std::string probeVulkan() {
    std::ostringstream out;
    void* lib = dlopen("libvulkan.so", RTLD_NOW);
    if (lib == nullptr) {
        out << "libvulkan.so: 不存在（容器未暴露 Vulkan 驱动）\n";
        return out.str();
    }
    auto fpCreate = reinterpret_cast<PFN_vkCreateInstance>(dlsym(lib, "vkCreateInstance"));
    auto fpEnum = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(dlsym(lib, "vkEnumeratePhysicalDevices"));
    auto fpProps = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(dlsym(lib, "vkGetPhysicalDeviceProperties"));
    auto fpDestroy = reinterpret_cast<PFN_vkDestroyInstance>(dlsym(lib, "vkDestroyInstance"));
    if (fpCreate == nullptr || fpEnum == nullptr || fpProps == nullptr) {
        out << "libvulkan.so: 存在但符号不全（残缺驱动）\n";
        dlclose(lib);
        return out.str();
    }

    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "qwenpaint-probe";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ci{};
    ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ci.pApplicationInfo = &app;
    VkInstance inst = VK_NULL_HANDLE;
    VkResult r = fpCreate(&ci, nullptr, &inst);
    if (r != VK_SUCCESS || inst == VK_NULL_HANDLE) {
        out << "vkCreateInstance 失败，VkResult=" << static_cast<int>(r) << "\n";
        dlclose(lib);
        return out.str();
    }

    uint32_t count = 0;
    fpEnum(inst, &count, nullptr);
    out << "Vulkan 设备数: " << count << "\n";
    if (count > 0) {
        std::vector<VkPhysicalDevice> devs(count);
        fpEnum(inst, &count, devs.data());
        const char* typeNames[] = {"OTHER", "INTEGRATED_GPU", "DISCRETE_GPU", "VIRTUAL_GPU", "CPU"};
        for (uint32_t i = 0; i < count; ++i) {
            VkPhysicalDeviceProperties p{};
            fpProps(devs[i], &p);
            int t = (p.deviceType <= 4) ? p.deviceType : 0;
            out << "  [" << i << "] " << p.deviceName << "\n"
                << "      类型=" << typeNames[t]
                << "  API=" << VK_VERSION_MAJOR(p.apiVersion) << "." << VK_VERSION_MINOR(p.apiVersion)
                << "  驱动=" << p.driverVersion << "\n";
        }
    }
    if (fpDestroy != nullptr) fpDestroy(inst, nullptr);
    dlclose(lib);
    return out.str();
}

// OpenCL 最小声明（NDK 不带 OpenCL 头文件）
typedef int32_t cl_int;
typedef uint32_t cl_uint;
typedef uint64_t cl_ulong;
typedef void* cl_platform_id;
typedef void* cl_device_id;
typedef void* cl_context;

std::string probeOpenCL() {
    std::ostringstream out;
    const char* candidates[] = {"libOpenCL.so", "libOpenCL.so.1", "/vendor/lib64/libOpenCL.so",
                                "/system/vendor/lib64/libOpenCL.so"};
    void* lib = nullptr;
    for (const char* name : candidates) {
        lib = dlopen(name, RTLD_NOW);
        if (lib != nullptr) break;
    }
    if (lib == nullptr) {
        out << "libOpenCL.so: 不存在（容器未暴露 OpenCL 驱动）\n";
        return out.str();
    }
    auto fpPlatforms = reinterpret_cast<cl_int (*)(cl_uint, cl_platform_id*, cl_uint*)>(
        dlsym(lib, "clGetPlatformIDs"));
    auto fpDevices = reinterpret_cast<cl_int (*)(cl_platform_id, cl_ulong, cl_uint, cl_device_id*, cl_uint*)>(
        dlsym(lib, "clGetDeviceIDs"));
    auto fpDevInfo = reinterpret_cast<cl_int (*)(cl_device_id, cl_uint, size_t, void*, size_t*)>(
        dlsym(lib, "clGetDeviceInfo"));
    if (fpPlatforms == nullptr || fpDevices == nullptr || fpDevInfo == nullptr) {
        out << "libOpenCL.so: 存在但符号不全\n";
        dlclose(lib);
        return out.str();
    }
    cl_uint np = 0;
    cl_int err = fpPlatforms(0, nullptr, &np);
    out << "OpenCL 平台数: " << (err == 0 ? std::to_string(np) : ("查询失败 err=" + std::to_string(err))) << "\n";
    if (err == 0 && np > 0) {
        std::vector<cl_platform_id> plats(np);
        fpPlatforms(np, plats.data(), nullptr);
        const cl_ulong CL_DEVICE_TYPE_ALL = 0xFFFFFFFF;
        const cl_uint CL_DEVICE_NAME = 0x102B, CL_DEVICE_VENDOR = 0x102C;
        for (cl_uint i = 0; i < np; ++i) {
            cl_uint nd = 0;
            fpDevices(plats[i], CL_DEVICE_TYPE_ALL, 0, nullptr, &nd);
            for (cl_uint d = 0; d < nd; ++d) {
                std::vector<cl_device_id> devs(nd);
                fpDevices(plats[i], CL_DEVICE_TYPE_ALL, nd, devs.data(), nullptr);
                char name[256] = {0}, vendor[256] = {0};
                fpDevInfo(devs[d], CL_DEVICE_NAME, sizeof(name), name, nullptr);
                fpDevInfo(devs[d], CL_DEVICE_VENDOR, sizeof(vendor), vendor, nullptr);
                out << "  [p" << i << " d" << d << "] " << name << " (" << vendor << ")\n";
            }
        }
    }
    dlclose(lib);
    return out.str();
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_qwenpaint_SdEngine_nativeProbeHardware(JNIEnv* env, jclass /*clazz*/) {
    std::ostringstream out;
    out << "== Vulkan 计算探测 ==\n" << probeVulkan() << "\n";
    out << "== OpenCL 探测 ==\n" << probeOpenCL() << "\n";
    out << "== 说明 ==\n"
        << "· 发现 INTEGRATED_GPU 且有 Vulkan ≥1.1：可启用 ggml Vulkan 后端，扩散模型可走 GPU 加速\n"
        << "· NNAPI（麒麟 NPU 的 Android 入口）无公开枚举接口，需 TFLite 委托实测，本探测不覆盖\n"
        << "· 麒麟 NPU 的 CANN Kit/NNRt 是鸿蒙原生 API，Android 容器理论上不可达\n";
    std::string s = out.str();
    LOGI("probe:\n%s", s.c_str());
    return env->NewStringUTF(s.c_str());
}
