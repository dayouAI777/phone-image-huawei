plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// -Pqwenpaint.vulkan=true 编出带 ggml Vulkan 后端的 so（Maleoon GPU 路径）
val useVulkan: Boolean = (project.findProperty("qwenpaint.vulkan") as String?)?.toBoolean() ?: false
val versionCodeValue: Int = (project.findProperty("qwenpaint.versionCode") as String?)?.toInt() ?: 5
val versionNameValue: String = (project.findProperty("qwenpaint.versionName") as String?) ?: "1.3.0"

android {
    namespace = "com.qwenpaint"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.qwenpaint"
        minSdk = 26
        targetSdk = 34
        versionCode = versionCodeValue
        versionName = versionNameValue

        ndk {
            // 卓易通容器与真机均为 arm64，只出 arm64-v8a 减小体积
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // -O3 + NEON；关闭 OpenMP，NDK 无 libomp
                cppFlags += listOf("-O3", "-fexceptions", "-frtti", "-DNDEBUG")
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DQWENPAINT_VULKAN=$useVulkan"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        // 固定签名：所有机器/所有版本共用，保证覆盖安装不触发卸载（模型数据得以保留）
        create("app") {
            storeFile = rootProject.file("keystore/qwenpaint.jks")
            storePassword = "qwenpaint2026"
            keyAlias = "qwenpaint"
            keyPassword = "qwenpaint2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("app")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            // so 解压到磁盘而非内存加载，兼容层下更稳
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
