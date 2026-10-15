# Armpaba NativeTranslator (UEFI ARM64->x86-64 JIT) — alpha 0.1

**This is the first real translator implementation, not the old QEMU-based emulator.**

Boot architecture:

    PC x86-64 UEFI firmware
       -> EFI/BOOT/BOOTX64.EFI (Armpaba, a native x86-64 EFI app)
           -> enumerate firmware-readable FAT/optical/USB volumes
           -> load EFI/BOOT/BOOTAA64.EFI ARM64 PE32+ bytes
           -> map PE sections/optional rebasing
           -> decode ARM64 A64 and generate actual x86-64 machine code
           -> run translated code on host CPU
           -> bridge recognized UEFI OutputString back to real PC firmware

This is **dynamic binary translation (DBT)**: the produced x86-64 machine
instructions run directly. QEMU is NOT used by the translator and NO Linux
host runs inside it. QEMU x86 + OVMF are optional only for automated firmware
smoke tests.

## What works now

- Native x86-64 UEFI `.EFI` PE32+ executable (no guest system emulator).
- ARM64 PE32+ image reader and section mapping; DIR64 base relocation handling.
- Runtime x86-64 JIT for a subset of A64: MOVZ/MOVK/MOVN, MOV register,
  ADD/SUB immediate and register, ADR/ADRP, LDR/STR 32/64 unsigned offset, NOP.
- Branch dispatcher: B, BL, BR, BLR, RET, CBZ, CBNZ.
- First whitelist-based ARM64-to-x64 UEFI service bridge: OutputString.
- F5 during Armpaba startup opens UEFI-inspired filesystem selection. Firmware-
  readable CD/USB EFI volumes with `BOOTAA64.EFI` are enumerated along with
  other EFI volumes (their type is not labeled yet).
- Embedded ARM64 EFI test program prints `Armpaba: ARM64 translated code ran!`
  and returns x0=42. Tested through the same translator on a native x86-64 Linux
  host harness, and EFI binaries are validated as PE32+ machine types x64/ARM64.
- Self-contained UEFI El Torito ISO containing both `BOOTX64.EFI` and the ARM64
  test executable in a FAT16 EFI boot image.

## What does not work yet

**Not capable of booting Linux or macOS ARM64 kernels.** The translator's ISA
coverage is narrow and it does not yet implement privilege states, MMUs,
exception levels, page tables, system registers, interrupts, platform buses,
macOS firmware services, or Apple M-series hardware abstractions. Apple
Silicon SOC compatibility (M1/M2/M3/M4/M5) is not present. It does not provide
physical disk image isolation or virtual block device translation yet.

The small x86 EFI program maps guest memory into the host UEFI environment;
**treat guest programs as untrusted code, and run this alpha in a disposable VM**.
JIT code pages use UEFI `EfiLoaderCode` and may be non-executable on firmware
with restrictive memory protection. No Secure Boot signing is included.

## Build with Clang + LLD, not GNU-EFI or QEMU

`bash scripts/build.sh` requires `clang`, `lld-link`, GCC, and Python 3.
It produces:

    build/BOOTX64.EFI                   native PC UEFI translator
    build/BOOTAA64.EFI                  native ARM64 EFI payload
    build/efi.img                       16 MiB FAT16 EFI boot filesystem
    build/Armpaba-NativeTranslator-x86_64.iso   UEFI El Torito ISO

`make-esp.py` and `make-iso.py` are pure Python so no external FAT/ISO tools are
needed. Tests validate x86 machine-code generation and the actual compiled
ARM64 EFI PE file, including UEFI console-call bridging.

Boot the ISO in an x86-64 UEFI VM (OVMF, Secure Boot off) and watch output.
The F5 menu appears during Armpaba's own splash, after the motherboard UEFI
executes BOOTX64.EFI.

## Installation design (not enabled)

The ISO is a standalone bootable translator demo. The destructive physical-disk
installer from the old emulation build is **not enabled** in this first native
translator release, to avoid wiping disks for a stage that cannot yet boot OSes.
Future design: 1st disk partition holds ESP and native x86-64 Armpaba;
other partitions can store ARM64 OS virtual disk files, once ARM64 block I/O
bridges exist. Any destructive installer will have mandatory confirmations.

## Architecture details

- x64 instructions emitted at runtime into `EfiLoaderCode` pages; no QEMU ARM
  machine, no ARM interpreter for linear blocks. Branches and privileged
  behavior currently stop at a controlled dispatcher.
- Dedicated whitelisted firmware service proxies. Unsupported EFI calls
  return failure instead of jumping to an arbitrary host address.
- Deliberately limited to PE images under 8 MiB (mapped images <=32 MiB).
- Tested on host CPU with memory mapping and native invocation of JIT blocks.
- The firmware smoke test in `.github/workflows/armpaba-native.yml` additionally
  attempts OVMF x86 boot and captures legacy COM1 serial output.

References: UEFI 2.10 specification BOOTX64.EFI and BOOTAA64.EFI machine types,
Arm AArch64 instruction encodings, LLVM's PE/COFF ARM64 target.
