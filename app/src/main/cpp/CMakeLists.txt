cmake_minimum_required(VERSION 3.22.1)
project(vulkan_probe LANGUAGES CXX)

find_library(VULKAN_LIBRARY vulkan REQUIRED)
find_library(LOG_LIBRARY log REQUIRED)
find_library(ANDROID_LIBRARY android REQUIRED)

# Android 15+ devices may use 16 KB memory pages. Align every LOAD segment.
add_link_options(-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)

add_library(vulkan_probe SHARED vulkan_probe.cpp)
target_compile_features(vulkan_probe PRIVATE cxx_std_20)
target_compile_options(vulkan_probe PRIVATE -Wall -Wextra -Werror)
target_link_libraries(vulkan_probe PRIVATE ${VULKAN_LIBRARY} ${LOG_LIBRARY} ${ANDROID_LIBRARY})

add_library(mo2_input_bridge SHARED xinput_bridge_jni.cpp)
target_compile_features(mo2_input_bridge PRIVATE cxx_std_20)
target_compile_options(mo2_input_bridge PRIVATE -Wall -Wextra -Werror)
target_link_libraries(mo2_input_bridge PRIVATE ${LOG_LIBRARY})


# Standalone ARM64 helper: sets the process group in its own process before
# execv() replaces it with Box64. Gradle stages this executable into jniLibs
# so Android extracts it under nativeLibraryDir with executable permissions.
add_executable(mo2_process_group_launcher process_group_launcher.cpp)
# Reconcile naming: CMake target `mo2_process_group_launcher`, output file
# `libmo2_process_group.so`, and Kotlin runtime lookup `nativeLibraryDir + libmo2_process_group.so`
# all use the same artifact name; CI checks `lib/arm64-v8a/libmo2_process_group.so`.
set_target_properties(mo2_process_group_launcher PROPERTIES
    OUTPUT_NAME "mo2_process_group"
    PREFIX "lib"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
    POSITION_INDEPENDENT_CODE ON
)
# Standalone PIE executable: -fPIE at compile time, -pie at link time; not loaded
# with System.loadLibrary but exec'd directly by ProcessBuilder.
target_compile_options(mo2_process_group_launcher PRIVATE -Wall -Wextra -Werror -fPIE)
target_link_options(mo2_process_group_launcher PRIVATE
    -pie
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
