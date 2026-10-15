#include "uefi.h"
#include "armjit.h"

/* Armpaba native UEFI translator research build.
 * Intentionally NO qemu, NO Linux host, NO virtual machine.
 * The x86-64 EFI program loads AArch64 PE/COFF into memory, then executes
 * supported A64 instructions as translated x86-64 code at EFI boot time. */

#define RAW_LIMIT (8U*1024U*1024U)
#define IMAGE_LIMIT (32U*1024U*1024U)
#define JIT_PAGES 4
#define GUEST_STACK_PAGES 16

static EFI_SYSTEM_TABLE *gst;
static EFI_BOOT_SERVICES *gb;
static EFI_GUID fs_guid={0x964e5b22,0x6459,0x11d2,{0x8e,0x39,0x00,0xa0,0xc9,0x69,0x72,0x3b}};
static EFI_HANDLE gimg;

static void copy_bytes(void *to,const void *from,UINTN sz){
    uint8_t *d=(uint8_t*)to; const uint8_t *s=(const uint8_t*)from;
    for(UINTN i=0;i<sz;i++)d[i]=s[i];
}
static void zero_bytes(void *to,UINTN sz){uint8_t *d=to;for(UINTN i=0;i<sz;i++)d[i]=0;}
/* Provide stack-independent helpers to keep Windows freestanding linker happy. */
void *memcpy(void*d,const void*s,size_t n){copy_bytes(d,s,n);return d;}
void *memset(void*d,int v,size_t n){uint8_t*p=d;while(n--)*p++=(uint8_t)v;return d;}
void *memmove(void*d,const void*s,size_t n){uint8_t*a=d;const uint8_t*b=s;
    if(a>b && a<b+n){while(n--)a[n]=b[n];}else{for(size_t i=0;i<n;i++)a[i]=b[i];}return d;}

static void outport(uint16_t port,uint8_t c){
    __asm__ __volatile__("outb %0, %1"::"a"(c),"Nd"(port));
}
static uint8_t inport(uint16_t port){
    uint8_t ret;__asm__ __volatile__("inb %1, %0":"=a"(ret):"Nd"(port));return ret;
}
static void serial_init(void){
    /* Legacy COM1 mirror makes headless UEFI smoke tests observable. */
    outport(0x3f9,0);outport(0x3fb,0x80);
    outport(0x3f8,1);outport(0x3f9,0);
    outport(0x3fb,3);outport(0x3fa,0xc7);outport(0x3fc,3);
}
static void serial_char(uint8_t c){
    for(int n=0;n<10000;n++)if(inport(0x3fd)&0x20){outport(0x3f8,c);break;}
}
static void say(const CHAR16*s){
    gst->ConOut->OutputString(gst->ConOut,s);
    for(UINTN i=0;s[i]&&i<16384;i++)serial_char(s[i]<128?(uint8_t)s[i]:'?');
}
static void eol(void){say(L"\r\n");}
static void hex(uint64_t v){CHAR16 s[19];s[0]='0';s[1]='x';for(int i=0;i<16;i++){
    unsigned x=(unsigned)((v>>((15-i)*4))&15);s[2+i]=x<10?'0'+x:'A'+x-10;}
    s[18]=0;say(s);
}
static void num(unsigned n){CHAR16 buf[24];int p=23;buf[p]=0;do{buf[--p]=(CHAR16)('0'+(n%10));n/=10;}while(n&&p);say(buf+p);}
static uint16_t read16(const uint8_t*p){return (uint16_t)(p[0]|((uint16_t)p[1]<<8));}
static uint32_t read32(const uint8_t*p){return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);}
static uint64_t read64(const uint8_t*p){return (uint64_t)read32(p)|((uint64_t)read32(p+4)<<32);}
static void write64(uint8_t*p,uint64_t v){for(int i=0;i<8;i++)p[i]=(uint8_t)(v>>(i*8));}

typedef struct {uint8_t *base;UINTN pages;uint64_t entry;uint64_t image_len;} GUEST_IMAGE;

