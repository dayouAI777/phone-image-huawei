# QwenPaint · 端侧离线生图（Android / 卓易通）

一个纯本地推理的文生图 App：不联网、不上传、不出户。装进华为 Mate 70 Pro 的**卓易通**里运行。

---

## 0. 先说清楚一件事

你要的是 **Qwen-Image 2.1 手机上离线生图**。这个组合在当前硬件上不成立，不是调优能解决的：

| 项目 | 数值 |
|---|---|
| Qwen-Image-2.1 视觉生成主干 | 7B 参数（32 层 Single-Stream DiT） |
| 文本/条件图编码器 | Qwen3-VL **8B** |
| BF16 权重合计 | ≈ 30 GB |
| 极限 4bit 量化后 | ≈ 9–10 GB |
| 默认输出 / 步数 | 2048×2048 / 40 步 |
| Mate 70 Pro 内存 | 16 GB（扣掉鸿蒙 + 卓易通容器，可用 6–9 GB） |

10 GB 静态权重 + 2048² latent + KV cache，塞不进容器；就算塞进去，麒麟 NPU 也没有这套混合注意力的端侧算子，纯 CPU 跑 7B DiT × 40 步，单张以小时计。

### 但「端侧跑 Qwen-Image」这件事本身是成立的 —— 在骁龙上

