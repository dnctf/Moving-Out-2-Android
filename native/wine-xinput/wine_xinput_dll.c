/*
 * MO2X v1 Wine-side XInput compatibility shim.
 * Built for 64-bit Windows via MinGW-w64. Uses atomic byte reads to avoid
 * torn snapshots while the Android writer updates the shared mapping.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>

#define MO2X_MAGIC 0x4D4F3258u
#define MO2X_VERSION 1u
#define MO2X_SIZE 128u
#define MO2X_HEADER_SIZE 32u
#define MO2X_SLOT_SIZE 24u
#define MO2X_SLOTS 4u

typedef struct { uint32_t seq; uint8_t connected, reserved; uint16_t buttons; uint8_t lt, rt; int16_t lx, ly, rx, ry; uint8_t padding[6]; } MO2X_SLOT;
typedef struct { uint32_t magic, version, total_size, max_players; uint64_t last_update_ns, reserved; MO2X_SLOT slots[MO2X_SLOTS]; } MO2X_MAP;
_Static_assert(sizeof(MO2X_SLOT)==MO2X_SLOT_SIZE, "slot layout mismatch");
_Static_assert(sizeof(MO2X_MAP)==MO2X_SIZE, "mapping layout mismatch");

typedef struct { WORD wButtons; BYTE bLeftTrigger,bRightTrigger; SHORT sThumbLX,sThumbLY,sThumbRX,sThumbRY; } XINPUT_GAMEPAD;
typedef struct { DWORD dwPacketNumber; XINPUT_GAMEPAD Gamepad; } XINPUT_STATE;
typedef struct { BYTE bType,bSubType; WORD wFlags; XINPUT_GAMEPAD Gamepad; DWORD dwFFSamplePeriod,DwReserved; } XINPUT_CAPABILITIES;
typedef struct { BYTE BatteryType,BatteryLevel; } XINPUT_BATTERY_INFORMATION;
typedef struct { BYTE Reserved; WORD Flags; BYTE VirtualKey; WCHAR Unicode; WORD UserIndex; BYTE HidCode; } XINPUT_KEYSTROKE;

#define ERROR_DEVICE_NOT_CONNECTED 1167
#define ERROR_EMPTY 4306
#define XINPUT_GAMEPAD_DPAD_UP 0x0001
#define XINPUT_GAMEPAD_DPAD_DOWN 0x0002
#define XINPUT_GAMEPAD_DPAD_LEFT 0x0004
#define XINPUT_GAMEPAD_DPAD_RIGHT 0x0008
#define XINPUT_GAMEPAD_START 0x0010
#define XINPUT_GAMEPAD_BACK 0x0020
#define XINPUT_GAMEPAD_LEFT_THUMB 0x0040
#define XINPUT_GAMEPAD_RIGHT_THUMB 0x0080
#define XINPUT_GAMEPAD_LEFT_SHOULDER 0x0100
#define XINPUT_GAMEPAD_RIGHT_SHOULDER 0x0200
#define XINPUT_GAMEPAD_A 0x1000
#define XINPUT_GAMEPAD_B 0x2000
#define XINPUT_GAMEPAD_X 0x4000
#define XINPUT_GAMEPAD_Y 0x8000
#define XINPUT_DEVTYPE_GAMEPAD 0x01
#define XINPUT_DEVSUBTYPE_GAMEPAD 0x01
#define XINPUT_CAPABILITIES_FLAG_GAMEPAD 0x0001
#define BATTERY_TYPE_WIRED 0x01
#define BATTERY_LEVEL_FULL 0x03

static HANDLE g_map_handle = INVALID_HANDLE_VALUE;
static MO2X_MAP *g_map = NULL;
static volatile LONG g_init_state = 0; /* 0 uninit, 1 init in progress, 2 done */

static int mo2x_init(void) {
    if (InterlockedCompareExchange(&g_init_state, 1, 0) != 0) {
        while (InterlockedCompareExchange(&g_init_state, 2, 2) != 2) Sleep(1);
        return g_map != NULL;
    }
    const char *path = getenv("MO2_XINPUT_SHM_PATH");
    if (path && *path) {
        int fd = open(path, O_RDONLY);
        if (fd >= 0) {
            struct stat st;
            if (fstat(fd, &st) == 0 && st.st_size >= MO2X_SIZE) {
                void *p = mmap(NULL, MO2X_SIZE, PROT_READ, MAP_SHARED, fd, 0);
                if (p != MAP_FAILED) {
                    MO2X_MAP *m = (MO2X_MAP *)p;
                    if (m->magic == MO2X_MAGIC && m->version == MO2X_VERSION &&
                        m->total_size == MO2X_SIZE && m->max_players == MO2X_SLOTS) {
                        g_map_handle = (HANDLE)(intptr_t)fd;
                        g_map = m;
                        fd = -1;
                    } else munmap(p, MO2X_SIZE);
                }
            }
            if (fd >= 0) close(fd);
        }
    }
    InterlockedExchange(&g_init_state, 2);
    return g_map != NULL;
}

