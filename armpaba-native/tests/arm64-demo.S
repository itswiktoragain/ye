/* Native ARM64 PE/COFF EFI test payload, executed through Armpaba's x86 JIT.
 * Compiled into ARM64 machine instructions, NOT x86-64. */
    .text
    .global EfiMain
EfiMain:
    mov     x20, x30             // preserve link register across UEFI call
    ldr     x2, [x1, #64]         // UEFI SystemTable->ConOut
    ldr     x3, [x2, #8]          // ConOut->OutputString pointer
    mov     x0, x2               // first EFI call argument
    adrp    x1, demo_text
    add     x1, x1, :lo12:demo_text
    blr     x3                   // bridge invokes HOST x64 OutputString
    mov     x30, x20             // restore return sentinel
    movz    x0, #42              // return test code x0=42
    ret
    .section .rdata,"dr"
    .p2align 1
demo_text:
    .short 65,114,109,112,97,98,97,58,32,65,82,77,54,52,32,116,114,97,110,115,108,97,116,101,100,32,99,111,100,101,32,114,97,110,33,13,10,0
