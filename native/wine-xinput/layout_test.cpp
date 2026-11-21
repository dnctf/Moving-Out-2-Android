#include "xinput_shm_protocol.h"
#include <stddef.h>

int main(void) {
    if (sizeof(MO2X_HEADER) != 32 || sizeof(MO2X_SLOT) != 24 || sizeof(MO2X_MAPPING) != 128) {
        return 1;
    }
    if (offsetof(MO2X_MAPPING, slots) != 32 || offsetof(MO2X_SLOT, buttons) != 6 || offsetof(MO2X_SLOT, thumb_lx) != 10) {
        return 2;
    }
    alignas(4) volatile uint32_t seq = 0;
    if (mo2x_seq_load(&seq) != 0) return 3;
    seq = 1;
    if (mo2x_seq_load(&seq) != 1) return 4;
    seq = 2;
    if (mo2x_seq_load(&seq) != 2) return 5;
    mo2x_read_fence();
    return 0;
}
