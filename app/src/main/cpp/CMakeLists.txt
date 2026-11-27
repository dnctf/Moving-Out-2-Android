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
set_target_properties(mo2_process_group_launcher PROPERTIES
    OUTPUT_NAME "mo2_process_group_launcher"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)
target_compile_options(mo2_process_group_launcher PRIVATE -Wall -Wextra -Werror)
target_link_options(mo2_process_group_launcher PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
