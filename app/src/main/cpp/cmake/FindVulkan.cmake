# Minimal FindVulkan for the Android NDK cross-compilation.
#
# The NDK ships Vulkan headers + libvulkan.so loader stubs, but no CMake package
# config file. Upstream ggml does:
#     find_package(Vulkan COMPONENTS glslc REQUIRED)
# so we have to answer that call ourselves, pointing at the NDK sysroot for the
# loader stub and at the NDK shader-tools directory for glslc.exe.

if (TARGET Vulkan::Vulkan)
    set(Vulkan_FOUND TRUE)
    return()
endif()

set(Vulkan_FOUND FALSE)

if (NOT Vulkan_INCLUDE_DIR)
    find_path(Vulkan_INCLUDE_DIR NAMES vulkan/vulkan_core.h)
endif()

# The loader stub is exported per API level and only contains the entry points
# that exist at that level. ggml needs Vulkan 1.1 (vkGetPhysicalDeviceFeatures2),
# which first appears in the android-28 stub, while the toolchain resolves
# `-lvulkan` to the api level of minSdk (26). So pick the stub by hand.
# minSdk stays 26 for the CPU build; the GPU build simply needs Android 9+.
set(Vulkan_LIBRARY "")
set(_qp_ndk_root "${CMAKE_ANDROID_NDK}")
if (NOT _qp_ndk_root)
    set(_qp_ndk_root "${ANDROID_NDK}")
endif()
if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(_qp_host_tag "windows-x86_64")
elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(_qp_host_tag "darwin-x86_64")
else()
    set(_qp_host_tag "linux-x86_64")
endif()
if (_qp_ndk_root AND ANDROID_ABI)
    # ANDROID_ABI is the "arm64-v8a" style name; the sysroot folders use the
    # triple style ("aarch64-linux-android").
    set(_qp_abi_dir "${ANDROID_ABI}")
    if (ANDROID_TOOLCHAIN_MACHINE)
        set(_qp_abi_dir "${ANDROID_TOOLCHAIN_MACHINE}")
    else()
        if (ANDROID_ABI STREQUAL "arm64-v8a")
            set(_qp_abi_dir "aarch64-linux-android")
        elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
            set(_qp_abi_dir "arm-linux-androideabi")
        elseif (ANDROID_ABI STREQUAL "x86")
            set(_qp_abi_dir "i686-linux-android")
        elseif (ANDROID_ABI STREQUAL "x86_64")
            set(_qp_abi_dir "x86_64-linux-android")
        endif()
    endif()
    set(_qp_vk_stub_dir "${_qp_ndk_root}/toolchains/llvm/prebuilt/${_qp_host_tag}/sysroot/usr/lib/${_qp_abi_dir}")
    foreach(_qp_lvl 28 29 30 31 32 33)
        if (EXISTS "${_qp_vk_stub_dir}/${_qp_lvl}/libvulkan.so")
            set(Vulkan_LIBRARY "${_qp_vk_stub_dir}/${_qp_lvl}/libvulkan.so")
            break()
        endif()
    endforeach()
endif()
if (NOT Vulkan_LIBRARY)
    find_library(Vulkan_LIBRARY NAMES vulkan)
endif()

# glslc is a *host* tool, so it must never be searched inside the sysroot.
if (NOT Vulkan_GLSLC_EXECUTABLE)
    set(_qp_suffix "")
    if (CMAKE_HOST_WIN32)
        set(_qp_suffix ".exe")
    endif()
    set(_qp_glslc_hints "")
    if (DEFINED ENV{VULKAN_SDK})
        list(APPEND _qp_glslc_hints "$ENV{VULKAN_SDK}/Bin")
    endif()
    if (DEFINED QWENPAINT_NDK_ROOT AND EXISTS "${QWENPAINT_NDK_ROOT}/shader-tools")
        if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
            list(APPEND _qp_glslc_hints "${QWENPAINT_NDK_ROOT}/shader-tools/windows-x86_64")
        elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
            list(APPEND _qp_glslc_hints "${QWENPAINT_NDK_ROOT}/shader-tools/darwin-x86_64")
        else()
            list(APPEND _qp_glslc_hints "${QWENPAINT_NDK_ROOT}/shader-tools/linux-x86_64")
        endif()
    endif()
    find_program(Vulkan_GLSLC_EXECUTABLE
                 NAMES glslc
                 HINTS ${_qp_glslc_hints}
                 NO_CMAKE_FIND_ROOT_PATH
                 NO_SYSTEM_ENVIRONMENT_PATH)
endif()

if (Vulkan_INCLUDE_DIR AND Vulkan_LIBRARY)
    set(Vulkan_FOUND TRUE)

    # The NDK only ships the C headers; ggml-vulkan also needs Vulkan-Hpp
    # (vulkan/vulkan.hpp), which we vendor next to this module.
    set(_qp_vk_extra_include "")
    get_filename_component(_qp_vk_hpp_root
        "${CMAKE_CURRENT_LIST_DIR}/../third_party/vulkan" ABSOLUTE)
    if (EXISTS "${_qp_vk_hpp_root}/vulkan/vulkan.hpp")
        set(_qp_vk_extra_include "${_qp_vk_hpp_root}")
    endif()

    add_library(Vulkan::Vulkan UNKNOWN IMPORTED)
    set_target_properties(Vulkan::Vulkan PROPERTIES
        IMPORTED_LOCATION "${Vulkan_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${Vulkan_INCLUDE_DIR};${_qp_vk_extra_include}")

    # ggml links Vulkan::Vulkan; also expose the plain variable-based interface
    # used by older consumers.
    set(Vulkan_LIBRARIES "${Vulkan_LIBRARY}")
    set(Vulkan_INCLUDE_DIRS "${Vulkan_INCLUDE_DIR};${_qp_vk_extra_include}")
endif()

if (NOT Vulkan_GLSLC_EXECUTABLE)
    message(WARNING "FindVulkan: glslc not found - shader generation will fail")
endif()

message(STATUS "FindVulkan (android shim): lib=${Vulkan_LIBRARY} inc=${Vulkan_INCLUDE_DIR} glslc=${Vulkan_GLSLC_EXECUTABLE}")
