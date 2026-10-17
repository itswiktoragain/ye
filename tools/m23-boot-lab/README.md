# Galaxy M23 boot lab (Ye)

GitHub Actions in this branch runs real AArch64 instructions through QEMU on a Linux runner, using an existing M23 kernel compiled previously in this Ye repository.

The experiment has two independent steps:
- ARM64 smoke code runs in QEMU and emits a real PL011 UART message.
- The compiled Galaxy M23 Linux Image runs under QEMU virt with a tiny AArch64 Linux initramfs containing a PID 1 marker. This can demonstrate kernel/startup behavior if the kernel supports QEMU's virtual devices.

Results are uploaded under the workflow artifact "m23-boot-lab-reports". Reports distinguish guest instruction traces, Linux kernel console messages, and whether PID 1 was reached. A silent kernel is *inconclusive* because the phone kernel may not include QEMU PL011 console support.

**This is not an exact Snapdragon 750G/Galaxy M23 hardware emulator.** Samsung's bootloader, Qualcomm UFS, SDE/LCD, Adreno GPU and firmware services are not implemented in QEMU virt. The earlier Ye kernel artifact is not guaranteed to match the three BOOT images previously flashed.

To analyze the exact BOOT image after it is made available to a runner: python3 tools/m23-boot-lab/m23emu.py run --boot /path/to/boot.img --out reports/boot --seconds 20

No phone flashing, no Android OS download, no device connection, and no USERDATA writes.
