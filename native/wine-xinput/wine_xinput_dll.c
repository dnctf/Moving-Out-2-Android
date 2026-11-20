#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#define MO2X_MAGIC 0x4D4F3258u
#define MO2X_VERSION 1u
#define MO2X_SIZE 128u
#define MO2X_SLOT_SIZE 24u
#define MO2X_SLOTS 4u

typedef struct { uint32_t seq; uint8_t connected,reserved; uint16_t buttons; uint8_t lt,rt; int16_t lx,ly,rx,ry; uint8_t padding[6]; } Slot;
typedef struct { uint32_t magic,version,total_size,max_players; uint64_t last_update_ns,reserved; Slot slots[4]; } Mapping;
_Static_assert(sizeof(Slot)==24,"MO2X slot layout");
_Static_assert(sizeof(Mapping)==128,"MO2X mapping layout");
typedef struct { WORD wButtons; BYTE bLeftTrigger,bRightTrigger; SHORT sThumbLX,sThumbLY,sThumbRX,sThumbRY; } XINPUT_GAMEPAD;
typedef struct { DWORD dwPacketNumber; XINPUT_GAMEPAD Gamepad; } XINPUT_STATE;
typedef struct { WORD wLeftMotorSpeed,wRightMotorSpeed; } XINPUT_VIBRATION;
typedef struct { BYTE bType,bSubType; WORD wFlags; XINPUT_GAMEPAD Gamepad; XINPUT_VIBRATION Vibration; } XINPUT_CAPABILITIES;
typedef struct { BYTE BatteryType,BatteryLevel; } XINPUT_BATTERY_INFORMATION;
typedef struct { DWORD dwFlags; BYTE bReserved; BYTE bVirtualKey; WCHAR Unicode; DWORD dwUserIndex; BYTE bHidCode; } XINPUT_KEYSTROKE;
#define ERROR_DEVICE_NOT_CONNECTED 1167
#define ERROR_EMPTY 4306
#define XINPUT_DEVTYPE_GAMEPAD 1
#define XINPUT_DEVSUBTYPE_GAMEPAD 1
#define XINPUT_CAPABILITIES_FLAG_GAMEPAD 1
#define BATTERY_TYPE_WIRED 1
#define BATTERY_LEVEL_FULL 3

