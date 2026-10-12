#!/usr/bin/env python3
"""Validate the actual 16-bit Windows 1.x native Open dialog in CHRONO.EXE.

This checks both exported callbacks, resources, and Windows 1.x API imports.
It does not prove that Windows 1.04 can run the executable.
"""
from pathlib import Path
import sys


def validate(path):
    b=Path(path).read_bytes()
    def word(pos): return int.from_bytes(b[pos:pos+2],"little")
    def dword(pos): return int.from_bytes(b[pos:pos+4],"little")
    assert b[:2]==b"MZ"
    ne=dword(0x3c)
    assert b[ne:ne+2]==b"NE"
    assert word(ne+0x3e)==0x0100
    align=word(ne+0x32)
    # Verify the resource was added as a genuine RT_DIALOG, not just
    # as a harmless icon resource or a string in the executable.
    resource=ne+word(ne+0x24)
    resource_end=ne+word(ne+0x26)
    shift=word(resource)
    cursor=resource+2
    dialogs=[]
    while cursor+2<=resource_end:
        kind=word(cursor)
        if not kind: break
        count=word(cursor+2)
        cursor+=8
        for _ in range(count):
            offset=word(cursor)<<shift
            size=word(cursor+2)<<shift
            flags=word(cursor+4)
            ident=word(cursor+6)
            if kind==0x8005:
                dialogs.append((ident,b[offset:offset+size],flags))
            cursor+=12
    assert len(dialogs)==1, f"Expected one Win1 modal dialog, found {len(dialogs)}"
    ident,template,flags=dialogs[0]
    assert ident==(0x8000|101), f"Unexpected dialog ID {ident:#x}"
    assert len(template)>50 and template[4]>=6, "File picker missing controls"
    assert b"Open CV1 image" in template, "Wrong dialog caption"
    # All native Win1 helpers must be present as ordinal relocations.
    names=ne+word(ne+0x2a)
    modtable=ne+word(ne+0x28)
    count=word(ne+0x1e)
    dlls=[]
    for i in range(count):
        pos=names+word(modtable+i*2)
        dlls.append(b[pos+1:pos+1+b[pos]].decode("ascii").upper())
    used=set()
    segtable=ne+word(ne+0x22)
    segments=word(ne+0x1c)
    for i in range(segments):
        s=segtable+i*8
        foff=word(s)<<align
        length=word(s+2) or 65536
        flags=word(s+4)
        if not(flags & 0x100): continue
        rel=foff+length
        for j in range(word(rel)):
            p=rel+2+j*8
            if (b[p+1]&3)==1:
                module=word(p+4)
                assert 1<=module<=len(dlls)
                used.add((dlls[module-1],word(p+6)))
    expected={
        ("USER",87),  # DialogBox
        ("USER",88),  # EndDialog
        ("USER",92),  # SetDlgItemText
        ("USER",93),  # GetDlgItemText
        ("USER",99),  # DlgDirSelect
        ("USER",100), # DlgDirList
        ("USER",153), # ChangeMenu
        ("USER",156), # GetSystemMenu
        ("KERNEL",51), # MakeProcInstance
        ("KERNEL",52), # FreeProcInstance
        ("KERNEL",81), # _lclose
        ("KERNEL",82), # _lread
        ("KERNEL",85), # _lopen
    }
    assert expected<=used, f"Missing native Windows 1.x API imports: {expected-used}"
    # Export table: the first entry is the viewer WndProc, second is
    # the modal dialog callback. Both must reload DGROUP, and neither
    # may call a stack-check routine before restoring DS.
    entry=ne+word(ne+4)
    assert b[entry]>=2 and b[entry+1]==255,"Missing 16-bit callback exports"
    for ordinal in (1,2):
        p=entry+2+(ordinal-1)*6
        assert word(p+1)==0x3fcd, "Unexpected movable-export entry"
        section=b[p+3]
        off=word(p+4)
        assert section in range(1,segments+1)
        seg=segtable+(section-1)*8
        start=word(seg)<<align
        n=word(seg+2)
        flags=word(seg+4)
        assert flags&0x10 and not(flags&0x1000),"Code must be movable, non-discardable"
        head=b[start+off:start+off+64]
        i=head.find(b"\\xb8\\xff\\xff\\x8e\\xd8")
        assert i>=0, f"Callback {ordinal} lacks DGROUP restoration"
        prefix=head[:i]
        assert b"\\xe8" not in prefix and b"\\x9a" not in prefix, f"Callback {ordinal} calls helper before DS restore"
        rel=start+n
        matches=[]
        for j in range(word(rel)):
            rp=rel+2+j*8
            if b[rp]==2 and (b[rp+1]&3)==0 and word(rp+2)==off+i+1 and word(rp+4)==2:
                matches.append(rp)
        assert len(matches)==1, f"Callback {ordinal} missing DGROUP relocation"
        print(f"Callback ordinal {ordinal}: safe Win1 DS relocation verified")
    print(f"PASS: Win1.04 native Open file picker (dialog 101, {template[4]} controls)")
    print("PASS: system menu, directory navigation, modal dialog and KERNEL file APIs")


if __name__=="__main__":
    validate(sys.argv[1])
