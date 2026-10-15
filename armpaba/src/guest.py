#!/usr/bin/env python3
"""QEMU ARM64 guest command construction. No host shell interpolation."""
import os
import pathlib
import shutil
import subprocess

STORE = pathlib.Path('/mnt/armpaba-store')
DATA_LABEL = 'ARMPABA_DATA'


def get_store():
    STORE.mkdir(parents=True, exist_ok=True)
    if not os.path.ismount(STORE):
        # Do not accidentally write a huge disk image into the live root overlay.
        result = subprocess.run(['mount', '-o', 'noatime', 'LABEL=' + DATA_LABEL, str(STORE)],
                                capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError('Persistent guest partition not mounted. Install Armpaba first. ' + result.stderr.strip())
    return STORE


def firmware_args(store):
    code = pathlib.Path('/usr/share/AAVMF/AAVMF_CODE.fd')
    vars_template = pathlib.Path('/usr/share/AAVMF/AAVMF_VARS.fd')
    vars_dest = store / 'aavmf-vars.fd'
    if code.is_file() and vars_template.is_file():
        if not vars_dest.exists():
            shutil.copyfile(vars_template, vars_dest)
        return ['-drive', f'if=pflash,format=raw,readonly=on,file={code}',
                '-drive', f'if=pflash,format=raw,file={vars_dest}']
    legacy = pathlib.Path('/usr/share/qemu-efi-aarch64/QEMU_EFI.fd')
    if legacy.is_file():
        return ['-bios', str(legacy)]
    raise RuntimeError('ARM64 UEFI firmware not installed (qemu-efi-aarch64).')


def new_guest_disk(store, size_gib=64):
    if not 8 <= size_gib <= 1024:
        raise ValueError('Disk size must be between 8 and 1024 GiB')
    p = store / 'guest.raw'
    if p.exists():
        raise FileExistsError(f'Guest disk already exists: {p}')
    # Raw sparse disk: file consumes only allocated blocks, no fake native-speed promises.
    with p.open('xb') as f:
        f.truncate(size_gib * 1024**3)
    return p


def build_command(store, source=None, cpus=4, memory_mib=4096):
    if not 1 <= cpus <= 16 or not 1024 <= memory_mib <= 32768:
        raise ValueError('Unsupported guest CPU/RAM settings')
    disk = store / 'guest.raw'
    if not disk.is_file():
        raise FileNotFoundError('Missing guest.raw. Create a guest disk first.')
    args = ['qemu-system-aarch64', '-machine', 'virt,highmem=on',
            '-cpu', 'max', '-accel', 'tcg,thread=multi', '-smp', str(cpus),
            '-m', str(memory_mib), '-boot', 'menu=on',
            '-device', 'virtio-gpu-pci', '-device', 'qemu-xhci',
            '-device', 'usb-kbd', '-device', 'usb-tablet',
            '-netdev', 'user,id=net0', '-device', 'virtio-net-pci,netdev=net0',
            '-drive', f'if=none,id=guestdisk,format=raw,file={disk},cache=writeback',
            '-device', 'virtio-blk-pci,drive=guestdisk,bootindex=2',
            '-device', 'virtio-scsi-pci,id=scsi0',
            '-display', 'gtk,gl=off']
    args.extend(firmware_args(store))
    if source:
        kind, dev = source
        dev = pathlib.Path(dev)
        if kind == 'iso':
            if not dev.is_file():
                raise FileNotFoundError(dev)
            args += ['-drive', f'if=none,id=cd0,media=cdrom,readonly=on,format=raw,file={dev}',
                     '-device', 'scsi-cd,drive=cd0,bus=scsi0.0,bootindex=1']
        elif kind == 'cd':
            if not str(dev).startswith('/dev/sr') or not dev.is_block_device():
                raise ValueError('Not an optical drive device')
            args += ['-drive', f'if=none,id=cd0,media=cdrom,readonly=on,format=raw,file={dev}',
                     '-device', 'scsi-cd,drive=cd0,bus=scsi0.0,bootindex=1']
        elif kind == 'usb':
            # The host's USB installer is passed through as READ ONLY raw storage.
            if not str(dev).startswith('/dev/') or not dev.is_block_device():
                raise ValueError('Not a block device')
            args += ['-drive', f'if=none,id=usb0,format=raw,readonly=on,file={dev}',
                     '-device', 'usb-storage,drive=usb0,bootindex=1']
        else:
            raise ValueError('Unknown boot source')
    return args


def discover_sources():
    """Return only known optical and USB whole-device paths."""
    import json
    p = subprocess.run(['lsblk', '-J', '-p', '-d', '-o', 'PATH,TYPE,TRAN,MODEL,SIZE'],
                       check=True, capture_output=True, text=True)
    out = []
    for item in json.loads(p.stdout).get('blockdevices', []):
        path = item.get('path') or ''
        typ = item.get('type')
        tran = item.get('tran')
        if (typ == 'rom' and path.startswith('/dev/sr')):
            out.append(('cd', path, f'CD/DVD: {path}  {item.get("model", "")}'))
        elif typ == 'disk' and tran == 'usb' and path.startswith('/dev/'):
            out.append(('usb', path, f'USB: {path}  {item.get("size", "")}  {item.get("model", "")}'))
    return out
