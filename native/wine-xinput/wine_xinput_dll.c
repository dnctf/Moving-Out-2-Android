#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include "xinput_shm_protocol.h"

#define MO2X_MAGIC 0x4D4F3258u
#define MO2X_VERSION 1u
#define MO2X_SIZE 128u
#define MO2X_SLOT_SIZE 24u
#define MO2X_SLOTS 4u

typedef struct { uint32_t seq; uint8_t connected,reserved; uint16_t buttons; uint8_t lt,rt; int16_t lx,ly,rx,ry; uint8_t padding[6]; } Slot;
typedef struct { uint32_t magic,version,total_size,max_players; uint64_t last_update_ns,reader_heartbeat; Slot slots[4]; } Mapping;
_Static_assert(sizeof(Slot)==24,"MO2X slot layout");
_Static_assert(sizeof(Mapping)==128,"MO2X mapping layout");
_Static_assert(offsetof(Mapping,reader_heartbeat)==24,"MO2X reader heartbeat offset");
typedef struct { WORD wButtons; BYTE bLeftTrigger,bRightTrigger; SHORT sThumbLX,sThumbLY,sThumbRX,sThumbRY; } XINPUT_GAMEPAD;
typedef struct { DWORD dwPacketNumber; XINPUT_GAMEPAD Gamepad; } XINPUT_STATE;
typedef struct { WORD wLeftMotorSpeed,wRightMotorSpeed; } XINPUT_VIBRATION;
typedef struct { BYTE bType,bSubType; WORD wFlags; XINPUT_GAMEPAD Gamepad; XINPUT_VIBRATION Vibration; } XINPUT_CAPABILITIES;
typedef struct { BYTE BatteryType,BatteryLevel; } XINPUT_BATTERY_INFORMATION;
typedef struct { DWORD dwFlags; BYTE bReserved; BYTE bVirtualKey; WCHAR Unicode; DWORD dwUserIndex; BYTE bHidCode; } XINPUT_KEYSTROKE;
#ifndef ERROR_DEVICE_NOT_CONNECTED
#define ERROR_DEVICE_NOT_CONNECTED 1167
#endif
#ifndef ERROR_EMPTY
#define ERROR_EMPTY 4306
#endif
#define XINPUT_DEVTYPE_GAMEPAD 1
#define XINPUT_DEVSUBTYPE_GAMEPAD 1
#define XINPUT_CAPABILITIES_FLAG_GAMEPAD 1
#define BATTERY_TYPE_WIRED 1
#define BATTERY_LEVEL_FULL 3

