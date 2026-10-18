# W2K64 AMD64 community prototype - boot preservation fix

**October 19, 2026:** The old nine-part W2K64_AMD64_Complete.zip archive was reported NOT BOOTING. An intact ZIP and a valid El Torito catalog do not prove that Windows Setup will start. Do not consider the old archive boot-tested.

The original script rebuilt the whole ISO filesystem with libarchive, potentially altering the filenames/layout that older Windows loaders need. The replacement build.py now uses xorriso to import your ORIGINAL XP x64 ISO, inserts prototype extras in a separate /W2K64 folder, and performs '-boot_image any replay'. It checks the original El Torito BIOS boot image and all original installation file bytes. It ABORTS if the verification fails.

GitHub Actions includes a small open-source bootloader smoke test of this preservation technique. This is NOT yet a successful XP x64 installer test.

This remains Windows XP Professional AMD64 (NT 5.2) at its core, not a genuine Windows 2000 NT 5.0 x64 port. XP x64's native WOW64 supports 32-bit applications but does not supply NTVDM or native DOS/Win16 support. The graphics and standalone About app are prototypes and are not automatically wired into the system boot image or winver.

## Ubuntu build from your two-part 7z

Both the 001 and 002 parts must be in Downloads:

~~~bash
sudo apt update
sudo apt install -y git python3 python3-pil fonts-dejavu-core mingw-w64 p7zip-full xorriso qemu-system-x86 qemu-utils
git clone https://github.com/itswiktoragain/ye.git
cd ye/w2k64-amd64

python3 build.py \
  --xp-7z "$HOME/Downloads/Windows XP Pro x64 English.7z.001" \
  --win2000-iso "$HOME/Downloads/Windows 2000 Professional.ISO" \
  --output "$HOME/Downloads/W2K64_FIXED"
~~~

If already cloned:

~~~bash
cd ~/ye && git pull
cd w2k64-amd64
~~~

If you already extracted XP x64 to an ISO, use --xp-iso /path/to/original.iso instead of --xp-7z.

The generated ISO is W2K64_AMD64_Community_Prototype_FIXED.iso. By default the builder also creates a complete .tar.xz, 64MiB numbered parts, and SHA256SUMS.txt. Add --no-archive for a quick ISO-only diagnostic run.

Reassemble numbered parts if needed:

~~~bash
cat W2K64_AMD64_Community_Prototype_FIXED.tar.xz.part[0-9][0-9] \
  > W2K64_AMD64_Community_Prototype_FIXED.tar.xz
tar -xJf W2K64_AMD64_Community_Prototype_FIXED.tar.xz
~~~

## QEMU test - use legacy BIOS

First test the ORIGINAL XP x64 ISO with the same settings. If it does not boot, an ISO repack alone cannot fix the VM incompatibility.

~~~bash
qemu-img create -f qcow2 "$HOME/Downloads/w2k64-test.qcow2" 20G
qemu-system-x86_64 -M pc -m 2048 -smp 2 -cpu qemu64 \
  -drive file="$HOME/Downloads/w2k64-test.qcow2",format=qcow2,if=ide \
  -cdrom "$HOME/Downloads/W2K64_FIXED/W2K64_AMD64_Community_Prototype_FIXED.iso" \
  -boot order=d -net none
~~~

The virtual CD must be first in boot order; press a key if it says to boot from CD. Use SeaBIOS (legacy BIOS), not UEFI-only OVMF. Avoid AHCI, NVMe or VirtIO disks until storage drivers are supplied. If a STOP 0x000000A5 blue screen appears *after Setup starts*, investigate XP x64 ACPI incompatibility instead of the boot record.

Inspect the image's boot catalog:

~~~bash
xorriso -indev "$HOME/Downloads/W2K64_FIXED/W2K64_AMD64_Community_Prototype_FIXED.iso" -report_el_torito plain
~~~

The standalone boot replay smoke test is at w2k64-amd64/tests/boot_replay.sh and runs through .github/workflows/w2k64-boot-smoke.yml. It proves only that an elementary BIOS test image survives the media-replay process, not that proprietary XP installation media has successfully booted.

Never install this experimental, obsolete Windows image on an important disk. Use an offline disposable VM first.
