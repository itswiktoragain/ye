#define _GNU_SOURCE
#include "../src/armjit.h"
#include <assert.h>
#include <stdio.h>
#include <sys/mman.h>
#include <string.h>

#define PAGE 4096
static void run(const uint32_t *program,unsigned n,uint64_t expected){
    unsigned char *guest=mmap(0,PAGE,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    unsigned char *jit=mmap(0,ARMPABA_JIT_CAP,PROT_READ|PROT_WRITE|PROT_EXEC,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
    assert(guest!=MAP_FAILED && jit!=MAP_FAILED);
    memcpy(guest,program,n*4);
    ARM_VM vm={0};vm.image=guest;vm.image_size=PAGE;vm.pc=(uintptr_t)guest;vm.x[30]=ARMPABA_RETURN_SENTINEL;
    int ret=arm_run(&vm,jit,ARMPABA_JIT_CAP,0,0);
    printf("host translated %llu guest A64 insns, rc=%d, x0=%llu\n",(unsigned long long)vm.steps,ret,(unsigned long long)vm.x[0]);
    assert(ret==0);assert(vm.x[0]==expected);
    munmap(jit,ARMPABA_JIT_CAP);munmap(guest,PAGE);
}
int main(void){
    uint32_t a[]={0xd28000e0,0xd2800461,0x8b010000,0xd65f03c0}; /* 7+35=42 */
    uint32_t b[]={0xd2800000 | (0x1234<<5),0xf2800000 | (0xabcd<<5) | (1<<21),0xd65f03c0};
    uint32_t c[]={0xd2800020,0x91001400,0xd65f03c0}; /* 1+5=6 */
    uint32_t d[]={0xd2800020,0xd1000400,0xd65f03c0}; /* 1-1=0 */
    run(a,4,42);run(b,3,0xabcd1234);run(c,3,6);run(d,3,0);
    puts("Armpaba x86-64 native dynamic translator checks PASSED");return 0;
}
