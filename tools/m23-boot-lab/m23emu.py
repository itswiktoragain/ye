#!/usr/bin/env python3
"""Run real ARM64 code under QEMU virt. Not a Qualcomm/Samsung emulator."""
import argparse
import gzip
import json
import shutil
import struct
import subprocess
import tempfile
import time
from pathlib import Path

MARKER = 'M23BOOTLAB: AARCH64 QEMU TCG AND PL011 READY'
TRACE_LIMIT = 24 * 1024 * 1024

def newc(name, mode, data=b'', rmajor=0, rminor=0, ino=1):
    name = name.encode() + b'\0'
    fields = (ino, mode, 0, 0, 1, 0, len(data), 0, 0,
              rmajor, rminor, len(name), 0)
    head = b'070701' + b''.join(f'{v:08x}'.encode() for v in fields)
    result = head + name
    result += b'\0' * (-len(result) % 4)
    result += data
    return result + b'\0' * (-len(result) % 4)

def pack_init(binary, destination):
    data = Path(binary).read_bytes()
    if data[:4] != b'\x7fELF' or struct.unpack_from('<H', data, 18)[0] != 183:
        raise ValueError('Expected AArch64 ELF for PID 1')
    archive = (newc('dev', 0o040755)
               + newc('dev/console', 0o020600, rmajor=5, rminor=1, ino=2)
               + newc('dev/null', 0o020666, rmajor=1, rminor=3, ino=3)
               + newc('init', 0o100755, data, ino=4)
               + newc('TRAILER!!!', 0, ino=5))
    p = Path(destination)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(gzip.compress(archive, compresslevel=6))
    print('AArch64 initramfs written:', p)

