#!/usr/bin/env bash
# Public-domain test of xorriso's El Torito BIOS boot replay.
# No Microsoft content is used; this does NOT prove that Windows XP setup boots.
set -euo pipefail
cd "$(dirname "$0")/../.."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/src" "$TMP/extras"
cat > "$TMP/boot.asm" <<'ASM'
BITS 16
org 0
mov dx, 0x00e9
%macro emit 1
    mov al, %1
    out dx, al
%endmacro
emit 'B'
emit 'O'
emit 'O'
emit 'T'
emit 'P'
emit 'A'
emit 'S'
emit 'S'
emit 10
cli
loop_forever:
    hlt
    jmp loop_forever
times 2048 - ($ - $$) db 0
ASM
nasm -f bin "$TMP/boot.asm" -o "$TMP/src/BOOTIMG.BIN"
echo TEST > "$TMP/src/TEST.TXT"
echo "Prototype additions" > "$TMP/extras/README.TXT"
xorriso -as mkisofs -J -R -iso-level 3 -V TEST_BIOS \
    -b BOOTIMG.BIN -c BOOT.CAT -no-emul-boot -boot-load-size 4 \
    -o "$TMP/original.iso" "$TMP/src"
xorriso -indev "$TMP/original.iso" -outdev "$TMP/replay.iso" \
    -map "$TMP/extras" /W2K64 -boot_image any replay -commit -end

python3 - "$TMP/original.iso" "$TMP/replay.iso" <<'PY'
import sys
sys.path.insert(0, 'w2k64-amd64')
from build import inspect_bios_boot
from iso9660 import ISO9660
a, b = sys.argv[1:]
assert inspect_bios_boot(a) == inspect_bios_boot(b), "Boot sector was altered"
original = ISO9660(a)
result = ISO9660(b)
assert "BOOTIMG.BIN" in result.entries
assert "W2K64/README.TXT" in result.entries
original.close()
result.close()
print("Structural check passed: boot bytes retained, extra files present")
PY

set +e
timeout 20s qemu-system-i386 -m 64 -machine pc -boot order=d \
    -cdrom "$TMP/replay.iso" -display none -monitor none -serial none \
    -no-reboot -net none -debugcon "file:$TMP/debug.txt" \
    -global isa-debugcon.iobase=0xe9 >/dev/null 2>&1
STATUS=$?
set -e
cat "$TMP/debug.txt" || true
grep -q BOOTPASS "$TMP/debug.txt"
echo "Boot smoke test passed in QEMU (QEMU exit code $STATUS)"
