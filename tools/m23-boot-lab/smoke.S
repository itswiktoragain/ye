/* Real AArch64 instructions, executing against QEMU virt PL011 UART. */
.section .text
.global _start
.type _start, %function
_start:
    ldr x1, =0x09000000
    adr x2, msg
1:
    ldrb w0, [x2], #1
    cbz w0, 3f
2:
    ldr w3, [x1, #0x18]
    tbnz w3, #5, 2b
    str w0, [x1]
    b 1b
3:
    wfi
    b 3b
.align 2
msg: .asciz "M23BOOTLAB: AARCH64 QEMU TCG AND PL011 READY\r\n"
