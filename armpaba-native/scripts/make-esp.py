#!/usr/bin/env python3
"""Create a FAT16 El Torito EFI System Partition image using the stdlib only.

No emulator images or operating system disk image is included. BOOTX64.EFI
is Armpaba's x86-64 UEFI translator; BOOTAA64.EFI is an ARM64 demo payload.
"""
import pathlib
import struct
import sys

SECTOR=512
SECTORS=32768    # 16 MiB, FAT16
FAT_SECTORS=128
RESERVED=1
NFATS=2
ROOT_ENTRIES=512
ROOT_START=RESERVED + NFATS*FAT_SECTORS
ROOT_SECTORS=(ROOT_ENTRIES*32+SECTOR-1)//SECTOR
DATA_START=ROOT_START+ROOT_SECTORS


def build(out, efi64, arm64):
    buf=bytearray(SECTOR*SECTORS)
    boot=memoryview(buf)[:512]
    boot[:3]=b'\xeb\x3c\x90'
    boot[3:11]=b'ARMPABA '
    struct.pack_into('<HBHBHHBHHHII',boot,11,512,1,1,2,512,32768,0xF8,128,32,64,0,0)
    boot[36]=0x80
    boot[38]=0x29
    struct.pack_into('<I',boot,39,0xA64A64A6)
    boot[43:54]=b'ARMPABA    '
    boot[54:62]=b'FAT16   '
    boot[510:512]=b'\x55\xaa'
    fat=[0]*((FAT_SECTORS*SECTOR)//2)
    fat[0]=0xFFF8
    fat[1]=0xFFFF
    cluster=2

    def allocate(data):
        nonlocal cluster
        count=max(1,(len(data)+SECTOR-1)//SECTOR)
        start=cluster
        if start+count >= len(fat):
            raise ValueError('FAT exhausted')
        for i in range(count):
            c=cluster+i
            fat[c]=0xFFFF if i==count-1 else c+1
            a=(DATA_START+c-2)*SECTOR
            buf[a:a+len(data[i*SECTOR:(i+1)*SECTOR])]=data[i*SECTOR:(i+1)*SECTOR]
        cluster+=count
        return start

    def direntry(name, attr, first, size=0):
        assert len(name)==11
        e=bytearray(32)
        e[:11]=name
        e[11]=attr
        struct.pack_into('<H',e,26,first)
        struct.pack_into('<I',e,28,size)
        return e

    efi_cluster=allocate(bytes(SECTOR))
    boot_cluster=allocate(bytes(SECTOR))
    x64=pathlib.Path(efi64).read_bytes()
    aa64=pathlib.Path(arm64).read_bytes()
    x64_cluster=allocate(x64)
    aa64_cluster=allocate(aa64)
    def write_dir(clus, entries):
        address=(DATA_START+clus-2)*SECTOR
        block=b''.join(entries)
        buf[address:address+len(block)]=block
    write_dir(efi_cluster,[direntry(b'.          ',0x10,efi_cluster),direntry(b'..         ',0x10,0),direntry(b'BOOT       ',0x10,boot_cluster)])
    write_dir(boot_cluster,[direntry(b'.          ',0x10,boot_cluster),direntry(b'..         ',0x10,efi_cluster),direntry(b'BOOTX64 EFI',0x20,x64_cluster,len(x64)),direntry(b'BOOTAA64EFI',0x20,aa64_cluster,len(aa64))])
    buf[ROOT_START*SECTOR:ROOT_START*SECTOR+32]=direntry(b'EFI        ',0x10,efi_cluster)
    fatbytes=struct.pack('<'+str(len(fat))+'H',*fat)
    for i in range(NFATS):
        start=(RESERVED+i*FAT_SECTORS)*SECTOR
        buf[start:start+len(fatbytes)]=fatbytes
    pathlib.Path(out).write_bytes(buf)
    print(f'Generated {out}: FAT16, {len(x64)} bytes x64 translator, {len(aa64)} bytes A64 EFI demo')

if __name__=='__main__':
    if len(sys.argv)!=4: raise SystemExit('Usage: make-esp.py OUTPUT BOOTX64.EFI BOOTAA64.EFI')
    build(*sys.argv[1:])
