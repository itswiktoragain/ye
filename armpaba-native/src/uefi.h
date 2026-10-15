#ifndef ARMPABA_UEFI_H
#define ARMPABA_UEFI_H
#include <stdint.h>
#include <stddef.h>
#ifdef _MSC_VER
#define EFIAPI
#else
#define EFIAPI __attribute__((ms_abi))
#endif

typedef uint64_t EFI_STATUS;
typedef uint64_t UINTN;
typedef uint16_t CHAR16;
typedef void *EFI_HANDLE;
typedef struct {uint32_t a; uint16_t b,c; uint8_t d[8];} EFI_GUID;
typedef struct {uint64_t Signature; uint32_t Revision,HeaderSize,CRC32,Reserved;} EFI_TABLE_HEADER;
typedef struct {uint16_t ScanCode; CHAR16 UnicodeChar;} EFI_INPUT_KEY;
typedef struct EFI_SIMPLE_TEXT_OUTPUT_PROTOCOL EFI_CONOUT;
struct EFI_SIMPLE_TEXT_OUTPUT_PROTOCOL {
    EFI_STATUS (EFIAPI *Reset)(EFI_CONOUT*,int);
    EFI_STATUS (EFIAPI *OutputString)(EFI_CONOUT*,const CHAR16*);
    EFI_STATUS (EFIAPI *TestString)(EFI_CONOUT*,const CHAR16*);
    EFI_STATUS (EFIAPI *QueryMode)(EFI_CONOUT*,UINTN,UINTN*,UINTN*);
    EFI_STATUS (EFIAPI *SetMode)(EFI_CONOUT*,UINTN);
    EFI_STATUS (EFIAPI *SetAttribute)(EFI_CONOUT*,UINTN);
    EFI_STATUS (EFIAPI *ClearScreen)(EFI_CONOUT*);
    EFI_STATUS (EFIAPI *SetCursorPosition)(EFI_CONOUT*,UINTN,UINTN);
    EFI_STATUS (EFIAPI *EnableCursor)(EFI_CONOUT*,int);
    void *Mode;
};
typedef struct EFI_SIMPLE_TEXT_INPUT_PROTOCOL EFI_CONIN;
struct EFI_SIMPLE_TEXT_INPUT_PROTOCOL {
    EFI_STATUS (EFIAPI *Reset)(EFI_CONIN*,int);
    EFI_STATUS (EFIAPI *ReadKeyStroke)(EFI_CONIN*,EFI_INPUT_KEY*);
    void *WaitForKey;
};
typedef struct {
    EFI_TABLE_HEADER Hdr;
    CHAR16 *FirmwareVendor;
    uint32_t FirmwareRevision;
    uint32_t Padding;
    EFI_HANDLE ConsoleInHandle;
    EFI_CONIN *ConIn;
    EFI_HANDLE ConsoleOutHandle;
    EFI_CONOUT *ConOut;
    EFI_HANDLE StandardErrorHandle;
    EFI_CONOUT *StdErr;
    void *RuntimeServices;
    void *BootServices;
    UINTN NumberOfTableEntries;
    void *ConfigurationTable;
} EFI_SYSTEM_TABLE;
typedef struct EFI_FILE_PROTOCOL EFI_FILE;
struct EFI_FILE_PROTOCOL {
    uint64_t Revision;
    EFI_STATUS (EFIAPI *Open)(EFI_FILE*,EFI_FILE**,const CHAR16*,uint64_t,uint64_t);
    EFI_STATUS (EFIAPI *Close)(EFI_FILE*);
    EFI_STATUS (EFIAPI *Delete)(EFI_FILE*);
    EFI_STATUS (EFIAPI *Read)(EFI_FILE*,UINTN*,void*);
    EFI_STATUS (EFIAPI *Write)(EFI_FILE*,UINTN*,const void*);
    EFI_STATUS (EFIAPI *GetPosition)(EFI_FILE*,uint64_t*);
    EFI_STATUS (EFIAPI *SetPosition)(EFI_FILE*,uint64_t);
    void *GetInfo, *SetInfo, *Flush, *OpenEx, *ReadEx, *WriteEx, *FlushEx;
};
typedef struct EFI_SIMPLE_FILE_SYSTEM_PROTOCOL EFI_FS;
struct EFI_SIMPLE_FILE_SYSTEM_PROTOCOL {
    uint64_t Revision;
    EFI_STATUS (EFIAPI *OpenVolume)(EFI_FS*,EFI_FILE**);
};
typedef struct {
    EFI_TABLE_HEADER Hdr;
    void *fn[44];
} EFI_BOOT_SERVICES;
#define EFI_ERROR(s) (((s)>>63)!=0)
#define EFI_SUCCESS ((EFI_STATUS)0)
#define EFI_NOT_FOUND (UINT64_C(0x800000000000000e))
#define EFI_UNSUPPORTED (UINT64_C(0x8000000000000003))
#define EFI_LOAD_ERROR (UINT64_C(0x8000000000000001))
#define EFI_OUT_OF_RESOURCES (UINT64_C(0x8000000000000009))

static inline EFI_STATUS bs_allocate_pool(EFI_BOOT_SERVICES*b,UINTN type,UINTN sz,void **o) {
    return ((EFI_STATUS(EFIAPI*)(UINTN,UINTN,void**))b->fn[5])(type,sz,o);
}
static inline EFI_STATUS bs_free_pool(EFI_BOOT_SERVICES*b,void*p) {
    return ((EFI_STATUS(EFIAPI*)(void*))b->fn[6])(p);
}
static inline EFI_STATUS bs_allocate_pages(EFI_BOOT_SERVICES*b,UINTN type,UINTN mem_type,UINTN pages,uint64_t *address) {
    return ((EFI_STATUS(EFIAPI*)(UINTN,UINTN,UINTN,uint64_t*))b->fn[2])(type,mem_type,pages,address);
}
static inline EFI_STATUS bs_free_pages(EFI_BOOT_SERVICES*b,uint64_t address,UINTN pages) {
    return ((EFI_STATUS(EFIAPI*)(uint64_t,UINTN))b->fn[3])(address,pages);
}
static inline EFI_STATUS bs_handle_protocol(EFI_BOOT_SERVICES*b,EFI_HANDLE h,EFI_GUID*g,void**o) {
    return ((EFI_STATUS(EFIAPI*)(EFI_HANDLE,EFI_GUID*,void**))b->fn[16])(h,g,o);
}
static inline EFI_STATUS bs_locate_handles(EFI_BOOT_SERVICES*b,EFI_GUID*g,UINTN*n,EFI_HANDLE**h) {
    return ((EFI_STATUS(EFIAPI*)(UINTN,EFI_GUID*,void*,UINTN*,EFI_HANDLE**))b->fn[36])(2,g,0,n,h);
}
static inline void bs_stall(EFI_BOOT_SERVICES*b,UINTN micros) {
    ((EFI_STATUS(EFIAPI*)(UINTN))b->fn[28])(micros);
}
#endif
