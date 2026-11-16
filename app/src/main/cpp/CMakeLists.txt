cmake_minimum_required(VERSION 3.22)
project(qwenpaint C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Vendored stable-diffusion.cpp tree (contains its ggml submodule).
set(SD_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools/sdcpp/stable-diffusion.cpp-master")
if(NOT EXISTS "${SD_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "stable-diffusion.cpp sources missing at ${SD_SRC}. Run scripts/setup.ps1 first.")
endif()

# ---- Backend selection -------------------------------------------------------
# QWENPAINT_VULKAN=ON compiles the ggml Vulkan backend into the same .so as the
# CPU backend; the engine then picks a device at runtime ("vulkan" vs "cpu").
option(QWENPAINT_VULKAN "Build the ggml Vulkan GPU backend for Maleoon GPUs" OFF)

set(SD_BUILD_EXAMPLES       OFF CACHE BOOL "" FORCE)
set(SD_BUILD_TESTS          OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(SD_WEBP                 OFF CACHE BOOL "" FORCE)
set(SD_WEBM                 OFF CACHE BOOL "" FORCE)
set(SD_CUDA                 OFF CACHE BOOL "" FORCE)
set(SD_OPENCL               OFF CACHE BOOL "" FORCE)
set(SD_METAL                OFF CACHE BOOL "" FORCE)
set(SD_VULKAN              "${QWENPAINT_VULKAN}" CACHE BOOL "" FORCE)

# ---- ggml: portable NEON path, no host-CPU probing, no OpenMP ----
set(GGML_NATIVE             OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP             OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE          OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS   OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE             OFF CACHE BOOL "" FORCE)

if(QWENPAINT_VULKAN)
    # --- Vulkan plumbing for the NDK -----------------------------------------
    # 1. ggml calls find_package(Vulkan COMPONENTS glslc REQUIRED) but the NDK has
    #    no CMake package config, so ours answers it (sysroot stub + host glslc).
    list(INSERT CMAKE_MODULE_PATH 0 "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
    set(QWENPAINT_NDK_ROOT "${CMAKE_ANDROID_NDK}")
    # 2. SPIRV-Headers is requested in CONFIG mode; the stub keeps it satisfied.
    #    The Android toolchain restricts package lookup to the sysroot, so point
    #    <Pkg>_DIR straight at our stub instead of using CMAKE_PREFIX_PATH.
    set(SPIRV-Headers_DIR "${CMAKE_CURRENT_SOURCE_DIR}/cmake" CACHE PATH "" FORCE)

    # 3. shader generation cross-compiles a host tool, so a host C++ compiler has
    #    to be supplied explicitly (the Android toolchain would otherwise be used).
    set(QWENPAINT_HOST_CC  "" CACHE FILEPATH "Host C compiler for vulkan-shaders-gen")
    set(QWENPAINT_HOST_CXX "" CACHE FILEPATH "Host C++ compiler for vulkan-shaders-gen")
    if(NOT QWENPAINT_HOST_CXX)
        get_filename_component(_qp_tools "${CMAKE_CURRENT_SOURCE_DIR}/../../../../tools" ABSOLUTE)
        # Prefer the msvcrt-flavoured toolchain: its output runs standalone, while
        # the UCRT build needs api-ms-win-crt-*.dll which is missing on some hosts.
        foreach(_qp_cand "${_qp_tools}/mingw-msvcrt/mingw64/bin" "${_qp_tools}/mingw/mingw64/bin")
            if(EXISTS "${_qp_cand}/g++.exe")
                set(QWENPAINT_HOST_CC  "${_qp_cand}/gcc.exe")
                set(QWENPAINT_HOST_CXX "${_qp_cand}/g++.exe")
                break()
            endif()
        endforeach()
    endif()
    if(NOT QWENPAINT_HOST_CXX OR NOT EXISTS "${QWENPAINT_HOST_CXX}")
        message(FATAL_ERROR
            "QWENPAINT_VULKAN requires a host C++ compiler to build vulkan-shaders-gen. "
            "Run scripts/setup-vulkan.ps1 (installs MinGW-w64 into tools/mingw) or pass "
            "-DQWENPAINT_HOST_CXX=<path to g++.exe>.")
    endif()
    set(QWENPAINT_HOST_NINJA "${CMAKE_MAKE_PROGRAM}")
    if(NOT QWENPAINT_HOST_NINJA OR NOT EXISTS "${QWENPAINT_HOST_NINJA}")
        find_program(QWENPAINT_HOST_NINJA NAMES ninja ninja.exe)
    endif()
    # Backslashes are escape sequences inside a CMake string literal, so every
    # path baked into the generated toolchain file must be normalised.
    foreach(_qp_p QWENPAINT_HOST_CC QWENPAINT_HOST_CXX QWENPAINT_HOST_NINJA)
        if(${_qp_p})
            file(TO_CMAKE_PATH "${${_qp_p}}" ${_qp_p})
        endif()
    endforeach()
    configure_file("${CMAKE_CURRENT_SOURCE_DIR}/cmake/host-toolchain.cmake.in"
                   "${CMAKE_BINARY_DIR}/qp-host-toolchain.cmake" @ONLY)
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${CMAKE_BINARY_DIR}/qp-host-toolchain.cmake"
        CACHE FILEPATH "" FORCE)

    # Vulkan 1.1+ is required by ggml; Android 10+ (API 29) guarantees it, and the
    # Maleoon 920 on Mate 70 reports Vulkan 1.3.
    message(STATUS "qwenpaint: Vulkan backend ENABLED (host cxx=${QWENPAINT_HOST_CXX})")
else()
    message(STATUS "qwenpaint: CPU-only backend")
endif()

add_subdirectory("${SD_SRC}" "${CMAKE_CURRENT_BINARY_DIR}/sdcpp")

add_library(qwenpaint SHARED sd_jni.cpp)
target_include_directories(qwenpaint PRIVATE "${SD_SRC}/include")
target_link_libraries(qwenpaint PRIVATE stable-diffusion)

if(QWENPAINT_VULKAN)
    # libvulkan.so is loaded through the NDK sysroot stub; keep the reference so
    # the loader resolves it at process start.
    if(TARGET Vulkan::Vulkan)
        target_link_libraries(qwenpaint PRIVATE Vulkan::Vulkan)
    endif()
endif()

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
