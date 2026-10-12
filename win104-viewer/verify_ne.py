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

    # Windows 1.x moves window code/data independently. The callback MUST
    # reload DGROUP from a relocatable segment, or painting/moving windows
    # may interpret USER.EXE's DS as application variables. Watcom's
    # __loadds is what emits the segment reload in this build.
    #
    # Verify machine code AND the loader relocation, not C declarations.
    name=Path(path).name.upper()
    if name in ("CHRONO.EXE","SDKPROBE.EXE"):
        entry=ne+u16(ne+4)
        bundle_size=data[entry]
        bundle_seg=data[entry+1]
        assert bundle_size>=1 and bundle_seg not in (0,255), "Unexpected Win1 callback export"
        entry_offset=u16(entry+3)   # exported ordinal 1
        code_ent=seg_table+8*(bundle_seg-1)
        code_pos=u16(code_ent)<<u16(ne+0x32)
        code_size=u16(code_ent+2)
        code_flags=u16(code_ent+4)
        assert code_flags&0x0010, "Window callback code must be moveable in Win1"
        assert not(code_flags&0x1000), "Window callback code must not be discardable"
        head=data[code_pos+entry_offset:code_pos+entry_offset+48]
        # Watcom's DGROUP restore: MOV AX,<relocatable DGROUP>; MOV DS,AX.
        pattern=b"\xb8\xff\xff\x8e\xd8"
        pattern_index=head.find(pattern)
        assert pattern_index>=0, "Window callback does not reload its data segment"
        # A compiler stack-check call before DS is restored can read USER.EXE
        # memory and corrupt the caller. Require straight-line setup only.
        setup=head[:pattern_index]
        assert b"\xe8" not in setup and b"\x9a" not in setup, (
            "Callback calls a helper before DGROUP reload (unsafe stack checker)"
        )
        imm_offset=entry_offset+pattern_index+1
        reloc=code_pos+code_size
        reloc_count=u16(reloc)
        valid_reloc=False
        for j in range(reloc_count):
            loc=reloc+2+j*8
            if (data[loc]==2 and (data[loc+1]&3)==0
                and u16(loc+2)==imm_offset and u16(loc+4)==2):
                valid_reloc=True
                break
        assert valid_reloc, "DGROUP reload instruction lacks a loader segment relocation"
        print(f"Callback relocation verified: DGROUP loaded from Win1 data segment (ordinal 1, +{pattern_index:#x})")
    print(f"VALIDATED: {path}")
    print(f"Format: MZ/NE real mode, Windows version 0x{min_version:04x}, segments={segments}")
    print("Modules:",", ".join(modules))
    print("Import ordinals:",", ".join(f"{m}.{n}" for m,n in sorted(found)))
    print("Static Windows 1.04 check passed (not an emulator runtime test).")

if __name__=="__main__":
    if len(sys.argv)!=2:
        sys.exit("Usage: verify_ne.py CHRONO.EXE")
    verify(sys.argv[1])