static HANDLE fileHandle=INVALID_HANDLE_VALUE, mappingHandle=NULL;
static const Mapping *view=NULL;
static volatile LONG initState=0;
static BOOL init_mapping(void) {
 LONG prior=InterlockedCompareExchange(&initState,1,0);
 if(prior!=0){while(InterlockedCompareExchange(&initState,2,2)!=2) Sleep(1); return view!=NULL;}
 char path[2048]={0}; DWORD n=GetEnvironmentVariableA("MO2_XINPUT_SHM_PATH",path,sizeof(path));
 if(n>0 && n<sizeof(path)){
  fileHandle=CreateFileA(path,GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,NULL,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,NULL);
  if(fileHandle!=INVALID_HANDLE_VALUE){
   LARGE_INTEGER sz;
   if(GetFileSizeEx(fileHandle,&sz)&&sz.QuadPart>=MO2X_SIZE){
    mappingHandle=CreateFileMappingA(fileHandle,NULL,PAGE_READONLY,0,MO2X_SIZE,NULL);
    if(mappingHandle)view=(const Mapping*)MapViewOfFile(mappingHandle,FILE_MAP_READ,0,0,MO2X_SIZE);
    if(view && (view->magic!=MO2X_MAGIC||view->version!=MO2X_VERSION||view->total_size!=MO2X_SIZE||view->max_players!=MO2X_SLOTS)){UnmapViewOfFile(view);view=NULL;}
   }
  }
 }
 InterlockedExchange(&initState,2); return view!=NULL;
}
static BOOL read_slot(DWORD user,Slot*out){
 if(user>=4||!init_mapping()||!view)return FALSE;
 const volatile BYTE *p=(const volatile BYTE*)&view->slots[user];
 for(int tries=0;tries<1000;tries++){
  LONG a=InterlockedCompareExchange((volatile LONG*)p,0,0); if(a&1)continue;
  BYTE b[24]; for(int i=4;i<24;i++)b[i]=p[i]; MemoryBarrier();
  LONG z=InterlockedCompareExchange((volatile LONG*)p,0,0);
  if(a!=z||(z&1))continue; memcpy(out,b,24);out->seq=(uint32_t)z;return out->connected!=0;
 }
 return FALSE;
}
__declspec(dllexport) DWORD WINAPI XInputGetState(DWORD user,XINPUT_STATE*s){if(!s)return ERROR_INVALID_PARAMETER;Slot x;if(!read_slot(user,&x)){ZeroMemory(s,sizeof(*s));return ERROR_DEVICE_NOT_CONNECTED;}s->dwPacketNumber=x.seq/2;s->Gamepad.wButtons=x.buttons;s->Gamepad.bLeftTrigger=x.lt;s->Gamepad.bRightTrigger=x.rt;s->Gamepad.sThumbLX=x.lx;s->Gamepad.sThumbLY=x.ly;s->Gamepad.sThumbRX=x.rx;s->Gamepad.sThumbRY=x.ry;return ERROR_SUCCESS;}
__declspec(dllexport) DWORD WINAPI XInputSetState(DWORD user,void*v){(void)v;Slot x;return read_slot(user,&x)?ERROR_SUCCESS:ERROR_DEVICE_NOT_CONNECTED;}
__declspec(dllexport) DWORD WINAPI XInputGetCapabilities(DWORD user,DWORD flags,XINPUT_CAPABILITIES*c){(void)flags;if(!c)return ERROR_INVALID_PARAMETER;Slot x;if(!read_slot(user,&x)){ZeroMemory(c,sizeof(*c));return ERROR_DEVICE_NOT_CONNECTED;}ZeroMemory(c,sizeof(*c));c->bType=XINPUT_DEVTYPE_GAMEPAD;c->bSubType=XINPUT_DEVSUBTYPE_GAMEPAD;c->wFlags=XINPUT_CAPABILITIES_FLAG_GAMEPAD;c->Gamepad.wButtons=x.buttons;c->Gamepad.bLeftTrigger=x.lt;c->Gamepad.bRightTrigger=x.rt;c->Gamepad.sThumbLX=x.lx;c->Gamepad.sThumbLY=x.ly;c->Gamepad.sThumbRX=x.rx;c->Gamepad.sThumbRY=x.ry;return ERROR_SUCCESS;}
__declspec(dllexport) void WINAPI XInputEnable(BOOL enabled){(void)enabled;}
__declspec(dllexport) DWORD WINAPI XInputGetBatteryInformation(DWORD user,BYTE type,XINPUT_BATTERY_INFORMATION*b){(void)type;if(!b)return ERROR_INVALID_PARAMETER;Slot x;if(!read_slot(user,&x))return ERROR_DEVICE_NOT_CONNECTED;b->BatteryType=BATTERY_TYPE_WIRED;b->BatteryLevel=BATTERY_LEVEL_FULL;return ERROR_SUCCESS;}
__declspec(dllexport) DWORD WINAPI XInputGetKeystroke(DWORD user,DWORD reserved,XINPUT_KEYSTROKE*k){(void)reserved;if(!k)return ERROR_INVALID_PARAMETER;Slot x;return read_slot(user,&x)?ERROR_EMPTY:ERROR_DEVICE_NOT_CONNECTED;}
BOOL WINAPI DllMain(HINSTANCE h,DWORD reason,LPVOID reserved){(void)reserved;if(reason==DLL_PROCESS_ATTACH){DisableThreadLibraryCalls(h);init_mapping();}if(reason==DLL_PROCESS_DETACH){if(view)UnmapViewOfFile(view);if(mappingHandle)CloseHandle(mappingHandle);if(fileHandle!=INVALID_HANDLE_VALUE)CloseHandle(fileHandle);view=NULL;mappingHandle=NULL;fileHandle=INVALID_HANDLE_VALUE;}return TRUE;}
