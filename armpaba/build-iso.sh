#!/usr/bin/env bash
# Build a genuinely bootable Debian Live based Armpaba x86_64 ISO.
set -Eeuo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
WORK=${ARMPABA_BUILD_WORK:-"$ROOT/.work"}
OUT=${ARMPABA_OUTPUT:-"$ROOT/Armpaba-x86_64.iso"}
[[ $(id -u) == 0 ]] || { echo 'Run with sudo: sudo ./build-iso.sh'; exit 1; }
for bin in lb mksquashfs grub-mkrescue xorriso; do
  command -v "$bin" >/dev/null || { echo "Missing $bin. Install live-build squashfs-tools grub-common xorriso."; exit 1; }
done
mkdir -p "$WORK"
cd "$WORK"
if [[ ! -e config/bootstrap ]]; then
  lb config --distribution trixie --architecture amd64 \
    --binary-images iso-hybrid --bootloaders 'grub-pc grub-efi' \
    --debian-installer none --apt-recommends false \
    --archive-areas main --iso-application Armpaba \
    --iso-publisher 'Armpaba project' --bootappend-live 'boot=live components' \
    --uefi-secure-boot disable
fi
mkdir -p config/package-lists config/includes.chroot/usr/local/lib/armpaba \
  config/includes.chroot/etc/systemd/system config/hooks/live
cat > config/package-lists/armpaba.list.chroot <<'PKG'
linux-image-amd64
live-boot
live-config
systemd-sysv
python3
qemu-system-arm
qemu-system-gui
qemu-efi-aarch64
qemu-utils
xserver-xorg-core
xserver-xorg-input-libinput
xserver-xorg-video-fbdev
xinit
xauth
openbox
parted
fdisk
util-linux
udev
dosfstools
e2fsprogs
grub-efi-amd64-bin
grub2-common
ca-certificates
fonts-dejavu-core
usbutils
PKG
install -m 0755 "$ROOT/src/bootmenu.py" config/includes.chroot/usr/local/lib/armpaba/bootmenu.py
install -m 0755 "$ROOT/src/guest.py" config/includes.chroot/usr/local/lib/armpaba/guest.py
install -m 0755 "$ROOT/src/launch.py" config/includes.chroot/usr/local/lib/armpaba/launch.py
install -m 0755 "$ROOT/src/xsession.sh" config/includes.chroot/usr/local/lib/armpaba/xsession.sh
install -m 0755 "$ROOT/src/install.sh" config/includes.chroot/usr/local/lib/armpaba/install.sh
install -m 0644 "$ROOT/src/armpaba-menu.service" config/includes.chroot/etc/systemd/system/armpaba-menu.service
cat > config/hooks/live/010-armpaba-enable.hook.chroot <<'HOOK'
#!/bin/sh
set -eu
systemctl enable armpaba-menu.service
# The Armpaba boot interface occupies tty1.
systemctl mask getty@tty1.service
HOOK
chmod +x config/hooks/live/010-armpaba-enable.hook.chroot
lb build
ISO=$(find . -maxdepth 1 -type f -name '*.iso' | head -n 1)
[[ -n "$ISO" && -s "$ISO" ]] || { echo 'ERROR: live-build produced no ISO'; exit 1; }
mkdir -p "$(dirname "$OUT")"
cp -f "$ISO" "$OUT"
sha256sum "$OUT" > "$OUT.sha256"
echo "Successfully built $OUT"
