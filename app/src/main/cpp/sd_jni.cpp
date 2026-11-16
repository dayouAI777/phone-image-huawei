// JNI bridge for stable-diffusion.cpp (new key/value C API, 2026 refactor).
// Verified line-by-line against include/stable-diffusion.h in the vendored sdcpp tree.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
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

struct EngineHandle {
    sd_ctx_t* ctx = nullptr;
};

std::string lastError() {
    const char* e = sd_get_last_error();
    return (e != nullptr && e[0] != '\0') ? std::string(e) : std::string("(no error info)");
}

// ---------------------------------------------------------------- progress

void pushProgress(int completed, int total, const char* stage) {
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
                                  jStage);
        if (jStage != nullptr) env->DeleteLocalRef(jStage);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (attached) g_vm->DetachCurrentThread();
}

// sdcpp installs this process-wide, not per-context.
void onProgress(int step, int steps, float /*time*/, void* /*data*/) {
    if (g_cancelRequested.load()) return;
    pushProgress(step, steps, "sampling");
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
                                              "(IILjava/lang/String;)V");
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
    LOGI("engine ready: model=%s dit=%s threads=%d", modelPath.c_str(), diffusionPath.c_str(), jThreads);
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
                                           jstring jMethod) {
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
    setBool(opt, "vae_tiling.enabled", true);

    if (!ok) {
        sd_opt_free(opt);
        throwRuntime(env, "invalid generation params: " + lastError());
        return nullptr;
    }

    pushProgress(0, jSteps, "text-encode");

    sd_result_t* result = nullptr;
    sd_status_t st = sd_generate_image(h->ctx, opt, &result);
    sd_opt_free(opt);

    if (st != SD_STATUS_OK || result == nullptr) {
        throwRuntime(env, "generation failed (" + std::to_string(static_cast<int>(st)) + "): " + lastError());
        if (result != nullptr) sd_result_free(result);
        return nullptr;
    }

    pushProgress(jSteps, jSteps, "vae-decode");

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
