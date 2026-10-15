#!/bin/bash
# Explicit, irreversible full-disk installer. Do not auto-select target.
set -Eeuo pipefail
trap 'echo; echo "Installer stopped. If formatting began, disk may be unusable."; exit 1' ERR
if (( EUID != 0 )); then echo 'Run as root'; exit 1; fi
printf '\n========================================================\n'
printf '       ARMPABA  /  DESTRUCTIVE DISK INSTALLER\n'
printf '========================================================\n'
printf 'WARNING: This operation DESTROYS ALL EXISTING DATA\n'
printf 'on the selected physical disk, including all partitions.\n'
printf 'Nothing will be preserved. DO NOT select your main disk\n'
printf 'unless you have tested backups and accept total erasure.\n\n'
lsblk -dn -o PATH,SIZE,MODEL,TRAN,TYPE
printf '\nEnter the WHOLE disk path (for example /dev/vdb): '
read -r target
[[ -b "$target" ]] || { echo 'Not a block device'; exit 2; }
target=$(readlink -f -- "$target")
[[ "$(lsblk -dn -o TYPE -- "$target" | tr -d ' ')" == disk ]] || { echo 'Select a whole disk'; exit 2; }
size=$(blockdev --getsize64 "$target")
(( size >= 12 * 1024 * 1024 * 1024 )) || { echo 'Minimum target disk size: 12 GiB'; exit 2; }
# Detect mounted partitions to avoid destroying the running host system.
if lsblk -nr -o MOUNTPOINTS -- "$target" | grep -q '[^[:space:]]'; then
  echo 'REFUSED: Target disk contains mounted filesystems. Unmount and verify first.'
  exit 2
fi
printf '\nSELECTED TARGET:\n'
lsblk -o NAME,SIZE,FSTYPE,LABEL,MODEL,SERIAL,TRAN,MOUNTPOINTS -- "$target"
printf '\nWARNING #2: ENTIRE DISK %s WILL BE ERASED.\n' "$target"
read -r -p "Type exactly 'ERASE $target' to proceed: " ack
[[ "$ack" == "ERASE $target" ]] || { echo 'Cancelled'; exit 0; }
printf '\nWARNING #3: There is NO UNDO.\n'
read -r -p 'Type exactly I UNDERSTAND THIS ERASES EVERYTHING: ' ack
[[ "$ack" == 'I UNDERSTAND THIS ERASES EVERYTHING' ]] || { echo 'Cancelled'; exit 0; }
code=$(od -An -N4 -tu4 /dev/urandom | tr -d ' ')
code=$(printf '%06d' "$((code % 1000000))")
printf '\nFINAL CONFIRMATION CODE: %s\n' "$code"
read -r -p 'Re-enter the six-digit code to AUTHORIZE ERASE: ' ack
[[ "$ack" == "$code" ]] || { echo 'Cancelled'; exit 0; }
for i in 5 4 3 2 1; do echo "ERASE STARTS IN $i  (Ctrl+C to abort)"; sleep 1; done

find_live() {
  local candidate
  for candidate in /run/live/medium/live /lib/live/mount/medium/live /run/live/medium; do
    if [[ -f "$candidate/filesystem.squashfs" ]]; then echo "$candidate"; return 0; fi
  done
  return 1
}
# Check installation medium BEFORE ANY DISK WRITE.
live=$(find_live) || { echo 'ERROR: Cannot locate Debian live files; aborting without wiping.'; exit 2; }
kernel=$(find "$live" -maxdepth 1 -type f -name 'vmlinuz*' | sort | head -n1)
initrd=$(find "$live" -maxdepth 1 -type f -name 'initrd*' | sort | head -n1)
[[ -n "$kernel" && -n "$initrd" ]] || { echo 'ERROR: Missing live kernel/initrd'; exit 2; }

wipefs -af -- "$target"
parted -s -- "$target" mklabel gpt
parted -s -- "$target" mkpart ARMPABA-EFI fat32 1MiB 4097MiB
parted -s -- "$target" set 1 esp on
parted -s -- "$target" mkpart ARMPABA-DATA ext4 4097MiB 100%
partprobe "$target"
udevadm settle
mapfile -t partitions < <(lsblk -nrpo PATH,TYPE -- "$target" | awk '$2=="part"{print $1}')
(( ${#partitions[@]} == 2 )) || { echo 'Unable to identify two target partitions'; exit 3; }
part1=${partitions[0]}
part2=${partitions[1]}
mkfs.vfat -F 32 -n ARMPABA_EFI "$part1"
mkfs.ext4 -F -L ARMPABA_DATA "$part2"
mkdir -p /mnt/armpaba-inst-esp /mnt/armpaba-inst-data
mount "$part1" /mnt/armpaba-inst-esp
mount "$part2" /mnt/armpaba-inst-data
trap 'umount /mnt/armpaba-inst-esp /mnt/armpaba-inst-data 2>/dev/null || true' EXIT
mkdir -p /mnt/armpaba-inst-esp/live /mnt/armpaba-inst-esp/boot/grub
cp -a "$live"/. /mnt/armpaba-inst-esp/live/
# No automatic guest allocation: user creates sparse raw disk from the menu.
printf 'ARMPABA_DATA / filesystem: guest.raw and optional ARM64 .iso images\n' > /mnt/armpaba-inst-data/README.txt
kb=$(basename "$kernel")
ib=$(basename "$initrd")
cat > /mnt/armpaba-inst-esp/boot/grub/grub.cfg <<EOF
set timeout=2
set default=0
insmod part_gpt
insmod fat
search --no-floppy --label ARMPABA_EFI --set=root
menuentry 'Armpaba - ARM64 compatibility host (press F5 after Linux starts)' {
  linux /live/$kb boot=live live-media=/dev/disk/by-label/ARMPABA_EFI live-media-path=/live quiet
  initrd /live/$ib
}
EOF
grub-install --target=x86_64-efi --efi-directory=/mnt/armpaba-inst-esp \
  --boot-directory=/mnt/armpaba-inst-esp/boot --removable --no-nvram --recheck
sync
printf '\nINSTALL COMPLETE.\n'
printf 'Partition 1: ESP, Armpaba kernel + initramfs + squashfs\n'
printf 'Partition 2: ext4 data, virtual guest disk goes here\n'
printf 'Reboot, then press F5 on Armpaba splash to choose media.\n'
