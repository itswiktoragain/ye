#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$ROOT"
CLANG=${CLANG:-clang}
LLD_LINK=${LLD_LINK:-lld-link}
for bin in "$CLANG" "$LLD_LINK" gcc python3; do command -v "$bin" >/dev/null || { echo "Missing build tool $bin"; exit 1; }; done
mkdir -p build/obj build/iso/boot
CFLAGS=(-std=c11 -O2 -ffreestanding -fno-builtin -fno-stack-protector -mno-stack-arg-probe -mno-red-zone -fshort-wchar -Wall -Wextra)
"$CLANG" -target x86_64-pc-win32 "${CFLAGS[@]}" -c src/main.c -o build/obj/main.obj
"$CLANG" -target x86_64-pc-win32 "${CFLAGS[@]}" -c src/armjit.c -o build/obj/armjit.obj
"$LLD_LINK" /subsystem:efi_application /machine:x64 /entry:efi_main /nodefaultlib \
  /out:build/BOOTX64.EFI build/obj/main.obj build/obj/armjit.obj
"$CLANG" -target aarch64-windows-msvc -c tests/arm64-demo.S -o build/obj/demo.obj
"$LLD_LINK" /subsystem:efi_application /machine:arm64 /entry:EfiMain /nodefaultlib \
  /out:build/BOOTAA64.EFI build/obj/demo.obj

gcc -std=c11 -Wall -Wextra -Werror -O2 src/armjit.c tests/host.c -o build/host-test
./build/host-test
gcc -std=c11 -Wall -Wextra -Werror -O2 src/armjit.c tests/demo-test.c -o build/demo-test
./build/demo-test build/BOOTAA64.EFI

python3 scripts/make-esp.py build/efi.img build/BOOTX64.EFI build/BOOTAA64.EFI
python3 scripts/make-iso.py build/Armpaba-NativeTranslator-x86_64.iso build/efi.img
(cd build && sha256sum Armpaba-NativeTranslator-x86_64.iso > Armpaba-NativeTranslator-x86_64.iso.sha256)
ls -lh build/Armpaba-NativeTranslator-x86_64.iso build/BOOTX64.EFI build/BOOTAA64.EFI
