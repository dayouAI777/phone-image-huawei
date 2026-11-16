cmake_minimum_required(VERSION 3.22)
project(qwenpaint C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Vendored stable-diffusion.cpp tree (contains its ggml submodule).
set(SD_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/sdcpp/stable-diffusion.cpp-master")
if(NOT EXISTS "${SD_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "stable-diffusion.cpp sources missing at ${SD_SRC}. Run scripts/setup.ps1 first.")
endif()

# ---- CPU-only build: no CUDA/OpenCL/Vulkan/Metal on Android ----
set(SD_BUILD_EXAMPLES       OFF CACHE BOOL "" FORCE)
set(SD_BUILD_TESTS          OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(SD_WEBP                 OFF CACHE BOOL "" FORCE)
set(SD_WEBM                 OFF CACHE BOOL "" FORCE)
set(SD_CUDA                 OFF CACHE BOOL "" FORCE)
set(SD_VULKAN               OFF CACHE BOOL "" FORCE)
set(SD_OPENCL               OFF CACHE BOOL "" FORCE)
set(SD_METAL                OFF CACHE BOOL "" FORCE)

# ---- ggml: portable NEON path, no host-CPU probing, no OpenMP ----
set(GGML_NATIVE             OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP             OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE          OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS   OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE             OFF CACHE BOOL "" FORCE)

add_subdirectory("${SD_SRC}" "${CMAKE_CURRENT_BINARY_DIR}/sdcpp")

add_library(qwenpaint SHARED sd_jni.cpp)
target_include_directories(qwenpaint PRIVATE "${SD_SRC}/include")
target_link_libraries(qwenpaint PRIVATE stable-diffusion)

# Android needs liblog for __android_log_print
find_library(ANDROID_LOG_LIB log)
if(ANDROID_LOG_LIB)
    target_link_libraries(qwenpaint PRIVATE ${ANDROID_LOG_LIB})
endif()

target_link_libraries(qwenpaint PRIVATE android)

# Keep only arm64 NEON kernels; strip debug info to keep the APK small.
target_compile_options(qwenpaint PRIVATE -O3 -fno-finite-math-only)
set_target_properties(qwenpaint PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON)
