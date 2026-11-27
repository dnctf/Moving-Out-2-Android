#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <algorithm>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>
#include <mutex>
#include <unistd.h>
#include <signal.h>
#include <cerrno>

static std::string clean(const char* value) {
    std::string out = value ? value : "";
    for (char& c : out) if (c == ';' || c == ',' || c == '\n' || c == '\r') c = '_';
    return out;
}
static std::string version(uint32_t v) {
    std::ostringstream s; s << VK_VERSION_MAJOR(v) << "." << VK_VERSION_MINOR(v) << "." << VK_VERSION_PATCH(v); return s.str();
}
static std::string join(const std::vector<std::string>& values) {
    std::ostringstream s; for (size_t i=0;i<values.size();i++){ if(i) s << ","; s << values[i]; } return s.str();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dnctf_movingout2_runtime_NativeProcessGroup_signalGroupNative(
    JNIEnv*, jobject, jlong processGroupId, jint signal) {
    return kill(-static_cast<pid_t>(processGroupId), signal) == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dnctf_movingout2_runtime_NativeProcessGroup_isGroupAliveNative(
    JNIEnv*, jobject, jlong processGroupId) {
    if (kill(-static_cast<pid_t>(processGroupId), 0) == 0) return JNI_TRUE;
    return errno == ESRCH ? JNI_FALSE : JNI_TRUE;
}

// App-process Surface lease. This owns an ANativeWindow reference only inside this process;
// it is deliberately not described as a DXVK WSI bridge (Box64 is a separate process).
static std::mutex g_sessionWindowMutex;
static ANativeWindow* g_sessionWindow = nullptr;
static int g_sessionWidth = 0;
static int g_sessionHeight = 0;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dnctf_movingout2_graphics_VulkanNativeProbe_attachSessionSurfaceNative(
    JNIEnv* env, jobject, jobject surface, jint width, jint height) {
    if (!surface || width <= 0 || height <= 0) return JNI_FALSE;
    ANativeWindow* next = ANativeWindow_fromSurface(env, surface);
    if (!next) return JNI_FALSE;
    if (ANativeWindow_setBuffersGeometry(next, width, height, 0) != 0) {
        ANativeWindow_release(next);
        return JNI_FALSE;
    }
    ANativeWindow* previous = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_sessionWindowMutex);
        previous = g_sessionWindow;
        g_sessionWindow = next;
        g_sessionWidth = width;
        g_sessionHeight = height;
    }
    if (previous) ANativeWindow_release(previous);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dnctf_movingout2_graphics_VulkanNativeProbe_resizeSessionSurfaceNative(
    JNIEnv* env, jobject, jobject surface, jint width, jint height) {
    if (!surface || width <= 0 || height <= 0) return JNI_FALSE;
    ANativeWindow* current = ANativeWindow_fromSurface(env, surface);
    if (!current) return JNI_FALSE;
    const int result = ANativeWindow_setBuffersGeometry(current, width, height, 0);
    ANativeWindow_release(current);
    if (result != 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_sessionWindowMutex);
    if (!g_sessionWindow) return JNI_FALSE;
    g_sessionWidth = width;
    g_sessionHeight = height;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dnctf_movingout2_graphics_VulkanNativeProbe_detachSessionSurfaceNative(JNIEnv*, jobject) {
    ANativeWindow* previous = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_sessionWindowMutex);
        previous = g_sessionWindow;
        g_sessionWindow = nullptr;
        g_sessionWidth = 0;
        g_sessionHeight = 0;
    }
    if (previous) ANativeWindow_release(previous);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dnctf_movingout2_graphics_VulkanNativeProbe_probeNative(JNIEnv* env, jobject) {
    uint32_t loaderVersion = VK_API_VERSION_1_0;
    auto enumerateVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    VkResult versionResult = enumerateVersion ? enumerateVersion(&loaderVersion) : VK_ERROR_EXTENSION_NOT_PRESENT;
    VkApplicationInfo app{};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "Moving Out 2 Android Diagnostics";
    app.applicationVersion = 1;
    app.pEngineName = "Diagnostics";
    app.engineVersion = 1;
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo create{};
    create.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    VkResult created = vkCreateInstance(&create, nullptr, &instance);
    std::ostringstream out;
    if (versionResult != VK_SUCCESS) loaderVersion = VK_API_VERSION_1_0;
    out << "loaderApi=" << version(loaderVersion) << ";";
    if (created != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        out << "status=instance_create_failed;vkResult=" << static_cast<int>(created);
        return env->NewStringUTF(out.str().c_str());
    }
    uint32_t count = 0;
    VkResult enumeration = vkEnumeratePhysicalDevices(instance, &count, nullptr);
    if (enumeration != VK_SUCCESS || count == 0) {
        out << "status=no_physical_device;vkResult=" << static_cast<int>(enumeration);
        vkDestroyInstance(instance, nullptr);
        return env->NewStringUTF(out.str().c_str());
    }
    std::vector<VkPhysicalDevice> devices(count);
    enumeration = vkEnumeratePhysicalDevices(instance, &count, devices.data());
    if (enumeration != VK_SUCCESS || count == 0) {
        out << "status=device_enumeration_failed;vkResult=" << static_cast<int>(enumeration);
        vkDestroyInstance(instance, nullptr);
        return env->NewStringUTF(out.str().c_str());
    }
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(devices[0], &props);
    uint32_t extCount = 0;
    vkEnumerateDeviceExtensionProperties(devices[0], nullptr, &extCount, nullptr);
    std::vector<VkExtensionProperties> ext(extCount);
    if (extCount) vkEnumerateDeviceExtensionProperties(devices[0], nullptr, &extCount, ext.data());
    std::vector<std::string> names;
    names.reserve(extCount);
    for (const auto& e : ext) names.emplace_back(e.extensionName);
    out << "status=ok;device=" << clean(props.deviceName)
        << ";vendorId=" << props.vendorID << ";deviceId=" << props.deviceID
        << ";deviceApi=" << version(props.apiVersion)
        << ";driverVersionRaw=0x" << std::hex << props.driverVersion << std::dec
        << ";deviceType=" << static_cast<int>(props.deviceType)
        << ";deviceExtensions=" << join(names);
    vkDestroyInstance(instance, nullptr);
    return env->NewStringUTF(out.str().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_dnctf_movingout2_graphics_VulkanNativeProbe_probeSwapchainNative(JNIEnv* env, jobject, jobject surface) {
    if (!surface) {
        return env->NewStringUTF("status=error;detail=surface_null");
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        return env->NewStringUTF("status=error;detail=native_window_null");
    }
    const int windowWidth = ANativeWindow_getWidth(window);
    const int windowHeight = ANativeWindow_getHeight(window);

    // 1. Enumerate and verify instance extensions
    uint32_t instExtCount = 0;
    vkEnumerateInstanceExtensionProperties(nullptr, &instExtCount, nullptr);
    std::vector<VkExtensionProperties> availableInstExts(instExtCount);
    if (instExtCount > 0) {
        vkEnumerateInstanceExtensionProperties(nullptr, &instExtCount, availableInstExts.data());
    }

    bool hasSurfaceExt = false;
    bool hasAndroidSurfaceExt = false;
    for (const auto& e : availableInstExts) {
        if (std::strcmp(e.extensionName, VK_KHR_SURFACE_EXTENSION_NAME) == 0) hasSurfaceExt = true;
        if (std::strcmp(e.extensionName, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME) == 0) hasAndroidSurfaceExt = true;
    }
    if (!hasSurfaceExt || !hasAndroidSurfaceExt) {
        ANativeWindow_release(window);
        std::ostringstream err;
        err << "status=error;detail=missing_instance_extensions;surface=" << hasSurfaceExt << ";android_surface=" << hasAndroidSurfaceExt;
        return env->NewStringUTF(err.str().c_str());
    }

    const char* enabledInstExts[] = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
    };

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "Moving Out 2 Swapchain Probe";
    appInfo.applicationVersion = 1;
    appInfo.pEngineName = "SwapchainProbe";
    appInfo.engineVersion = 1;
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo instCreate{};
    instCreate.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instCreate.pApplicationInfo = &appInfo;
    instCreate.enabledExtensionCount = 2;
    instCreate.ppEnabledExtensionNames = enabledInstExts;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult res = vkCreateInstance(&instCreate, nullptr, &instance);
    if (res != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        ANativeWindow_release(window);
        std::ostringstream err;
        err << "status=error;detail=instance_create_failed;vkResult=" << static_cast<int>(res);
        return env->NewStringUTF(err.str().c_str());
    }

    // 2. Load Surface function pointers
    auto vkCreateAndroidSurfaceKHR = reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(
        vkGetInstanceProcAddr(instance, "vkCreateAndroidSurfaceKHR"));
    auto vkDestroySurfaceKHR = reinterpret_cast<PFN_vkDestroySurfaceKHR>(
        vkGetInstanceProcAddr(instance, "vkDestroySurfaceKHR"));
    auto vkGetPhysicalDeviceSurfaceSupportKHR = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceSupportKHR>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceSurfaceSupportKHR"));
    auto vkGetPhysicalDeviceSurfaceCapabilitiesKHR = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"));
    auto vkGetPhysicalDeviceSurfaceFormatsKHR = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceSurfaceFormatsKHR"));
    auto vkGetPhysicalDeviceSurfacePresentModesKHR = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfacePresentModesKHR>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceSurfacePresentModesKHR"));

    if (!vkCreateAndroidSurfaceKHR || !vkDestroySurfaceKHR || !vkGetPhysicalDeviceSurfaceSupportKHR ||
        !vkGetPhysicalDeviceSurfaceCapabilitiesKHR || !vkGetPhysicalDeviceSurfaceFormatsKHR ||
        !vkGetPhysicalDeviceSurfacePresentModesKHR) {
        vkDestroyInstance(instance, nullptr);
        ANativeWindow_release(window);
        return env->NewStringUTF("status=error;detail=missing_surface_function_pointers");
    }

    // 3. Create Android Surface
    VkAndroidSurfaceCreateInfoKHR surfaceCreate{};
    surfaceCreate.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceCreate.window = window;

    VkSurfaceKHR surfaceKHR = VK_NULL_HANDLE;
    res = vkCreateAndroidSurfaceKHR(instance, &surfaceCreate, nullptr, &surfaceKHR);
    if (res != VK_SUCCESS || surfaceKHR == VK_NULL_HANDLE) {
        vkDestroyInstance(instance, nullptr);
        ANativeWindow_release(window);
        std::ostringstream err;
        err << "status=error;detail=surface_create_failed;vkResult=" << static_cast<int>(res);
        return env->NewStringUTF(err.str().c_str());
    }

    // 4. Select physical device and check queue family support
    uint32_t devCount = 0;
    vkEnumeratePhysicalDevices(instance, &devCount, nullptr);
    if (devCount == 0) {
        vkDestroySurfaceKHR(instance, surfaceKHR, nullptr);
        vkDestroyInstance(instance, nullptr);
        ANativeWindow_release(window);
        return env->NewStringUTF("status=error;detail=no_physical_device");
    }
    std::vector<VkPhysicalDevice> physicalDevices(devCount);
    vkEnumeratePhysicalDevices(instance, &devCount, physicalDevices.data());

    VkPhysicalDevice chosenPhysDev = physicalDevices[0];
    uint32_t chosenQueueFamily = UINT32_MAX;

    uint32_t qfCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(chosenPhysDev, &qfCount, nullptr);
    std::vector<VkQueueFamilyProperties> qfProps(qfCount);
    vkGetPhysicalDeviceQueueFamilyProperties(chosenPhysDev, &qfCount, qfProps.data());

    for (uint32_t i = 0; i < qfCount; ++i) {
        VkBool32 presentSupport = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(chosenPhysDev, i, surfaceKHR, &presentSupport);
        if ((qfProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && presentSupport == VK_TRUE) {
            chosenQueueFamily = i;
            break;
        }
    }
    if (chosenQueueFamily == UINT32_MAX) {
        vkDestroySurfaceKHR(instance, surfaceKHR, nullptr);
        vkDestroyInstance(instance, nullptr);
        ANativeWindow_release(window);
        return env->NewStringUTF("status=error;detail=no_graphics_present_queue_family");
    }

    // 5. Verify swapchain device extension
    uint32_t devExtCount = 0;
    vkEnumerateDeviceExtensionProperties(chosenPhysDev, nullptr, &devExtCount, nullptr);
    std::vector<VkExtensionProperties> devExts(devExtCount);
    if (devExtCount > 0) {
        vkEnumerateDeviceExtensionProperties(chosenPhysDev, nullptr, &devExtCount, devExts.data());
    }
    bool hasSwapchainExt = false;
    for (const auto& e : devExts) {
        if (std::strcmp(e.extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME) == 0) {
            hasSwapchainExt = true;
            break;
        }
    }
    if (!hasSwapchainExt) {
        vkDestroySurfaceKHR(instance, surfaceKHR, nullptr);
        vkDestroyInstance(instance, nullptr);
        ANativeWindow_release(window);
        return env->NewStringUTF("status=error;detail=device_missing_swapchain_extension");
    }

    // 6. Create VkDevice
    float queuePriority = 1.0f;
    VkDeviceQueueCreateInfo queueCreate{};
    queueCreate.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCreate.queueFamilyIndex = chosenQueueFamily;
    queueCreate.queueCount = 1;
    queueCreate.pQueuePriorities = &queuePriority;

    const char* enabledDevExts[] = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };
    VkDeviceCreateInfo devCreate{};
    devCreate.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    devCreate.queueCreateInfoCount = 1;
    devCreate.pQueueCreateInfos = &queueCreate;
    devCreate.enabledExtensionCount = 1;
    devCreate.ppEnabledExtensionNames = enabledDevExts;

    VkDevice device = VK_NULL_HANDLE;
    res = vkCreateDevice(chosenPhysDev, &devCreate, nullptr, &device);
    if (res != VK_SUCCESS || device == VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(instance, surfaceKHR, nullptr);
        vkDestroyInstance(instance, nullptr);
        ANativeWindow_release(window);
        std::ostringstream err;
        err << "status=error;detail=device_create_failed;vkResult=" << static_cast<int>(res);
        return env->NewStringUTF(err.str().c_str());
    }

    VkQueue queue = VK_NULL_HANDLE;
    vkGetDeviceQueue(device, chosenQueueFamily, 0, &queue);

    // 7. Surface capabilities, formats, and extent
    VkSurfaceCapabilitiesKHR caps{};
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(chosenPhysDev, surfaceKHR, &caps);

    uint32_t formatCount = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(chosenPhysDev, surfaceKHR, &formatCount, nullptr);
    if (formatCount == 0) {
        vkDestroyDevice(device, nullptr);
        vkDestroySurfaceKHR(instance, surfaceKHR, nullptr);
        vkDestroyInstance(instance, nullptr);
        ANativeWindow_release(window);
        return env->NewStringUTF("status=error;detail=no_surface_formats");
    }
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    vkGetPhysicalDeviceSurfaceFormatsKHR(chosenPhysDev, surfaceKHR, &formatCount, formats.data());

    VkSurfaceFormatKHR chosenFormat = formats[0];
    for (const auto& f : formats) {
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
            chosenFormat = f;
            break;
        }
    }

    VkExtent2D extent;
    if (caps.currentExtent.width != 0xFFFFFFFF) {
        extent = caps.currentExtent;
    } else {
        extent.width = std::max(caps.minImageExtent.width, std::min(caps.maxImageExtent.width, static_cast<uint32_t>(windowWidth > 0 ? windowWidth : 1280)));
        extent.height = std::max(caps.minImageExtent.height, std::min(caps.maxImageExtent.height, static_cast<uint32_t>(windowHeight > 0 ? windowHeight : 720)));
    }

    uint32_t minImageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && minImageCount > caps.maxImageCount) {
        minImageCount = caps.maxImageCount;
    }

    VkCompositeAlphaFlagBitsKHR compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR) {
        compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    } else if (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR) {
        compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    }

    // 8. Create Swapchain
    auto vkCreateSwapchainKHR = reinterpret_cast<PFN_vkCreateSwapchainKHR>(
        vkGetDeviceProcAddr(device, "vkCreateSwapchainKHR"));
    auto vkDestroySwapchainKHR = reinterpret_cast<PFN_vkDestroySwapchainKHR>(
        vkGetDeviceProcAddr(device, "vkDestroySwapchainKHR"));
    auto vkGetSwapchainImagesKHR = reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(
        vkGetDeviceProcAddr(device, "vkGetSwapchainImagesKHR"));
    auto vkAcquireNextImageKHR = reinterpret_cast<PFN_vkAcquireNextImageKHR>(
        vkGetDeviceProcAddr(device, "vkAcquireNextImageKHR"));
    auto vkQueuePresentKHR = reinterpret_cast<PFN_vkQueuePresentKHR>(
        vkGetDeviceProcAddr(device, "vkQueuePresentKHR"));

    if (!vkCreateSwapchainKHR || !vkDestroySwapchainKHR || !vkGetSwapchainImagesKHR ||
        !vkAcquireNextImageKHR || !vkQueuePresentKHR) {
        vkDestroyDevice(device, nullptr);
        vkDestroySurfaceKHR(instance, surfaceKHR, nullptr);
        vkDestroyInstance(instance, nullptr);
        ANativeWindow_release(window);
        return env->NewStringUTF("status=error;detail=missing_device_proc_addr");
    }

    VkSwapchainCreateInfoKHR swapCreate{};
    swapCreate.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    swapCreate.surface = surfaceKHR;
    swapCreate.minImageCount = minImageCount;
    swapCreate.imageFormat = chosenFormat.format;
    swapCreate.imageColorSpace = chosenFormat.colorSpace;
    swapCreate.imageExtent = extent;
    swapCreate.imageArrayLayers = 1;
    swapCreate.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    swapCreate.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    swapCreate.preTransform = caps.currentTransform;
    swapCreate.compositeAlpha = compositeAlpha;
    swapCreate.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    swapCreate.clipped = VK_TRUE;

    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    res = vkCreateSwapchainKHR(device, &swapCreate, nullptr, &swapchain);
    if (res != VK_SUCCESS || swapchain == VK_NULL_HANDLE) {
        vkDestroyDevice(device, nullptr);
        vkDestroySurfaceKHR(instance, surfaceKHR, nullptr);
        vkDestroyInstance(instance, nullptr);
        ANativeWindow_release(window);
        std::ostringstream err;
        err << "status=error;detail=swapchain_create_failed;vkResult=" << static_cast<int>(res);
        return env->NewStringUTF(err.str().c_str());
    }

    uint32_t actualImageCount = 0;
    vkGetSwapchainImagesKHR(device, swapchain, &actualImageCount, nullptr);

    // 9. Acquire and Present test frame
    VkSemaphore semaphore = VK_NULL_HANDLE;
    VkSemaphoreCreateInfo semInfo{};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    vkCreateSemaphore(device, &semInfo, nullptr, &semaphore);

    uint32_t imageIndex = 0;
    res = vkAcquireNextImageKHR(device, swapchain, 2000000000ULL /* 2s */, semaphore, VK_NULL_HANDLE, &imageIndex);
    const bool acquireSuccess = (res == VK_SUCCESS || res == VK_SUBOPTIMAL_KHR);

    bool presentSuccess = false;
    if (acquireSuccess) {
        VkPresentInfoKHR presentInfo{};
        presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        presentInfo.waitSemaphoreCount = (semaphore != VK_NULL_HANDLE) ? 1 : 0;
        presentInfo.pWaitSemaphores = &semaphore;
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &swapchain;
        presentInfo.pImageIndices = &imageIndex;
        res = vkQueuePresentKHR(queue, &presentInfo);
        presentSuccess = (res == VK_SUCCESS || res == VK_SUBOPTIMAL_KHR);
    }

    // 10. Clean up resources in reverse order
    if (semaphore != VK_NULL_HANDLE) {
        vkDestroySemaphore(device, semaphore, nullptr);
    }
    vkDestroySwapchainKHR(device, swapchain, nullptr);
    vkDestroyDevice(device, nullptr);
    vkDestroySurfaceKHR(instance, surfaceKHR, nullptr);
    vkDestroyInstance(instance, nullptr);
    ANativeWindow_release(window);

    std::ostringstream out;
    out << "status=ok;swapchain=created;images=" << actualImageCount
        << ";width=" << extent.width << ";height=" << extent.height
        << ";format=" << static_cast<int>(chosenFormat.format)
        << ";colorSpace=" << static_cast<int>(chosenFormat.colorSpace)
        << ";acquire=" << (acquireSuccess ? "success" : "failed")
        << ";present=" << (presentSuccess ? "success" : "failed");
    return env->NewStringUTF(out.str().c_str());
}
