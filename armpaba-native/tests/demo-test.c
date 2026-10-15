#define _GNU_SOURCE
#include "../src/uefi.h"
#include "../src/armjit.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>

static uint16_t r16(unsigned char*p){return (uint16_t)(p[0]|p[1]<<8);}
static uint32_t r32(unsigned char*p){return (uint32_t)p[0]|p[1]<<8|p[2]<<16|p[3]<<24;}
static int printed;
static EFI_STATUS EFIAPI output_string(EFI_CONOUT *out,const CHAR16 *text){
    (void)out;int n=0;
    while(*text && n++<512){ if(*text=='A' && text[1]=='r')printed=1;putchar((char)(*text++)); }
    return 0;
}
struct cbctx {EFI_CONOUT*conout;uint8_t*guest;size_t length;};
static int bridge(ARM_VM*vm,uint64_t addr,void *ctxptr){
    struct cbctx*c=ctxptr;
    if(addr!=(uint64_t)(uintptr_t)output_string || vm->x[0]!=(uint64_t)(uintptr_t)c->conout)return 1;
    uint64_t p=vm->x[1];if(p<(uint64_t)(uintptr_t)c->guest || p>=(uint64_t)(uintptr_t)c->guest+c->length)return 1;
    vm->x[0]=output_string(c->conout,(CHAR16*)(uintptr_t)p);
    return 0;
}
int main(int argc,char**argv){
    assert(argc==2);
    FILE*f=fopen(argv[1],"rb");assert(f);
    fseek(f,0,SEEK_END);long n=ftell(f);rewind(f);assert(n>512&&n<100000);
    uint8_t*raw=malloc((size_t)n);assert(raw&&fread(raw,1,(size_t)n,f)==(size_t)n);fclose(f);
    assert(r16(raw)==0x5a4d);
    uint32_t pe=r32(raw+0x3c), opt=pe+24;
    assert(r16(raw+pe+4)==0xaa64);
    uint16_t sections=r16(raw+pe+6), opt_size=r16(raw+pe+20);
    uint32_t image_size=r32(raw+opt+56);
    uint32_t entry=r32(raw+opt+16), head=r32(raw+opt+60);
    uint8_t*image=mmap(NULL,image_size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    uint8_t*jit=mmap(NULL,ARMPABA_JIT_CAP,PROT_READ|PROT_WRITE|PROT_EXEC,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(image!=MAP_FAILED&&jit!=MAP_FAILED);
    memcpy(image,raw,head);
    for(unsigned i=0;i<sections;i++){
        uint8_t*s=raw+opt+opt_size+i*40;
        uint32_t va=r32(s+12),size=r32(s+16),off=r32(s+20);
        assert(va+size<=image_size && off+size<=(uint32_t)n);
        memcpy(image+va,raw+off,size);
    }
    EFI_CONOUT conout={0};conout.OutputString=output_string;
    EFI_SYSTEM_TABLE st={0};st.ConOut=&conout;
    ARM_VM vm={0};vm.image=image;vm.image_size=image_size;vm.pc=(uintptr_t)image+entry;
    vm.x[0]=UINT64_C(0x10000000);vm.x[1]=(uintptr_t)&st;vm.x[30]=ARMPABA_RETURN_SENTINEL;
    struct cbctx ctx={&conout,image,image_size};
    int rc=arm_run(&vm,jit,ARMPABA_JIT_CAP,bridge,&ctx);
    printf("native translated ARM64 EFI: rc=%d, x0=%llu, ops=%llu, printed=%d\n",
           rc,(unsigned long long)vm.x[0],(unsigned long long)vm.steps,printed);
    assert(rc==0 && vm.x[0]==42 && printed);
    munmap(image,image_size);munmap(jit,ARMPABA_JIT_CAP);free(raw);
    return 0;
}
