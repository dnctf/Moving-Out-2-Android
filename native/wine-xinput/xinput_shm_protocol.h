#ifndef MO2_XINPUT_SHM_PROTOCOL_H
#define MO2_XINPUT_SHM_PROTOCOL_H
#include <stdint.h>
#define MO2X_MAGIC 0x4D4F3258u
#define MO2X_VERSION 1u
#define MO2X_TOTAL_SIZE 128u
#define MO2X_HEADER_SIZE 32u
#define MO2X_SLOT_SIZE 24u
#define MO2X_MAX_PLAYERS 4u
typedef struct MO2X_SLOT { uint32_t seq; uint8_t connected,reserved; uint16_t buttons; uint8_t left_trigger,right_trigger; int16_t thumb_lx,thumb_ly,thumb_rx,thumb_ry; uint8_t padding[6]; } MO2X_SLOT;
typedef struct MO2X_HEADER { uint32_t magic,version,total_size,max_players; uint64_t last_update_ns,reserved; } MO2X_HEADER;
typedef struct MO2X_MAPPING { MO2X_HEADER header; MO2X_SLOT slots[MO2X_MAX_PLAYERS]; } MO2X_MAPPING;

/* Read-only sequence loads: safe for readers mapping the protocol file with FILE_MAP_READ. */
static inline uint32_t mo2x_seq_load(const volatile uint32_t *seq) {
    return __atomic_load_n(seq, __ATOMIC_ACQUIRE);
}
static inline void mo2x_read_fence(void) {
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
}
#if defined(__cplusplus)
static_assert(sizeof(MO2X_HEADER)==32,"MO2X header layout"); static_assert(sizeof(MO2X_SLOT)==24,"MO2X slot layout"); static_assert(sizeof(MO2X_MAPPING)==128,"MO2X map layout");
#else
_Static_assert(sizeof(MO2X_HEADER)==32,"MO2X header layout"); _Static_assert(sizeof(MO2X_SLOT)==24,"MO2X slot layout"); _Static_assert(sizeof(MO2X_MAPPING)==128,"MO2X map layout");
#endif
#endif