def extract_android_boot(path, directory):
    data = Path(path).read_bytes()
    if len(data) < 1660 or data[:8] != b'ANDROID!':
        raise ValueError('Not an Android boot image')
    ks, ka, rs, ra, ss, sa, tags, page, version = struct.unpack_from('<9I', data, 8)
    if version != 2 or page not in (2048, 4096, 8192, 16384) or ss:
        raise ValueError('Expected Android boot header v2')
    if struct.unpack_from('<I', data, 1644)[0] != 1660:
        raise ValueError('Incorrect boot header size')
    ko = page
    ro = ko + ((ks + page - 1) // page) * page
    do = ro + ((rs + page - 1) // page) * page
    dtbsize, dtbaddr = struct.unpack_from('<IQ', data, 1648)
    if not (64 <= ks < 160*1024*1024 and 0 < rs < 64*1024*1024
            and 40 <= dtbsize < 16*1024*1024 and do + dtbsize <= len(data)):
        raise ValueError('Invalid/truncated boot payloads')
    kernel = data[ko:ko+ks]
    ramdisk = data[ro:ro+rs]
    if kernel[56:60] != b'ARMd' or data[do:do+4] != bytes.fromhex('d00dfeed'):
        raise ValueError('Invalid ARM64 Image or device tree')
    k = directory / 'Image'
    r = directory / 'ramdisk.cpio.gz'
    k.write_bytes(kernel)
    r.write_bytes(ramdisk)
    (directory / 'original-m23.dtb').write_bytes(data[do:do+dtbsize])
    return k, r

def kernel_info(path):
    p = Path(path)
    with p.open('rb') as f:
        b = f.read(64)
    if len(b) < 64 or b[56:60] != b'ARMd':
        raise ValueError('Kernel does not contain the ARM64 Image magic')
    return {'source_bytes': p.stat().st_size,
            'text_offset': hex(struct.unpack_from('<Q', b, 8)[0]),
            'image_size': struct.unpack_from('<Q', b, 16)[0],
            'flags': hex(struct.unpack_from('<Q', b, 24)[0])}

def execute(args, smoke=False):
    if not shutil.which('qemu-system-aarch64'):
        raise RuntimeError('Missing QEMU AArch64 executable')
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='m23lab-') as td:
        if smoke:
            kernel, ramdisk = Path(args.elf).resolve(), None
            kind = 'bare-metal AArch64 smoke ELF'
            info = {}
        elif args.boot:
            kernel, ramdisk = extract_android_boot(args.boot, Path(td))
            kind = 'provided boot image'
            info = kernel_info(kernel)
        else:
            kernel = Path(args.kernel).resolve()
            ramdisk = Path(args.ramdisk).resolve() if args.ramdisk else None
            kind = 'compiled M23 community kernel from Ye artifact'
            info = kernel_info(kernel)
        cmd = ['qemu-system-aarch64', '-M', 'virt,gic-version=3', '-accel', 'tcg',
               '-cpu', 'cortex-a76', '-smp', '1' if smoke else '2',
               '-m', '512' if smoke else '2048', '-display', 'none',
               '-monitor', 'none', '-no-reboot',
               '-serial', 'file:' + str(out / 'serial.log'),
               '-D', str(out / 'trace.log'), '-d', 'in_asm,int,guest_errors,unimp,cpu_reset',
               '-kernel', str(kernel)]
        if ramdisk:
            cmd += ['-initrd', str(ramdisk)]
        if not smoke:
            cmd += ['-append',
                    'console=ttyAMA0,115200 earlycon=pl011,mmio32,0x09000000 '
                    'loglevel=8 ignore_loglevel rdinit=/init panic=-1']
        (out/'qemu-command.txt').write_text(' '.join(cmd) + '\n')
        start = time.monotonic()
        reason = 'test-timeout'
        with (out/'qemu-stderr.log').open('wb') as errors:
            p = subprocess.Popen(cmd, stdout=errors, stderr=subprocess.STDOUT)
            try:
                while p.poll() is None:
                    time.sleep(.15)
                    if smoke and (out/'serial.log').exists():
                        if MARKER in (out/'serial.log').read_text(errors='replace'):
                            reason = 'smoke-marker-observed'
                            break
                    if (out/'trace.log').exists() and (out/'trace.log').stat().st_size > TRACE_LIMIT:
                        reason = 'trace-limit'
                        break
                    if time.monotonic() - start >= args.seconds:
                        break
                if p.poll() is not None:
                    reason = 'qemu-exited'
            finally:
                if p.poll() is None:
                    p.terminate()
                    try:
                        p.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        p.kill()
                        p.wait(timeout=3)
        uart = (out/'serial.log').read_text(errors='replace') if (out/'serial.log').exists() else ''
        errors = (out/'qemu-stderr.log').read_text(errors='replace')
        tracepath = out/'trace.log'
        tracebytes = tracepath.read_bytes() if tracepath.exists() else b''
        tracehead = tracebytes[:64000].decode('utf-8', 'replace')
        tracetail = tracebytes[-64000:].decode('utf-8', 'replace')
        (out/'trace-head.log').write_text(tracehead)
        (out/'trace-tail.log').write_text(tracetail)
        tracepath.unlink(missing_ok=True)
        report = {
            'mode': 'smoke' if smoke else 'M23-kernel-on-generic-QEMU-virt',
            'kind': kind, 'kernel': info,
            'seconds': round(time.monotonic()-start, 2), 'stop_reason': reason,
            'aarch64_uart_smoke_marker': MARKER in uart,
            'guest_instructions_disassembled': 'IN:' in tracehead,
            'linux_kernel_console_observed': 'Linux version' in uart or 'Booting Linux on physical CPU' in uart,
            'guest_pid1_marker': 'M23BOOTLAB: PID1 REACHED' in uart,
            'guest_kernel_panic_observed': 'Kernel panic' in uart,
            'uart_tail': uart[-3000:], 'qemu_errors_tail': errors[-1000:],
            'trace_tail_excerpt': tracetail[-3000:],
            'limitations': 'Generic QEMU virt, NOT an SM7225/M23 emulator; no Samsung bootloader, UFS, SDE, or Adreno. Serial silence can be missing PL011 support.'
        }
        (out/'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2), flush=True)
        if smoke and not report['aarch64_uart_smoke_marker']:
            raise RuntimeError('Real AArch64 UART smoke test failed; inspect logs')

def main():
    ap = argparse.ArgumentParser()
    commands = ap.add_subparsers(dest='command', required=True)
    p = commands.add_parser('pack-init')
    p.add_argument('--binary', required=True)
    p.add_argument('--out', required=True)
    p = commands.add_parser('smoke')
    p.add_argument('--elf', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--seconds', type=int, default=5)
    p = commands.add_parser('run')
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument('--kernel')
    g.add_argument('--boot')
    p.add_argument('--ramdisk')
    p.add_argument('--out', required=True)
    p.add_argument('--seconds', type=int, default=20)
    a = ap.parse_args()
    if a.command == 'pack-init':
        pack_init(a.binary, a.out)
    else:
        execute(a, a.command == 'smoke')

if __name__ == '__main__':
    main()