/* Map an AArch64 PE32+ image and apply IMAGE_REL_BASED_DIR64 fix-ups. */
static EFI_STATUS parse_pe(uint8_t *raw,UINTN len,GUEST_IMAGE *o){
    if(len<0x100 || read16(raw)!=0x5a4d)return EFI_UNSUPPORTED;
    UINTN pe=read32(raw+0x3c);
    if(pe>len-24 || read32(raw+pe)!=0x00004550)return EFI_UNSUPPORTED;
    if(read16(raw+pe+4)!=0xaa64){say(L"Expected ARM64 PE machine 0xAA64.\r\n");return EFI_UNSUPPORTED;}
    uint16_t sections=read16(raw+pe+6), opt_size=read16(raw+pe+20);
    UINTN opt=pe+24;
    if(sections==0 || sections>96 || opt_size<160 || opt+opt_size>len || read16(raw+opt)!=0x20b)return EFI_UNSUPPORTED;
    uint32_t image_size=read32(raw+opt+56), hdr_size=read32(raw+opt+60);
    uint32_t entry_rva=read32(raw+opt+16);
    if(image_size<4096 || image_size>IMAGE_LIMIT || hdr_size>image_size || hdr_size>len || entry_rva>=image_size)return EFI_UNSUPPORTED;
    UINTN sectbl=opt+opt_size;
    if(sectbl>len-(UINTN)sections*40)return EFI_UNSUPPORTED;
    UINTN pages=((UINTN)image_size+4095)/4096;
    uint64_t addr=0;
    if(EFI_ERROR(bs_allocate_pages(gb,0,1,pages,&addr)))return EFI_OUT_OF_RESOURCES;
    uint8_t *base=(uint8_t*)(uintptr_t)addr;
    zero_bytes(base,pages*4096);
    copy_bytes(base,raw,hdr_size);
    for(unsigned i=0;i<sections;i++){
        uint8_t *s=raw+sectbl+i*40;
        uint32_t virt=read32(s+12),size=read32(s+16),source=read32(s+20);
        if(virt>=image_size || size>image_size-virt || source>len || size>len-source){
            bs_free_pages(gb,addr,pages);return EFI_UNSUPPORTED;
        }
        copy_bytes(base+virt,raw+source,size);
    }
    /* ASLR rebasing where the PE supplies a base relocation directory. */
    uint64_t pref=read64(raw+opt+24);
    uint32_t reloc_rva=read32(raw+opt+112+5*8);
    uint32_t reloc_len=read32(raw+opt+112+5*8+4);
    if(reloc_rva && reloc_len && reloc_rva<image_size && reloc_len<=image_size-reloc_rva){
        uint32_t cursor=0;
        while(cursor+8<=reloc_len){
            uint32_t page=read32(base+reloc_rva+cursor);
            uint32_t block_len=read32(base+reloc_rva+cursor+4);
            if(block_len<8 || block_len>reloc_len-cursor || (block_len&1))break;
            for(uint32_t off=8;off+2<=block_len;off+=2){
                uint16_t x=read16(base+reloc_rva+cursor+off);
                uint16_t kind=x>>12;
                uint32_t rva=page+(x&4095);
                if(kind==10 && rva<=image_size-8){
                    uint64_t old=read64(base+rva);
                    write64(base+rva,old+addr-pref);
                }else if(kind!=0){
                    bs_free_pages(gb,addr,pages);return EFI_UNSUPPORTED;
                }
            }
            cursor+=block_len;
        }
    }
    o->base=base;o->pages=pages;o->entry=addr+entry_rva;o->image_len=image_size;
    return EFI_SUCCESS;
}

