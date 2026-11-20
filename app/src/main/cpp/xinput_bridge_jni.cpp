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
    __atomic_store_n(&s.connected, 0, __ATOMIC_RELAXED); __atomic_store_n(&s.reserved, 0, __ATOMIC_RELAXED); __atomic_store_n(&s.buttons, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&s.left_trigger, 0, __ATOMIC_RELAXED); __atomic_store_n(&s.right_trigger, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&s.thumb_lx, 0, __ATOMIC_RELAXED); __atomic_store_n(&s.thumb_ly, 0, __ATOMIC_RELAXED); __atomic_store_n(&s.thumb_rx, 0, __ATOMIC_RELAXED); __atomic_store_n(&s.thumb_ry, 0, __ATOMIC_RELAXED);
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
    __atomic_add_fetch(&s.seq, 1u, __ATOMIC_SEQ_CST);
    __atomic_store_n(&s.connected, connected ? 1 : 0, __ATOMIC_RELAXED);
    __atomic_store_n(&s.reserved, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&s.buttons, static_cast<uint16_t>(bytes[0]) | (static_cast<uint16_t>(bytes[1]) << 8), __ATOMIC_RELAXED);
    __atomic_store_n(&s.left_trigger, bytes[2], __ATOMIC_RELAXED); __atomic_store_n(&s.right_trigger, bytes[3], __ATOMIC_RELAXED);
    auto read_i16 = [&](size_t offset) { return static_cast<int16_t>(static_cast<uint16_t>(bytes[offset]) | (static_cast<uint16_t>(bytes[offset+1]) << 8)); };
    __atomic_store_n(&s.thumb_lx, read_i16(4), __ATOMIC_RELAXED); __atomic_store_n(&s.thumb_ly, read_i16(6), __ATOMIC_RELAXED); __atomic_store_n(&s.thumb_rx, read_i16(8), __ATOMIC_RELAXED); __atomic_store_n(&s.thumb_ry, read_i16(10), __ATOMIC_RELAXED);
    std::memset(s.padding, 0, sizeof(s.padding));
    __atomic_add_fetch(&s.seq, 1u, __ATOMIC_SEQ_CST);
    g_mapping->header.last_update_ns = monotonic_ns();
    return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dnctf_movingout2_input_NativeXInputTransport_nativeDestroySharedMemory(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return release_mapping() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dnctf_movingout2_input_NativeXInputTransport_nativeReadSnapshot(JNIEnv* env, jobject, jstring path, jint player, jobject buffer) {
    if (!path || player < 0 || player >= static_cast<jint>(XINPUT_SHM_MAX_PLAYERS) || !buffer) return JNI_FALSE;
    auto* out = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (!out || capacity < XINPUT_SHM_PACKET_BYTES) return JNI_FALSE;
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (!utf) return JNI_FALSE;
    int fd = open(utf, O_RDONLY | O_CLOEXEC);
    env->ReleaseStringUTFChars(path, utf);
    if (fd < 0) return JNI_FALSE;
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size < static_cast<off_t>(kMapSize)) { close(fd); return JNI_FALSE; }
    void* addr = mmap(nullptr, kMapSize, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (addr == MAP_FAILED) return JNI_FALSE;
    const auto* mapping = static_cast<const XInputShm*>(addr);
    bool valid = mapping->header.magic == XINPUT_SHM_MAGIC && mapping->header.version == XINPUT_SHM_VERSION &&
        mapping->header.total_size == kMapSize && mapping->header.max_players == XINPUT_SHM_MAX_PLAYERS;
    bool stable = false;
    uint8_t packet[XINPUT_SHM_PACKET_BYTES]{};
    if (valid) {
        const XInputShmSlot& slot = mapping->slots[static_cast<size_t>(player)];
        for (int attempt = 0; attempt < 1000; ++attempt) {
            const uint32_t before = __atomic_load_n(&slot.seq, __ATOMIC_ACQUIRE);
            if (before & 1u) continue;
            const uint16_t buttons = __atomic_load_n(&slot.buttons, __ATOMIC_RELAXED);
            const uint8_t leftTrigger = __atomic_load_n(&slot.left_trigger, __ATOMIC_RELAXED);
            const uint8_t rightTrigger = __atomic_load_n(&slot.right_trigger, __ATOMIC_RELAXED);
            const int16_t lx = __atomic_load_n(&slot.thumb_lx, __ATOMIC_RELAXED), ly = __atomic_load_n(&slot.thumb_ly, __ATOMIC_RELAXED);
            const int16_t rx = __atomic_load_n(&slot.thumb_rx, __ATOMIC_RELAXED), ry = __atomic_load_n(&slot.thumb_ry, __ATOMIC_RELAXED);
            packet[0] = static_cast<uint8_t>(buttons & 0xffu); packet[1] = static_cast<uint8_t>((buttons >> 8) & 0xffu);
            packet[2] = leftTrigger; packet[3] = rightTrigger;
            packet[4] = static_cast<uint8_t>(lx & 0xff); packet[5] = static_cast<uint8_t>((lx >> 8) & 0xff);
            packet[6] = static_cast<uint8_t>(ly & 0xff); packet[7] = static_cast<uint8_t>((ly >> 8) & 0xff);
            packet[8] = static_cast<uint8_t>(rx & 0xff); packet[9] = static_cast<uint8_t>((rx >> 8) & 0xff);
            packet[10] = static_cast<uint8_t>(ry & 0xff); packet[11] = static_cast<uint8_t>((ry >> 8) & 0xff);
            const uint32_t after = __atomic_load_n(&slot.seq, __ATOMIC_ACQUIRE);
            if (before == after && !(after & 1u)) { stable = true; break; }
        }
    }
    if (stable) std::memcpy(out, packet, sizeof(packet));
    munmap(addr, kMapSize);
    return stable ? JNI_TRUE : JNI_FALSE;
}