static int snapshot(unsigned user, MO2X_SLOT *out) {
    if (user >= MO2X_SLOTS || !mo2x_init() || !g_map) return 0;
    const volatile unsigned char *src = (const volatile unsigned char *)&g_map->slots[user];
    for (unsigned retry=0; retry<1000; ++retry) {
        uint32_t s1 = atomic_load_explicit((const _Atomic uint32_t *)(const void *)src, memory_order_acquire);
        if (s1 & 1u) continue;
        unsigned char tmp[MO2X_SLOT_SIZE];
        for (size_t i=4; i<MO2X_SLOT_SIZE; ++i)
            tmp[i] = atomic_load_explicit((const _Atomic unsigned char *)(const void *)(src+i), memory_order_relaxed);
        uint32_t s2 = atomic_load_explicit((const _Atomic uint32_t *)(const void *)src, memory_order_acquire);
        if (s1 != s2 || (s2 & 1u)) continue;
        memcpy(out, tmp, sizeof(tmp));
        out->seq = s2;
        return out->connected != 0;
    }
    return 0;
}

__declspec(dllexport) DWORD WINAPI XInputGetState(DWORD user, XINPUT_STATE *state) {
    if (!state) return ERROR_INVALID_PARAMETER;
    MO2X_SLOT slot;
    if (!snapshot(user, &slot)) { memset(state, 0, sizeof(*state)); return ERROR_DEVICE_NOT_CONNECTED; }
    state->dwPacketNumber = slot.seq / 2u;
    state->Gamepad.wButtons = slot.buttons;
    state->Gamepad.bLeftTrigger = slot.lt;
    state->Gamepad.bRightTrigger = slot.rt;
    state->Gamepad.sThumbLX = slot.lx; state->Gamepad.sThumbLY = slot.ly;
    state->Gamepad.sThumbRX = slot.rx; state->Gamepad.sThumbRY = slot.ry;
    return ERROR_SUCCESS;
}
__declspec(dllexport) DWORD WINAPI XInputSetState(DWORD user, void *vibration) { (void)vibration; MO2X_SLOT s; return snapshot(user,&s) ? ERROR_SUCCESS : ERROR_DEVICE_NOT_CONNECTED; }
__declspec(dllexport) DWORD WINAPI XInputGetCapabilities(DWORD user, DWORD flags, XINPUT_CAPABILITIES *caps) {
    (void)flags; if (!caps) return ERROR_INVALID_PARAMETER; MO2X_SLOT s;
    if (!snapshot(user,&s)) { memset(caps,0,sizeof(*caps)); return ERROR_DEVICE_NOT_CONNECTED; }
    memset(caps,0,sizeof(*caps)); caps->bType=XINPUT_DEVTYPE_GAMEPAD; caps->bSubType=XINPUT_DEVSUBTYPE_GAMEPAD; caps->wFlags=XINPUT_CAPABILITIES_FLAG_GAMEPAD; caps->Gamepad=s.buttons ? (XINPUT_GAMEPAD){s.buttons,s.lt,s.rt,s.lx,s.ly,s.rx,s.ry} : (XINPUT_GAMEPAD){0};
    return ERROR_SUCCESS;
}
__declspec(dllexport) void WINAPI XInputEnable(BOOL enabled) { (void)enabled; }
__declspec(dllexport) DWORD WINAPI XInputGetBatteryInformation(DWORD user, BYTE devType, XINPUT_BATTERY_INFORMATION *info) {
    (void)devType; if (!info) return ERROR_INVALID_PARAMETER; MO2X_SLOT s;
    if (!snapshot(user,&s)) return ERROR_DEVICE_NOT_CONNECTED; info->BatteryType=BATTERY_TYPE_WIRED; info->BatteryLevel=BATTERY_LEVEL_FULL; return ERROR_SUCCESS;
}
__declspec(dllexport) DWORD WINAPI XInputGetKeystroke(DWORD user, DWORD reserved, XINPUT_KEYSTROKE *key) {
    (void)reserved; if (!key) return ERROR_INVALID_PARAMETER; MO2X_SLOT s;
    return snapshot(user,&s) ? ERROR_EMPTY : ERROR_DEVICE_NOT_CONNECTED;
}
BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    (void)instance; (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) { DisableThreadLibraryCalls(instance); mo2x_init(); }
    if (reason == DLL_PROCESS_DETACH && g_map) { munmap(g_map,MO2X_SIZE); g_map=NULL; if(g_map_handle!=INVALID_HANDLE_VALUE) close((int)(intptr_t)g_map_handle); }
    return TRUE;
}
