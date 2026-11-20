#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <time.h>
#include "xinput_shm_protocol.h"

#define LOG_TAG "MO2XInput"
static std::mutex g_mutex;
static int g_fd = -1;
static XInputShm* g_mapping = nullptr;
static constexpr size_t kMapSize = sizeof(XInputShm);

static uint64_t monotonic_ns() {
    timespec ts{};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
}
static void neutralize_slot(XInputShmSlot& s) {
    __atomic_add_fetch(&s.seq, 1u, __ATOMIC_SEQ_CST);
    s.connected = 0; s.reserved = 0; s.buttons = 0;
    s.left_trigger = 0; s.right_trigger = 0;
    s.thumb_lx = 0; s.thumb_ly = 0; s.thumb_rx = 0; s.thumb_ry = 0;
    std::memset(s.padding, 0, sizeof(s.padding));
    __atomic_add_fetch(&s.seq, 1u, __ATOMIC_SEQ_CST);
}
static bool release_mapping() {
    bool ok = true;
    if (g_mapping) {
        for (uint32_t i = 0; i < XINPUT_SHM_MAX_PLAYERS; ++i) neutralize_slot(g_mapping->slots[i]);
        g_mapping->header.last_update_ns = monotonic_ns();
        if (munmap(g_mapping, kMapSize) != 0) ok = false;
        g_mapping = nullptr;
    }
    if (g_fd >= 0) { if (close(g_fd) != 0) ok = false; g_fd = -1; }
    return ok;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dnctf_movingout2_input_NativeXInputTransport_nativeInitSharedMemory(JNIEnv* env, jobject, jstring path) {
    if (!path) return JNI_FALSE;
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (!utf) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_mutex);
    release_mapping();
    int fd = open(utf, O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    env->ReleaseStringUTFChars(path, utf);
    if (fd < 0) { __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "open failed: %d", errno); return JNI_FALSE; }
    if (ftruncate(fd, static_cast<off_t>(kMapSize)) != 0) { close(fd); return JNI_FALSE; }
    void* addr = mmap(nullptr, kMapSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (addr == MAP_FAILED) { close(fd); return JNI_FALSE; }
    g_fd = fd; g_mapping = static_cast<XInputShm*>(addr);
    std::memset(g_mapping, 0, kMapSize);
    g_mapping->header.magic = XINPUT_SHM_MAGIC;
    g_mapping->header.version = XINPUT_SHM_VERSION;
    g_mapping->header.total_size = static_cast<uint32_t>(kMapSize);
    g_mapping->header.max_players = XINPUT_SHM_MAX_PLAYERS;
    g_mapping->header.last_update_ns = monotonic_ns();
    return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dnctf_movingout2_input_NativeXInputTransport_nativeWriteSerializedPacket(JNIEnv* env, jobject, jint player, jboolean connected, jobject buffer) {
    if (player < 0 || player >= static_cast<jint>(XINPUT_SHM_MAX_PLAYERS) || !buffer) return JNI_FALSE;
    auto* bytes = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!bytes || capacity < XINPUT_SHM_PACKET_BYTES) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_mapping) return JNI_FALSE;
    const size_t i = static_cast<size_t>(player);
    XInputShmSlot& s = g_mapping->slots[i];
    __atomic_add_fetch(&s.seq, 1u, __ATOMIC_RELEASE);
    s.connected = connected ? 1 : 0;
    s.reserved = 0;
    s.buttons = static_cast<uint16_t>(bytes[0]) | (static_cast<uint16_t>(bytes[1]) << 8);
    s.left_trigger = bytes[2]; s.right_trigger = bytes[3];
    auto read_i16 = [&](size_t offset) { return static_cast<int16_t>(static_cast<uint16_t>(bytes[offset]) | (static_cast<uint16_t>(bytes[offset+1]) << 8)); };
    s.thumb_lx = read_i16(4); s.thumb_ly = read_i16(6); s.thumb_rx = read_i16(8); s.thumb_ry = read_i16(10);
    std::memset(s.padding, 0, sizeof(s.padding));
    __atomic_add_fetch(&s.seq, 1u, __ATOMIC_RELEASE);
    g_mapping->header.last_update_ns = monotonic_ns();
    return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dnctf_movingout2_input_NativeXInputTransport_nativeDestroySharedMemory(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return release_mapping() ? JNI_TRUE : JNI_FALSE;
}