static HANDLE fileHandle=INVALID_HANDLE_VALUE, mappingHandle=NULL;
static Mapping *view=NULL;
static volatile LONG initState=0;
static BOOL init_mapping(void) {
 LONG prior=InterlockedCompareExchange(&initState,1,0);
 if(prior!=0){while(InterlockedCompareExchange(&initState,2,2)!=2) Sleep(1); return view!=NULL;}
 char path[2048]={0}; DWORD n=GetEnvironmentVariableA("MO2_XINPUT_SHM_PATH",path,sizeof(path));
 if(n>0 && n<sizeof(path)){
  /* Wine maps Android app-private paths through Z:\\; also accept Wine's
     explicit Unix-path namespace for callers that pass a POSIX path. */
  for (int attempt=0; attempt < 3 && fileHandle == INVALID_HANDLE_VALUE; ++attempt) {
   fileHandle=CreateFileA(path,GENERIC_READ|GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,NULL,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,NULL);
   if (fileHandle == INVALID_HANDLE_VALUE && GetLastError() == ERROR_SHARING_VIOLATION) Sleep(25u * (attempt + 1));
   else break;
  }
  if(fileHandle==INVALID_HANDLE_VALUE && path[0]=='/') {
   char unixPath[2100] = "\\\\?\\unix\\";
   strncat(unixPath, path, sizeof(unixPath)-strlen(unixPath)-1);
   fileHandle=CreateFileA(unixPath,GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,NULL,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,NULL);
  }
  if(fileHandle!=INVALID_HANDLE_VALUE){
   LARGE_INTEGER sz;
   if(GetFileSizeEx(fileHandle,&sz)&&sz.QuadPart>=MO2X_SIZE){
    mappingHandle=CreateFileMappingA(fileHandle,NULL,PAGE_READWRITE,0,MO2X_SIZE,NULL);
    if(mappingHandle)view=(const Mapping*)MapViewOfFile(mappingHandle,FILE_MAP_READ|FILE_MAP_WRITE,0,0,MO2X_SIZE);
    if(view && (view->magic!=MO2X_MAGIC||view->version!=MO2X_VERSION||view->total_size!=MO2X_SIZE||view->max_players!=MO2X_SLOTS)){UnmapViewOfFile(view);view=NULL;}
   }
  }
 }
 InterlockedExchange(&initState,2); return view!=NULL;
}
static BOOL read_slot(DWORD user,Slot*out){
 if(user>=4||!init_mapping()||!view)return FALSE;
 InterlockedIncrement64((volatile LONG64*)&view->reader_heartbeat);
 const volatile BYTE *p=(const volatile BYTE*)&view->slots[user];
 for(int tries=0;tries<1000;tries++){
  /* InterlockedCompareExchange is a read-modify-write and faults on FILE_MAP_READ.
     Use atomic loads only; the mapped protocol is intentionally read-only here. */
  uint32_t a=mo2x_seq_load((const volatile uint32_t*)p); if(a&1u)continue;
  BYTE b[24]={0}; for(int i=4;i<24;i++)b[i]=p[i]; mo2x_read_fence();
  uint32_t z=mo2x_seq_load((const volatile uint32_t*)p);
  if(a!=z||(z&1u)) continue;
  memcpy(out,b,24);
  out->seq=z;
  return out->connected!=0;
 }
 return FALSE;
}
__declspec(dllexport) DWORD WINAPI XInputGetState(DWORD user,XINPUT_STATE*s){if(!s)return ERROR_INVALID_PARAMETER;Slot x;if(!read_slot(user,&x)){ZeroMemory(s,sizeof(*s));return ERROR_DEVICE_NOT_CONNECTED;}s->dwPacketNumber=x.seq/2;s->Gamepad.wButtons=x.buttons;s->Gamepad.bLeftTrigger=x.lt;s->Gamepad.bRightTrigger=x.rt;s->Gamepad.sThumbLX=x.lx;s->Gamepad.sThumbLY=x.ly;s->Gamepad.sThumbRX=x.rx;s->Gamepad.sThumbRY=x.ry;return ERROR_SUCCESS;}
__declspec(dllexport) DWORD WINAPI XInputSetState(DWORD user,void*v){(void)v;Slot x;return read_slot(user,&x)?ERROR_SUCCESS:ERROR_DEVICE_NOT_CONNECTED;}
__declspec(dllexport) DWORD WINAPI XInputGetCapabilities(DWORD user,DWORD flags,XINPUT_CAPABILITIES*c){(void)flags;if(!c)return ERROR_INVALID_PARAMETER;Slot x;if(!read_slot(user,&x)){ZeroMemory(c,sizeof(*c));return ERROR_DEVICE_NOT_CONNECTED;}ZeroMemory(c,sizeof(*c));c->bType=XINPUT_DEVTYPE_GAMEPAD;c->bSubType=XINPUT_DEVSUBTYPE_GAMEPAD;c->wFlags=XINPUT_CAPABILITIES_FLAG_GAMEPAD;c->Gamepad.wButtons=x.buttons;c->Gamepad.bLeftTrigger=x.lt;c->Gamepad.bRightTrigger=x.rt;c->Gamepad.sThumbLX=x.lx;c->Gamepad.sThumbLY=x.ly;c->Gamepad.sThumbRX=x.rx;c->Gamepad.sThumbRY=x.ry;return ERROR_SUCCESS;}
__declspec(dllexport) void WINAPI XInputEnable(BOOL enabled){(void)enabled;}
__declspec(dllexport) DWORD WINAPI XInputGetBatteryInformation(DWORD user,BYTE type,XINPUT_BATTERY_INFORMATION*b){(void)type;if(!b)return ERROR_INVALID_PARAMETER;Slot x;if(!read_slot(user,&x))return ERROR_DEVICE_NOT_CONNECTED;b->BatteryType=BATTERY_TYPE_WIRED;b->BatteryLevel=BATTERY_LEVEL_FULL;return ERROR_SUCCESS;}
__declspec(dllexport) DWORD WINAPI XInputGetKeystroke(DWORD user,DWORD reserved,XINPUT_KEYSTROKE*k){(void)reserved;if(!k)return ERROR_INVALID_PARAMETER;Slot x;return read_slot(user,&x)?ERROR_EMPTY:ERROR_DEVICE_NOT_CONNECTED;}
__declspec(dllexport) DWORD WINAPI XInputGetStateEx(DWORD user,XINPUT_STATE*s){return XInputGetState(user,s);}
__declspec(dllexport) DWORD WINAPI XInputWaitForGuideButton(DWORD user,DWORD flags,DWORD timeout){(void)user;(void)flags;(void)timeout;return ERROR_EMPTY;}
__declspec(dllexport) void WINAPI XInputCancelGuideButton(DWORD user){(void)user;}
__declspec(dllexport) void WINAPI XInputPowerOffController(DWORD user){(void)user;}
BOOL WINAPI DllMain(HINSTANCE h,DWORD reason,LPVOID reserved){(void)reserved;if(reason==DLL_PROCESS_ATTACH){DisableThreadLibraryCalls(h);/* XInput exports initialize the shared mapping lazily outside the loader lock. */}if(reason==DLL_PROCESS_DETACH){if(view)UnmapViewOfFile(view);if(mappingHandle)CloseHandle(mappingHandle);if(fileHandle!=INVALID_HANDLE_VALUE)CloseHandle(fileHandle);view=NULL;mappingHandle=NULL;fileHandle=INVALID_HANDLE_VALUE;}return TRUE;}
