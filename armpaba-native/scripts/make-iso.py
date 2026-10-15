#!/usr/bin/env python3
"""Construct a UEFI El Torito ISO9660 CD-ROM from a FAT16 EFI boot image.
No OS/emulator is bundled; firmware loads BOOTX64.EFI from this image.
"""
import datetime
import pathlib
import struct
import sys

BLK=2048
CAT_LBA=19
PATH_LBA=20
PATH_BE_LBA=21
ROOT_LBA=22
ESP_LBA=32

def both16(v):return struct.pack('<H',v)+struct.pack('>H',v)
def both32(v):return struct.pack('<I',v)+struct.pack('>I',v)

def rec(extent,size,name,folder=False):
    n=len(name)
    length=33+n+(1 if n%2==0 else 0)
    x=bytearray(length)
    x[0]=length
    x[2:10]=both32(extent)
    x[10:18]=both32(size)
    dt=datetime.datetime(2026,10,15,0,0,0)
    x[18:25]=bytes((dt.year-1900,dt.month,dt.day,dt.hour,dt.minute,dt.second,0))
    x[25]=2 if folder else 0
    x[28:32]=both16(1)
    x[32]=n
    x[33:33+n]=name
    return x

def build(iso_path,esp_path):
    esp=pathlib.Path(esp_path).read_bytes()
    assert len(esp)%BLK==0 and len(esp)<=32768*512
    sectors=(len(esp)+BLK-1)//BLK
    total=ESP_LBA+sectors
    blob=bytearray(total*BLK)
    pvd=memoryview(blob)[16*BLK:17*BLK]
    pvd[0:7]=b'\x01CD001\x01'
    pvd[8:40]=b'ARMPABA'.ljust(32,b' ')
    pvd[40:72]=b'ARMPABA_NATIVE'.ljust(32,b' ')
    pvd[80:88]=both32(total)
    pvd[120:124]=both16(1)
    pvd[124:128]=both16(1)
    pvd[128:132]=both16(BLK)
    pvd[132:140]=both32(10)
    pvd[140:144]=struct.pack('<I',PATH_LBA)
    pvd[148:152]=struct.pack('>I',PATH_BE_LBA)
    pvd[156:190]=rec(ROOT_LBA,BLK,b'\x00',True)
    pvd[881]=1
    br=memoryview(blob)[17*BLK:18*BLK]
    br[0:7]=b'\x00CD001\x01'
    br[7:39]=b'EL TORITO SPECIFICATION'.ljust(32,b' ')
    br[71:75]=struct.pack('<I',CAT_LBA)
    tv=memoryview(blob)[18*BLK:19*BLK]
    tv[0:7]=b'\xffCD001\x01'
    cat=memoryview(blob)[CAT_LBA*BLK:(CAT_LBA+1)*BLK]
    validation=bytearray(32)
    validation[0]=1
    validation[1]=0xEF  # UEFI boot platform ID
    validation[4:28]=b'ARMPABA EFI'.ljust(24,b' ')
    validation[30]=0x55
    validation[31]=0xAA
    checksum=sum(struct.unpack('<16H',validation))&0xFFFF
    struct.pack_into('<H',validation,28,(-checksum)&0xFFFF)
    assert (sum(struct.unpack('<16H',validation))&0xFFFF)==0
    cat[:32]=validation
    cat[32]=0x88 # bootable entry, no emulation
    cat[33]=0x00 # no disk emulation
    struct.pack_into('<H',cat,38,len(esp)//512) # load/partition length in 512-byte sectors
    struct.pack_into('<I',cat,40,ESP_LBA)
    # Root path table
    le=bytearray(10);le[0]=1;le[2:6]=struct.pack('<I',ROOT_LBA);le[6:8]=struct.pack('<H',1)
    be=bytearray(10);be[0]=1;be[2:6]=struct.pack('>I',ROOT_LBA);be[6:8]=struct.pack('>H',1)
    blob[PATH_LBA*BLK:PATH_LBA*BLK+10]=le
    blob[PATH_BE_LBA*BLK:PATH_BE_LBA*BLK+10]=be
    contents=b''.join((
        rec(ROOT_LBA,BLK,b'\x00',True),
        rec(ROOT_LBA,BLK,b'\x01',True),
        rec(CAT_LBA,BLK,b'BOOT.CAT;1'),
        rec(ESP_LBA,len(esp),b'EFI.IMG;1'),
    ))
    blob[ROOT_LBA*BLK:ROOT_LBA*BLK+len(contents)]=contents
    blob[ESP_LBA*BLK:ESP_LBA*BLK+len(esp)]=esp
    pathlib.Path(iso_path).write_bytes(blob)
    print(f'ISO9660 UEFI El Torito: {iso_path}, {len(blob)} bytes; UEFI image at LBA {ESP_LBA}')

if __name__=='__main__':
    if len(sys.argv)!=3:raise SystemExit('make-iso.py OUTPUT.iso ESP.img')
    build(sys.argv[1],sys.argv[2])
