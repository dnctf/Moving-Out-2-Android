#include <jni.h>
#include <vulkan/vulkan.h>
#include <sstream>
#include <string>
#include <vector>

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
        << ";deviceType=" << static_cast<int>(props.deviceType)
        << ";deviceExtensions=" << join(names);
    vkDestroyInstance(instance, nullptr);
    return env->NewStringUTF(out.str().c_str());
}
