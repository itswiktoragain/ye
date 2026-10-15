#ifndef ARMPABA_ARMJIT_H
#define ARMPABA_ARMJIT_H
#include <stdint.h>
#include <stddef.h>
#define ARMPABA_MAX_STEPS 100000U
#define ARMPABA_JIT_CAP 16384U
#define ARMPABA_RETURN_SENTINEL (~(uint64_t)0)

typedef struct {
    uint64_t x[31];
    uint64_t sp;
    uint64_t pc;
    uint64_t steps;
    uint64_t last_unsupported_pc;
    uint32_t last_unsupported_insn;
    uint8_t *image;
    uint64_t image_size;
} ARM_VM;

typedef int (*ARM_BRIDGE)(ARM_VM *vm, uint64_t target, void *context);

/* Native x86-64 machine-code generator, not a QEMU system emulator.
 * Generates directly executable x86-64 from supported A64 instructions. */
int arm_run(ARM_VM *vm, void *code_cache, size_t code_capacity,
            ARM_BRIDGE bridge, void *context);
#endif
