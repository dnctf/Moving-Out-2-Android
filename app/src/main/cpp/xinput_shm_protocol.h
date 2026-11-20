#pragma once
#include <cstddef>
#include <cstdint>

#define XINPUT_SHM_MAGIC 0x4D4F3258u
#define XINPUT_SHM_VERSION 1u
#define XINPUT_SHM_MAX_PLAYERS 4u
#define XINPUT_SHM_PACKET_BYTES 12u

enum XInputShmButton : uint16_t {
    XINPUT_UP = 0x0001, XINPUT_DOWN = 0x0002, XINPUT_LEFT = 0x0004, XINPUT_RIGHT = 0x0008,
    XINPUT_START = 0x0010, XINPUT_BACK = 0x0020, XINPUT_LEFT_THUMB = 0x0040,
    XINPUT_RIGHT_THUMB = 0x0080, XINPUT_LEFT_SHOULDER = 0x0100, XINPUT_RIGHT_SHOULDER = 0x0200,
    XINPUT_A = 0x1000, XINPUT_B = 0x2000, XINPUT_X = 0x4000, XINPUT_Y = 0x8000
};

struct alignas(8) XInputShmSlot {
    uint32_t seq;
    uint8_t connected;
    uint8_t reserved;
    uint16_t buttons;
    uint8_t left_trigger;
    uint8_t right_trigger;
    int16_t thumb_lx;
    int16_t thumb_ly;
    int16_t thumb_rx;
    int16_t thumb_ry;
    uint8_t padding[6];
};
struct alignas(8) XInputShmHeader {
    uint32_t magic;
    uint32_t version;
    uint32_t total_size;
    uint32_t max_players;
    uint64_t last_update_ns;
    uint64_t reserved_header;
};
struct alignas(8) XInputShm {
    XInputShmHeader header;
    XInputShmSlot slots[XINPUT_SHM_MAX_PLAYERS];
};
static_assert(sizeof(XInputShmHeader) == 32, "MO2X header must be 32 bytes");
static_assert(sizeof(XInputShmSlot) == 24, "MO2X slot must be 24 bytes");
static_assert(offsetof(XInputShm, slots) == 32, "MO2X slots offset must be 32");
static_assert(sizeof(XInputShm) == 128, "MO2X mapping must be 128 bytes");
