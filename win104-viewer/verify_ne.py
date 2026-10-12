#!/usr/bin/env python3
"""Reject Windows 2/3+ imports from a purported Windows 1.04 binary.

This is a static compatibility gate, not a replacement for booting the real OS.
"""
import sys
from pathlib import Path

def verify(path):
    data=Path(path).read_bytes()
    def u16(i):
        assert i>=0 and i+2<=len(data), "Truncated NE structure"
        return int.from_bytes(data[i:i+2],"little")
    def u32(i):
        assert i>=0 and i+4<=len(data), "Truncated NE structure"
        return int.from_bytes(data[i:i+4],"little")
    assert data[:2]==b"MZ", "DOS MZ stub missing"
    ne=u32(0x3c)
    assert data[ne:ne+2]==b"NE", "New Executable header missing"
    assert data[ne+0x36]==2, "NE target OS must be Windows"
    min_version=u16(ne+0x3e)
    assert min_version<=0x0104, "EXE requires a Windows version newer than 1.04"
    assert min_version>=0x0100, "Unexpected NE Windows version"
    segments=u16(ne+0x1c)
    assert 0<segments<=5, "Windows 1 real mode cannot load too many segments"
    imod_count=u16(ne+0x1e)
    assert 0<imod_count<=8
    module_table=ne+u16(ne+0x28)
    import_table=ne+u16(ne+0x2a)
    seg_table=ne+u16(ne+0x22)
    modules=[]
    for i in range(imod_count):
        idx=import_table+u16(module_table+i*2)
        length=data[idx]
        modules.append(data[idx+1:idx+length+1].decode("ascii").upper())
    assert set(modules)<= {"KERNEL","USER","GDI"}, f"unexpected DLL {modules}"
    forbidden={
        ("USER",411):"AppendMenu (Windows 3.0+)",
        ("USER",415):"CreatePopupMenu (Windows 3.0+)",
        ("KERNEL",353):"lstrcpyn (unavailable under Windows 1.0)",
    }
    found=set()
    for seg in range(segments):
        ent=seg_table+8*seg
        start=u16(ent) << u16(ne+0x32)
        size=u16(ent+2) or 65536
        flags=u16(ent+4)
        if not flags & 0x100:
            continue
        reloc=start+size
        count=u16(reloc)
        for i in range(count):
            addr=reloc+2+i*8
            kind=data[addr+1]&3
            if kind not in (1,2):
                continue
            imod=u16(addr+4)
            assert 1<=imod<=len(modules),"Invalid import module"
            mod=modules[imod-1]
            value=u16(addr+6)
            if kind==1:
                ordname=value
                assert (mod,ordname) not in forbidden, forbidden.get((mod,ordname))
                assert ordname < 200, f"Suspicious {mod} import ordinal {ordname}; validate Windows 1 support"
            else:
                n=import_table+value
                ordname=data[n+1:n+data[n]+1].decode("ascii")
                assert (mod,ordname) not in forbidden
            found.add((mod,ordname))
    if Path(path).name.upper()=="MINWIN.EXE":
        expected={("KERNEL",91)} # CRT startup only; MINWIN has no USER GUI
    else:
        expected={("KERNEL",91),("USER",41),("USER",57)}
    assert expected<=found, f"Win16 runtime imports unexpectedly missing: {expected-found}"
    entry_size=u16(ne+6)
    assert entry_size>0, "No entry table / exported window proc"
    print(f"VALIDATED: {path}")
    print(f"Format: MZ/NE real mode, Windows version 0x{min_version:04x}, segments={segments}")
    print("Modules:",", ".join(modules))
    print("Import ordinals:",", ".join(f"{m}.{n}" for m,n in sorted(found)))
    print("Static Windows 1.04 check passed (not an emulator runtime test).")

if __name__=="__main__":
    if len(sys.argv)!=2:
        sys.exit("Usage: verify_ne.py CHRONO.EXE")
    verify(sys.argv[1])