参考项目 [xororz/local-dream](https://github.com/xororz/local-dream) 已经做到了：Qwen-Image-2.1 在骁龙 8 Elite 上本地出图，用 Q4_0 DiT 或 F8_E4M3 FP8 DiT。它的 CMakeLists 里这条是硬门槛：

```cmake
set(QNN_SDK_ROOT "$ENV{QNN_SDK_ROOT}" CACHE PATH "Qualcomm AI Runtime SDK")
foreach(arch 68 69 73 75 79 81 85)
  file(COPY ".../libQnnHtpV${arch}.so" ...)   # Hexagon NPU 专用库
```

| 路线 | Qwen-Image-2.1 | Mate 70 Pro（麒麟 9020） |
|---|---|---|
| local-dream + QNN / Hexagon NPU | ✅ 需骁龙 8 Elite 级 + HTP v79 | ❌ 无 Hexagon、无 Adreno，QNN SDK 不出麒麟库 |
| local-dream + MNN CPU | ❌ 其 README 明确只覆盖 SD1.5 / SDXL | ✅ 可用 |
| 本工程 + sdcpp CPU | ⚠️ 可加载，16G 内存极限，速度未知 | ⚠️ 可试，需实测 |

麒麟的 NPU 走 CANN Kit / NNRt，但那是**鸿蒙原生 API**，卓易通这个 Android 容器里拿不到。所以在你这台机器上，**只有 CPU 一条路**。

**本工程的做法：把「能离线跑」做扎实（SD-Turbo / SD1.5 / SDXL 在 CPU 上可跑），同时把模型层完全打通 —— 支持分离式多文件模型（DiT + Qwen3-VL 编码器 + VAE），你随时可以导入 Qwen-Image-2.1 自己实测能到什么程度，代码不用改。**


---

## 1. 架构

```
┌─────────────────────────────────────────┐
│ 卓易通（Android 兼容容器）                 │
│  ┌───────────────────────────────────┐  │
│  │ QwenPaint.apk                      │  │
│  │  ArkTS? 否 —— Kotlin + Jetpack Compose │
│  │        ↓ JNI                       │  │
│  │  libqwenpaint.so  (arm64-v8a)      │  │
│  │        ↓                           │  │
│  │  stable-diffusion.cpp (ggml/NEON)  │  │
│  │        ↓ 纯 CPU 推理               │  │
│  │  模型文件 .gguf (放 /files/models)  │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

**为什么是纯 CPU、不用 GPU/NPU**：卓易通是兼容层，OpenCL/Vulkan/NPU 直通不可靠。ggml 的 ARM NEON 后端没有任何系统级依赖，是唯一稳定解。

---

## 2. 目录

```
qwen-paint/
├── app/src/main/
│   ├── cpp/
│   │   ├── CMakeLists.txt      # 交叉编译 ggml + sdcpp，产出 libqwenpaint.so
│   │   ├── sd_jni.cpp          # JNI 桥（init / generate / cancel / free + 进度回调）
│   │   └── third_party/stable-diffusion.cpp   # setup.ps1 自动拉取
│   ├── java/com/qwenpaint/
│   │   ├── MainActivity.kt     # Compose UI
│   │   ├── GenViewModel.kt     # 状态机
│   │   ├── SdEngine.kt         # JNI 封装
│   │   ├── ModelRepository.kt  # 模型导入/管理
│   │   ├── UpdateManager.kt    # 在线升级：检查/下载/校验/安装
│   │   └── ui/Theme.kt
│   └── res/
├── keystore/qwenpaint.jks      # 固定签名（随仓库分发，保证覆盖安装）
├── release/version.json        # 版本清单（App 启动时拉取它判断是否有新版）
└── scripts/
    ├── setup.ps1               # 装 NDK / 拉内核 / 写 local.properties
    ├── build.ps1               # 出 APK / 推模型 / adb 安装
    └── fetch-model.py          # 下模型（支持断点续传、HF 镜像）
```

---

## 2.5 在线升级机制

```
App 启动/点「检查更新」
   │  按序尝试 3 个镜像拉 release/version.json（gh-proxy → ghfast → 直连）
   ▼
versionCode > 本地？ ──否──→ 已是最新
   │是
   ▼  顶部横幅提示
「下载并安装」
   │  APK 断点续传（Range）+ 镜像轮替 + 每镜像 3 次退避重试
   ▼
size / SHA256 校验（不过则删除重下，最多 2 轮）
   ▼
FileProvider 调起系统安装器 → 覆盖安装
```

关键点：

- **固定签名**：`keystore/qwenpaint.jks` 随仓库分发（storepass/keypass 均为 `qwenpaint2026`），任何机器构建出的包签名一致，覆盖安装不会触发「签名不一致需先卸载」——**模型数据（/files/models，约 1.9GB）在升级后保留**。
- **版本清单** `release/version.json`：发新版时改 `versionCode/versionName/url/sha256/size/notes` 并提交，同时在 GitHub Releases 上传同名 APK 附件。
- **镜像**：清单和 APK 下载都内置 `gh-proxy.com`、`ghfast.top` 两个国内加速镜像，直连失败自动切换，下载中断自动续传。
- Android 8+ 首次覆盖安装会弹「允许安装未知应用」，授权后回 App 再点一次「立即安装」即可。

## 3. 构建（Windows）

### 前置
- Android Studio（含 JBR 17、SDK、cmdline-tools）
- 磁盘 ≥ 15 GB（NDK 约 2 GB + 依赖）

### 三条命令

```powershell
cd "路径\qwen-paint"

# 1) 环境：装 platform-35 / NDK 27 / cmake，拉推理内核，校验 API
powershell -ExecutionPolicy Bypass -File scripts\setup.ps1

# 2) 出 APK（首次编译 ggml 需 5-15 分钟）
powershell -ExecutionPolicy Bypass -File scripts\build.ps1

# 3) 手机连着 USB 的话可以直接装
powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -Install
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

> 如果 `setup.ps1` 提示 `sdkmanager not found`：Android Studio → SDK Manager → SDK Tools → 勾选 "Android SDK Command-line Tools" 装上再跑。

---

## 4. 装进卓易通

卓易通不认 adb，走文件侧载：

1. 把 `app-debug.apk` 拷到手机（微信文件传输 / USB / 网盘都行）
2. 打开 **卓易通** → 右下角「我的」→「本地安装」→ 选 APK
3. 首次启动给存储权限；导入模型时用系统文件选择器

App 内不联网，无需网络权限外的任何授权。

---

## 5. 模型（关键一步）

App 不带模型，首次启动需要从手机里导入模型文件。

### 5.0 分离式模型：Qwen-Image-2.1 要几个文件？

本工程支持两种模型形态，导入后**按文件名自动归组**：

**单文件**（SD-Turbo / SD1.5 / SDXL）—— 一个 `.gguf` 或 `.safetensors` 就够。

**分离式**（Qwen-Image-2.1 / FLUX）—— 需要 4 类文件，逐个导入即可：

| 角色 | 文件名特征 | 加载到 sdcpp 的 key |
|---|---|---|
| DiT 主干 | 含 `qwen-image` / `dit` / `flux` | `diffusion_model_path` |
| 文本编码器 | 含 `qwen3-vl` / `qwen*vl` 且非 vae | `llm_path` |
| 视觉投影层 | 含 `mmproj` / `vision_proj` | `llm_vision_path` |
| VAE | 含 `vae` | `vae_path` |

SDXL 分离件走 `clip_l_path` / `clip_g_path`；SD3.5 走 `t5xxl_path`。

### 5.1 下载

```bash
# 列出仓库里有哪些模型文件
python scripts/fetch-model.py --list 仓库ID --mirror

# 下载指定文件
python scripts/fetch-model.py --repo 仓库ID --file 文件名.gguf --out models --mirror
```

候选仓库（`--list` 探测，404 就换一个搜）：

| 目标 | 搜索关键词 | 期望体积 |
|---|---|---|
| SD-Turbo（**最推荐**，4 步出图） | `SD-Turbo GGUF` | 0.9–1.2 GB |
| SD 1.5（画质更稳，20 步） | `stable-diffusion-v1-5 GGUF` | 1.0–1.3 GB |
| SDXL-Turbo（慢一倍，1024 级） | `SDXL-Turbo GGUF` | 2.5–3.5 GB |

官方原始权重（safetensors，约 4 GB，也能直接用，但手机内存吃紧）：
- SD-Turbo → `https://huggingface.co/stabilityai/sd-turbo`
- SD1.5 → `https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5`

### 5.2 传进手机
```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -PushModel "D:\models\sd-turbo-q4_k.gguf"
```
推到 `/sdcard/Download/`，然后在 App 里「导入模型」选中它。

---

## 6. 参数怎么调

| 模型 | 步数 | CFG | 尺寸 |
|---|---|---|---|
| SD-Turbo / LCM | **4** | **1.0** | 512×512 |
| SD 1.5 | 20–25 | 7.0 | 512×512 / 512×768 |
| SDXL-Turbo | 4 | 1.0 | 768×768 起 |

App 会根据文件名里的 `turbo/lcm/lightning` 自动切默认值。提示词**用英文**——SD 系列只有 CLIP 文本编码器，中文识别不了。

### 6.1 内存策略（跑大模型必看）

界面上有三档，本质是三个 sdcpp 参数的不同组合：

| 档位 | `enable_mmap` | `params_backend` | `max_vram` | 适用 |
|---|---|---|---|---|
| 省内存 | true | `te=disk` | 4G | Qwen-Image / FLUX 这类十几 G 的模型，文本编码器参数卸载到磁盘 |
| 均衡 | true | — | — | SD1.5 / SD-Turbo 默认，推荐 |
| 最快 | false | — | — | 权重全载入，最快，大模型必 OOM |

**加载 OOM 时的顺序**：切「省内存」→ 降分辨率到 256 的倍数 → 减步数 → 换更小的量化（Q4_0 → Q3_K → Q2_K）。

---

## 7. 性能预期（预估，需真机实测）

| 场景 | 耗时 | 峰值内存 |
|---|---|---|
| SD-Turbo Q4_K / 512² / 4 步 | 1–3 分钟 | 2.5–3.5 GB |
| SD1.5 Q4_K / 512² / 20 步 | 4–8 分钟 | 3–4 GB |
| SDXL-Turbo Q4_K / 768² / 4 步 | 5–12 分钟（可能 OOM） | 5–7 GB |

麒麟 9020 纯 CPU + 兼容层损耗，不要期待秒出。这是「能离线跑」的代价，不是 bug。

---

## 8. 已知限制

1. **Qwen-Image-2.1 能加载，但很可能跑不动**：分离式多文件已经打通，16G 内存理论上是它的最低门槛（参考项目作者原话 "Devices with less than 16 GB of memory have not been validated"）。但那是骁龙 NPU + FP8 的成绩；纯 CPU 上大概率 OOM 或单张几十分钟。**可以试，别抱期待。**
2. **纯 CPU**，卓易通是 Android 兼容层，麒麟 NPU 走的是鸿蒙 CANN Kit，容器里访问不到。所有模型都慢。
3. **提示词仅英文有效**。
4. **未在本机编译验证**：开发机缺 JDK/SDK/NDK 且网络只有 ~200 KB/s（NDK 需 2 小时），APK 需你在自己电脑跑第 3 节命令产出。工程按 AGP 8.7.3 + Gradle 8.9 + Kotlin 2.0.21 + NDK 27 配置，与上游 API 已逐条核对（`sd_ctx_opt_create` / `sd_opt_set_*` / `sd_generate_image` / `sd_result_get_image` / `llm_path` / `llm_vision_path`）。
5. **上游 API 变动风险**：sdcpp 标注 "API may change frequently"。`setup.ps1` 会检查头文件，若哪天不匹配会明确报错，改 `sd_jni.cpp` 里对应调用即可。

---

## 9. 真想试 Qwen-Image-2.1 的话

架构已经把口子留好了，不用改代码：

1. 备齐 4 个文件（见 5.0）：DiT Q4_0 或 Q3_K、Qwen3-VL 文本编码器、mmproj 视觉投影、Qwen Image VAE
2. 全部导进 App —— 文件名对上就自动归组，模型卡片会显示「DiT · 文本编码器 · VAE」
3. 内存策略切**省内存**，尺寸用 256 的倍数（512×512 起），步数先给 4 步测通再往上加
4. 点「加载」，然后看 logcat 和耗时

判断标准很简单：
- 加载阶段就 OOM → 换更低量化（Q3_K / Q2_K），或放弃
- 能加载但单张 > 10 分钟 → 这台机器不适合跑它，回 SD1.5

**想真正用上 Qwen-Image-2.1 端侧，换一台骁龙 8 Elite 级别的机器，比改一万行代码都有效。**