typedef struct {EFI_SYSTEM_TABLE *st;uint8_t *image;UINTN len;} BRIDGE_CTX;
static int guest_bridge(ARM_VM *vm,uint64_t target,void *context){
    BRIDGE_CTX *ctx=(BRIDGE_CTX*)context;
    /* Only forward the known EFI OutputString service in this build. This
     * avoids blindly executing arbitrary AArch64-chosen x86 addresses. */
    if(target==(uint64_t)(uintptr_t)ctx->st->ConOut->OutputString &&
       vm->x[0]==(uint64_t)(uintptr_t)ctx->st->ConOut){
        uint64_t str=vm->x[1];
        uintptr_t p=(uintptr_t)ctx->image;
        if(str<p || str>=p+ctx->len)return 1;
        /* Ensure UTF16 NUL terminator fits in the loaded guest image. */
        UINTN limit=(UINTN)(p+ctx->len-str);
        int found=0;
        for(UINTN i=0;i+1<limit && i<8192;i+=2){
            const uint8_t*buf=(const uint8_t*)(uintptr_t)(str+i);
            if(buf[0]==0&&buf[1]==0){found=1;break;}
        }
        if(!found)return 1;
        vm->x[0]=ctx->st->ConOut->OutputString(ctx->st->ConOut,(const CHAR16*)(uintptr_t)str);
        return 0;
    }
    return 1;
}

static EFI_STATUS run_guest(void *data,UINTN bytes){
    GUEST_IMAGE pe={0};
    EFI_STATUS status=parse_pe((uint8_t*)data,bytes,&pe);
    if(EFI_ERROR(status)){say(L"PE parser rejected ARM64 boot file.\r\n");return status;}
    uint64_t exec=0,stack=0;
    if(EFI_ERROR(bs_allocate_pages(gb,0,1,JIT_PAGES,&exec)) ||
       EFI_ERROR(bs_allocate_pages(gb,0,2,GUEST_STACK_PAGES,&stack))){
        say(L"Cannot allocate translated code/stack.\r\n");
        if(exec)bs_free_pages(gb,exec,JIT_PAGES);
        if(stack)bs_free_pages(gb,stack,GUEST_STACK_PAGES);
        bs_free_pages(gb,(uint64_t)(uintptr_t)pe.base,pe.pages);
        return EFI_OUT_OF_RESOURCES;
    }
    ARM_VM vm={0};
    vm.image=pe.base;vm.image_size=pe.image_len;vm.pc=pe.entry;
    vm.x[0]=(uint64_t)(uintptr_t)gimg;
    vm.x[1]=(uint64_t)(uintptr_t)gst;
    vm.x[30]=ARMPABA_RETURN_SENTINEL;
    vm.sp=stack+GUEST_STACK_PAGES*4096-16;
    BRIDGE_CTX ctx={gst,pe.base,pe.image_len};
    say(L"Armpaba JIT: executing translated A64 instructions...\r\n");
    int rc=arm_run(&vm,(void*)(uintptr_t)exec,JIT_PAGES*4096,guest_bridge,&ctx);
    if(rc==0){say(L"ARM64 entry returned. x0 = ");hex(vm.x[0]);eol();}
    else {
        say(L"Translation stopped (NOT guest OS booted). Reason ");num((unsigned)(-rc));eol();
        say(L"A64 PC ");hex(vm.last_unsupported_pc?vm.last_unsupported_pc:vm.pc);
        say(L"  Opcode ");hex(vm.last_unsupported_insn);eol();
    }
    bs_free_pages(gb,exec,JIT_PAGES);
    bs_free_pages(gb,stack,GUEST_STACK_PAGES);
    bs_free_pages(gb,(uint64_t)(uintptr_t)pe.base,pe.pages);
    return rc==0?EFI_SUCCESS:EFI_UNSUPPORTED;
}

static EFI_STATUS try_volume(EFI_HANDLE h,int show){
    EFI_FS *fs=0;
    if(EFI_ERROR(bs_handle_protocol(gb,h,&fs_guid,(void**)&fs)))return EFI_NOT_FOUND;
    EFI_FILE *root=0,*file=0;
    EFI_STATUS s=fs->OpenVolume(fs,&root);
    if(EFI_ERROR(s) || !root)return EFI_NOT_FOUND;
    s=root->Open(root,&file,L"\\EFI\\BOOT\\BOOTAA64.EFI",1,0);
    if(EFI_ERROR(s))s=root->Open(root,&file,L"\\BOOTAA64.EFI",1,0);
    if(EFI_ERROR(s)||!file){root->Close(root);return EFI_NOT_FOUND;}
    if(show){file->Close(file);root->Close(root);return EFI_SUCCESS;}
    void *raw=0;
    s=bs_allocate_pool(gb,2,RAW_LIMIT,&raw);
    if(EFI_ERROR(s)){file->Close(file);root->Close(root);return s;}
    UINTN length=0;
    while(length<RAW_LIMIT){
        UINTN n=65536;
        if(n>RAW_LIMIT-length)n=RAW_LIMIT-length;
        s=file->Read(file,&n,(uint8_t*)raw+length);
        if(EFI_ERROR(s)||n==0)break;
        length+=n;
    }
    file->Close(file);root->Close(root);
    if(EFI_ERROR(s)||length>=RAW_LIMIT){say(L"File read failed or file over 8 MiB.\r\n");bs_free_pool(gb,raw);return EFI_LOAD_ERROR;}
    say(L"Loaded ARM64 EFI image, size ");num((unsigned)length);say(L" bytes.\r\n");
    s=run_guest(raw,length);
    bs_free_pool(gb,raw);return s;
}

