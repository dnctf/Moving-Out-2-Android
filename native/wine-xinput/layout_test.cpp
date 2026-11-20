#include "xinput_shm_protocol.h"
#include <stddef.h>
int main(void) {
 if(sizeof(MO2X_HEADER)!=32 || sizeof(MO2X_SLOT)!=24 || sizeof(MO2X_MAPPING)!=128) return 1;
 if(offsetof(MO2X_MAPPING,slots)!=32 || offsetof(MO2X_SLOT,buttons)!=6 || offsetof(MO2X_SLOT,thumb_lx)!=10) return 2; return 0;
}
