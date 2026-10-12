#!/usr/bin/env python3
"""Generate real PNG fixtures without external dependencies.
Plus one JPEG using Pillow if installed in GitHub Actions.
"""
import struct,zlib
from pathlib import Path
out=Path('tests/fixtures')
out.mkdir(exist_ok=True,parents=True)
def chunk(t,p):
    c=zlib.crc32(t+p)
    return struct.pack('>I',len(p))+t+p+struct.pack('>I',c)
def make(name,w,h,depth,typ,raw,pal=None,trans=None,compress=6):
    ihdr=struct.pack(">IIBBBBB",w,h,depth,typ,0,0,0)
    payload=(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',ihdr))
    if pal is not None:payload+=chunk(b'PLTE',pal)
    if trans is not None:payload+=chunk(b'tRNS',trans)
    packed=zlib.compress(raw,compress)
    # Test multiple IDAT chunks, as real modern PNG encoders can emit them.
    if len(packed)>8:
        payload+=chunk(b'IDAT',packed[:8])+chunk(b'IDAT',packed[8:])
    else:payload+=chunk(b'IDAT',packed)
    payload+=chunk(b'IEND',b'')
    (out/name).write_bytes(payload)
# 16x16 EGA white, black, purple (palette 0,15,5):
make("rgb.png",16,16,8,2,b''.join(bytes([0])+b''.join(
    bytes((255,255,255) if x<4 else (0,0,0) if x<8 else (170,0,170))
    for x in range(16)) for y in range(16)))
# Indexed RGB with transparent paletted pixel -> white
make("indexed.png",16,16,4,3,
     b''.join(bytes([0])+b'\x01'*8 for _ in range(16)),
     pal=bytes((0,0,0,0,0,0)),
     trans=bytes((255,0)))
# Uncompressed zlib DEFLATE (stored blocks), 16x16 grayscale
make("stored.png",16,16,8,0,b''.join(bytes([0])+bytes([0])*16 for _ in range(16)),compress=0)
# RGBA rows filter Sub and Up
make("rgba.png",8,8,8,6,b''.join(bytes([0])+bytes([0,0,0,255])*8 for _ in range(8)))
# A real JPEG is generated with Pillow by workflow.
try:
    from PIL import Image
except ImportError:
    pass
else:
    Image.new('RGB',(32,24),(170,0,170)).save(out/'baseline.jpg','JPEG',quality=90,progressive=False)
    Image.new('RGB',(32,24),(170,0,170)).save(out/'progressive.jpg','JPEG',quality=80,progressive=True)
print('Native decoder test files:',*[p.name for p in out.iterdir()])