static EFI_HANDLE volumes[16];
static unsigned count;
static void find_volumes(void){
    EFI_HANDLE *handles=0;UINTN n=0;count=0;
    if(EFI_ERROR(bs_locate_handles(gb,&fs_guid,&n,&handles)))return;
    for(UINTN i=0;i<n && count<16;i++){
        if(!EFI_ERROR(try_volume(handles[i],1)))volumes[count++]=handles[i];
    }
    bs_free_pool(gb,handles);
}
static void header(void){
    gst->ConOut->ClearScreen(gst->ConOut);
    say(L"\r\n  ARMPABA / NATIVE ARM64 TRANSLATOR  [research alpha]\r\n");
    say(L"  ------------------------------------------------------\r\n");
    say(L"  x86-64 UEFI > A64 JIT > ARM64 EFI entry\r\n");
    say(L"  NO QEMU / NO virtual ARM machine / NO Linux host\r\n\r\n");
    say(L"  Limited A64 instruction subset. Apple M hardware not supported.\r\n\r\n");
}
static int key(int *scan){EFI_INPUT_KEY k;
    EFI_STATUS st=gst->ConIn->ReadKeyStroke(gst->ConIn,&k);
    if(EFI_ERROR(st))return -1;
    *scan=k.ScanCode;
    return (int)k.UnicodeChar;
}
EFI_STATUS EFIAPI efi_main(EFI_HANDLE img,EFI_SYSTEM_TABLE *sys){
    gimg=img;gst=sys;gb=(EFI_BOOT_SERVICES*)sys->BootServices;
    serial_init();header();find_volumes();
    say(L"ARM64 EFI boot files found on ");num(count);say(L" volume(s).\r\n");
    say(L"Press F5 for device selection, ENTER for default.\r\n");
    say(L"Automatically continuing after 5 seconds...\r\n");
    int menu=count==0;
    for(int i=0;i<250 && !menu;i++){
        int sc=0, k=key(&sc);
        if(sc==0x0f || k=='m' || k=='M'){menu=1;break;}
        if(k==13)break;
        bs_stall(gb,20000);
    }
    if(menu){
        header();say(L"F5 BOOT DEVICE SELECTION\r\n\r\n");
        if(!count)say(L"No ARM64 BOOTAA64.EFI found on firmware volumes.\r\n");
        for(unsigned i=0;i<count;i++){
            say(L"  [");num(i+1);say(L"]  ARM64 EFI on filesystem ");num(i+1);eol();
        }
        say(L"\r\n  Type a number, or ESC to exit.\r\n");
        while(1){int sc=0,k=key(&sc);
            if(k==27 || sc==0x17)return EFI_SUCCESS;
            if(k>='1'&&k<='9'&&((unsigned)(k-'1'))<count){
                say(L"\r\nSelected volume ");num((unsigned)(k-'0'));eol();
                EFI_STATUS s=try_volume(volumes[k-'1'],0);
                say(L"\r\nTranslation returned: ");hex(s);eol();return s;
            }
            bs_stall(gb,20000);
        }
    }
    if(count){say(L"\r\nStarting first ARM64 EFI payload...\r\n");EFI_STATUS s=try_volume(volumes[0],0);
        say(L"\r\nResult: ");hex(s);eol();return s;}
    return EFI_NOT_FOUND;
}
