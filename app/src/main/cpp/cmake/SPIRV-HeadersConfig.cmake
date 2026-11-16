# Stub package config.
#
# Upstream ggml asks for SPIRV-Headers in CONFIG mode just to make sure the SPIR-V
# grammar headers are on disk; vulkan-shaders-gen does not actually link against
# them (glslc embeds everything it needs). The NDK ships no CMake config for it,
# so provide an empty one to keep `find_package(... REQUIRED)` happy.
set(SPIRV-Headers_FOUND TRUE)
set(SPIRV_HEADERS_FOUND TRUE)
