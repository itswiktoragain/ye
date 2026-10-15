#include "armjit.h"

/* x86-64 native code emitter. Emits a block using RCX to hold ARM_VM*.
 * Only volatile registers RAX/RDX/RCX are touched and ABI is preserved.
 * This proof-of-concept deliberately stops at unsupported ARM opcodes,
 * rather than claiming it can boot an arbitrary M-series kernel. */

typedef struct { uint8_t *p; size_t n, cap; int failed; } EMIT;
static void b(EMIT *e, uint8_t byte) {
    if (e->n >= e->cap) { e->failed=1; return; }
    e->p[e->n++]=byte;
}
static void d32(EMIT *e, uint32_t x) { for (int i=0;i<4;i++) b(e,(uint8_t)(x>>(i*8))); }
static void d64(EMIT *e, uint64_t x) { for (int i=0;i<8;i++) b(e,(uint8_t)(x>>(i*8))); }
static void start_block(EMIT *e, ARM_VM *vm) {
    /* mov rcx, absolute ARM_VM pointer */
    b(e,0x48); b(e,0xb9); d64(e,(uint64_t)(uintptr_t)vm);
}
static void load_x(EMIT *e,unsigned n) {
    if (n==31) {b(e,0x31);b(e,0xc0);return;} /* xor eax,eax (XZR) */
    /* mov rax,[rcx+disp32] */
    b(e,0x48);b(e,0x8b);b(e,0x81);d32(e,(uint32_t)(offsetof(ARM_VM,x)+n*8));
}
static void store_x(EMIT *e,unsigned n) {
    if (n==31) return; /* XZR is discard */
    b(e,0x48);b(e,0x89);b(e,0x81);d32(e,(uint32_t)(offsetof(ARM_VM,x)+n*8));
}
static void mov_rax64(EMIT *e,uint64_t n) {b(e,0x48);b(e,0xb8);d64(e,n);}
static void mov_rdx64(EMIT *e,uint64_t n) {b(e,0x48);b(e,0xba);d64(e,n);}
static int64_t sext(uint64_t v,int bits) {
    return (int64_t)((v ^ (UINT64_C(1)<<(bits-1))) - (UINT64_C(1)<<(bits-1)));
}
static uint32_t get32(const uint8_t *x) {
    return (uint32_t)x[0] | ((uint32_t)x[1]<<8) | ((uint32_t)x[2]<<16) | ((uint32_t)x[3]<<24);
}
/* Return 1 if instruction compiled; 0 means dispatcher handles or rejects. */
static int translate_one(EMIT *e,uint32_t ins,uint64_t at) {
    unsigned rd=ins&31, rn=(ins>>5)&31, rm=(ins>>16)&31;
    int sf=(ins>>31)&1;
    if (ins==0xd503201f) return 1; /* nop */
    /* MOVZ, MOVN, MOVK (wide immediate, 32 and 64-bit). */
    if ((ins&0x1f800000)==0x12800000) {
        unsigned opc=(ins>>29)&3, hw=(ins>>21)&3;
        uint64_t imm=(uint64_t)((ins>>5)&65535)<<(hw*16);
        if (opc==1 || (!sf && hw>1)) return 0;
        if (opc==2) mov_rax64(e,imm);
        else if (opc==0) mov_rax64(e,~imm);
        else {
            load_x(e,rd);
            mov_rdx64(e,~(UINT64_C(0xffff)<<(hw*16)));
            b(e,0x48);b(e,0x21);b(e,0xd0); /* and rax,rdx */
            mov_rdx64(e,imm);
            b(e,0x48);b(e,0x09);b(e,0xd0); /* or rax,rdx */
        }
        if(!sf){ b(e,0x89);b(e,0xc0); } /* mov eax,eax clears high */
        store_x(e,rd);return 1;
    }
    /* ADD / SUB immediate (not flag-setting variant). */
    if ((ins & 0x1f000000)==0x11000000 && ((ins>>29)&1)==0) {
        unsigned sh=(ins>>22)&1;
        uint32_t imm=((ins>>10)&4095) << (sh?12:0);
        int sub=(ins>>30)&1;
        if (rn==31) { /* SP semantics for base register */
            b(e,0x48);b(e,0x8b);b(e,0x81);d32(e,(uint32_t)offsetof(ARM_VM,sp));
        } else load_x(e,rn);
        if(sf)b(e,0x48);
        b(e,sub?0x2d:0x05);d32(e,imm);
        if(rd==31) {
            b(e,0x48);b(e,0x89);b(e,0x81);d32(e,(uint32_t)offsetof(ARM_VM,sp));
        } else store_x(e,rd);
        return 1;
    }
    /* ADD/SUB shifted register, no flags, shift LSL #0 only initially. */
    if ((ins&0x1f200000)==0x0b000000 && ((ins>>29)&1)==0 && (ins&0x00c0fc00)==0) {
        int sub=(ins>>30)&1;
        load_x(e,rn);
        if (rm==31) {mov_rdx64(e,0);}
        else { b(e,0x48);b(e,0x8b);b(e,0x91);d32(e,(uint32_t)(offsetof(ARM_VM,x)+rm*8)); }
        if(sf) b(e,0x48);
        b(e,sub?0x29:0x01);b(e,0xd0); /* add/sub rax,rdx */
        store_x(e,rd);return 1;
    }
    /* ORR xD,xZR,xM (MOV register), zero shift. */
    if ((ins&0xffe0ffe0)==0xaa0003e0) {
        load_x(e,rm);store_x(e,rd);return 1;
    }
    /* ADR, ADRP generate translated address from source guest PC. */
    if ((ins&0x1f000000)==0x10000000) {
        int is_page=(ins>>31)&1;
        uint64_t imm21=((uint64_t)((ins>>5)&0x7ffff)<<2) | ((ins>>29)&3);
        int64_t offset=sext(imm21,21);
        uint64_t addr=is_page?((at&~UINT64_C(4095))+(uint64_t)(offset<<12)):(at+offset);
        mov_rax64(e,addr);store_x(e,rd);return 1;
    }
    /* LDR/STR unsigned immediate for 64 and 32-bit integer registers. */
    if ((ins&0xbfc00000)==0xb9400000 || (ins&0xbfc00000)==0xb9000000) {
        int bits64=((ins>>30)&1);
        int load=((ins>>22)&1);
        uint32_t offset=((ins>>10)&4095)<<(bits64?3:2);
        if(rn==31){b(e,0x48);b(e,0x8b);b(e,0x81);d32(e,(uint32_t)offsetof(ARM_VM,sp));}
        else load_x(e,rn);
        if(offset){b(e,0x48);b(e,0x05);d32(e,offset);}
        if(load){
            if(bits64){b(e,0x48);b(e,0x8b);b(e,0x10);} /* mov rdx,[rax] */
            else {b(e,0x8b);b(e,0x10);} /* mov edx,[rax] */
            b(e,0x48);b(e,0x89);b(e,0x91);d32(e,(uint32_t)(offsetof(ARM_VM,x)+rd*8));
        } else {
            if(rd==31){mov_rdx64(e,0);}
            else {b(e,0x48);b(e,0x8b);b(e,0x91);d32(e,(uint32_t)(offsetof(ARM_VM,x)+rd*8));}
            if(bits64){b(e,0x48);b(e,0x89);b(e,0x10);} /* mov [rax],rdx */
            else {b(e,0x89);b(e,0x10);}
        }
        return 1;
    }
    return 0;
}
static int bounds(ARM_VM *vm,uint64_t pc) {
    uintptr_t a=(uintptr_t)vm->image;
    return pc>=(uint64_t)a && pc-(uint64_t)a+4<=vm->image_size && ((pc&3)==0);
}
int arm_run(ARM_VM *vm,void *code_cache,size_t code_capacity,ARM_BRIDGE bridge,void *context) {
    if(!vm || !code_cache || code_capacity<1024 || !vm->image)return -1;
    while(vm->steps<ARMPABA_MAX_STEPS){
        if(!bounds(vm,vm->pc)) return -2;
        EMIT e={(uint8_t*)code_cache,0,code_capacity,0};
        start_block(&e,vm);
        uint64_t at=vm->pc;
        uint32_t compiled=0;
        while(compiled<16 && bounds(vm,at)){
            uint32_t ins=get32((uint8_t*)(uintptr_t)at);
            size_t before=e.n;
            if(!translate_one(&e,ins,at) || e.failed){e.n=before;break;}
            ++compiled;at+=4;
        }
        if(compiled){
            b(&e,0xc3); /* ret */
            if(e.failed)return -3;
            /* EFI allocates code cache from EfiLoaderCode pages.
             * Host tests separately mark JIT memory executable. */
            ((void(*)(void))code_cache)();
            vm->pc=at;
            vm->steps+=compiled;
            continue;
        }
        uint32_t ins=get32((uint8_t*)(uintptr_t)vm->pc);
        uint64_t here=vm->pc;
        vm->steps++;
        if ((ins&0xfffffc1f)==0xd65f0000) { /* RET Xn */
            unsigned rn=(ins>>5)&31;
            uint64_t dest= rn==31 ? 0 : vm->x[rn];
            if(dest==ARMPABA_RETURN_SENTINEL) return 0;
            vm->pc=dest;continue;
        }
        if ((ins&0xfffffc1f)==0xd63f0000) { /* BLR Xn */
            unsigned rn=(ins>>5)&31;
            uint64_t dest=rn==31 ? 0 : vm->x[rn];
            vm->x[30]=here+4;
            if(!bridge || bridge(vm,dest,context))return -4;
            vm->pc=vm->x[30];continue;
        }
        if ((ins&0xfffffc1f)==0xd61f0000) { /* BR Xn */
            unsigned rn=(ins>>5)&31;
            vm->pc=rn==31 ? 0 : vm->x[rn];continue;
        }
        if((ins&0xfc000000)==0x14000000 || (ins&0xfc000000)==0x94000000){
            int64_t offset=sext(ins&0x03ffffff,26)*4;
            if((ins&0xfc000000)==0x94000000)vm->x[30]=here+4;
            vm->pc=here+offset;continue;
        }
        if((ins&0x7e000000)==0x34000000){ /* CBZ / CBNZ */
            unsigned rt=ins&31;
            uint64_t v=(rt==31)?0:vm->x[rt];
            if(!((ins>>31)&1))v=(uint32_t)v;
            int neq=(ins>>24)&1;
            if( (!!v)==neq )vm->pc=here+4*sext((ins>>5)&0x7ffff,19);
            else vm->pc=here+4;
            continue;
        }
        vm->last_unsupported_pc=here;
        vm->last_unsupported_insn=ins;
        return -5;
    }
    return -6;
}
