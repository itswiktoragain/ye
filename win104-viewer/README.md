# ChronoView for Windows 1.04 — loader diagnostics

**Current status:** 8086 real-mode NE executables build and pass static checks in GitHub Actions. They have **not** yet passed a real Windows 1.04 emulator boot test.

Following reports of a blank OK dialog and a frozen MS-DOS Executive with the hourglass cursor, the viewer was rebuilt to:

- remove all `fopen("CHRONO.LOG")` and other DOS file I/O **during startup**
- use a `FAR PASCAL __loadds` callback so the correct Win16 data segment is restored
- use a built-in icon instead of an app resource, avoiding a legacy icon loader dependency
- avoid Windows 2.x/3.x APIs and use the genuine Watcom `system windows1` target
- avoid initial modal error dialogs and unnecessary synchronous painting
- include minimal test programs to distinguish OS loader errors from GUI errors

## Test order (important)

Restart the emulator **after** a freeze, and make a VM snapshot first.

1. **MINWIN.EXE** — loads the same Win1 C runtime and exits immediately. It displays **no window**. MS-DOS Executive should become responsive straight away. If it hangs, the problem is at the Windows loader / CRT level, *not* image decoding or buttons.
2. **PROBE.EXE** — minimal Win1 tiled window with one line of text, no file I/O or custom resources. If MINWIN works and PROBE hangs, the problem is with window registration / GDI / callback conventions.
3. **CHRONO.EXE** — the image viewer. Once it opens, enter `C:\\IMAGE.CV1` and select **Open**.

If an executable freezes, **stop there**. Report the *first* program that failed and which emulator (DOSBox-X, PCem, 86Box, etc.) was used. Repeatedly launching a frozen EXE will not help.

`CHRONO.LOG` is **no longer created**: its synchronous DOS file accesses were part of the unsafe startup path.

The ZIP includes all three EXEs, `IMAGE.CV1`, an offline `convert.html`, and `INSTALL.TXT`.

## Image-format limitations

The viewer still directly loads **CV1** (60,000 pixels maximum, indexed 16-colour EGA). PNG, JPEG and WebP are converted using the included modern browser tool. There is **no native PNG/JPEG/WebP decoding inside the Windows 1.04 executable** yet.

## Source

- `minwin.c` — no-GUI Win1 real-mode startup test
- `probe.c` — pure Win1 window creation, GDI and message-loop test
- `viewer.c` — CV1 image viewer
- `verify_ne.py` — NE header and API import check
- `convert.html` — modern browser image format conversion

Built using [Open Watcom 2](https://github.com/open-watcom/open-watcom-v2) and Win1 examples from [doslib](https://github.com/joncampbell123/doslib/tree/master/windows/hello). Compatibility analysis is not a substitute for a Windows 1.04 emulator run.
