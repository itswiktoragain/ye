# Armpaba: ARM64 boot environment for x86-64 UEFI (prototype)

Armpaba is a real x86-64 Debian Live ISO project, offering a keyboard-controlled
pre-guest boot manager and software-emulated **generic ARM64 UEFI** machine.

**Not a macOS-on-PC solution.** Apple M1/M2/M3/M4/M5 CPU instruction compatibility
is **not** equivalent to emulating Apple Silicon system-on-chip devices, boot ROM,
security coprocessors or Apple firmware. Current Armpaba cannot boot Apple Silicon
macOS installation media. Use generic ARM64 Linux images designed for UEFI/virtio.

## Features

* Bootable x86-64 UEFI Live ISO; installed mode boots from first EFI partition.
* A custom UEFI-inspired **post-firmware** boot screen. Hit **F5** on the Armpaba
  splash to enter the device menu (the key is read after the Linux host boots;
  it is not a native motherboard firmware shortcut).
* ARM64 boot device override: CD/DVD, USB raw disk (read-only), `.iso` file, or
  persistent virtual guest disk. ARM64 media must support QEMU's generic `virt` board.
* ARM64 UEFI firmware via EDK2 and ARM64 dynamic translation via QEMU TCG.
* Physical disk installer using GPT: partition 1 (4 GiB FAT32 EFI, Armpaba boot
  files, Linux filesystem) and partition 2 (ext4, virtual guest disk and ISO files).
* **No unattended disk erasure.** The installer requires an explicit disk path,
  an erase-phrase confirmation, a second confirmation, a random code, and a
  countdown. It refuses targets with mounted filesystems.
* Guest drive is `guest.raw`, a sparse raw disk image on partition 2. This separates
  the guest's disk from Armpaba's boot files. It does **not** eliminate translation
  overhead or ensure native storage performance.

## Source/build

On Debian 13 amd64:

```sh
sudo apt-get install live-build debootstrap squashfs-tools xorriso \
  grub-pc-bin grub-efi-amd64-bin mtools dosfstools
sudo bash build-iso.sh
```

GitHub Actions file: `.github/workflows/armpaba-iso.yml` in repository root.
It automatically builds the ISO from changes on `agent/armpaba`.
Download the `Armpaba-x86_64-ISO` workflow artifact after a successful run.

## Test safely in VM

Use a **disposable blank VMDK/QCOW2 disk** of at least 12 GiB and enable x86-64
UEFI firmware. Boot Armpaba ISO; open **Install** in its menu; select only the
blank disk (e.g. `/dev/vdb`), confirming the multiple erase prompts.
After installation, reboot from the virtual disk and press F5 during the Armpaba
splash. Choose **Create virtual disk**, then **Boot CD / USB** and select a
compatible ARM64 UEFI boot ISO (place it in the `ARMPABA_DATA` filesystem).

For QEMU host-side smoke tests:

```sh
python3 -m unittest discover -s tests -v
bash -n build-iso.sh src/install.sh src/xsession.sh
```

## Known limitations

1. Apple M-series **SoC** emulation is not implemented. No Apple macOS boot support.
2. F5 is implemented in the Armpaba boot environment, not inside the motherboard UEFI.
3. Booting a physical USB/CD drive is a read-only **device image attached to the ARM VM**, not native booting. The media must support ARM64 UEFI plus generic `virt` devices.
4. Real hardware UEFI and GPU compatibility vary. No Secure Boot support in this prototype.
5. ARM64 is software-emulated on x86-64 and substantially slower than native ARM64.
6. Direct writes to the virtual guest disk stay in its data-partition image; guests should not be granted direct access to other host disks.
7. First build requires Debian package downloads; GitHub Actions is intended for ISO generation.

Sources: QEMU arm virt machine docs and arm emulation docs; Debian Live Manual.
