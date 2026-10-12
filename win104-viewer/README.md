# ChronoView - Windows 1.04 native image viewer

This project creates genuine 8086 / Windows 1.x **New Executable (NE)** files, with the
**Open Watcom `system windows1`** target, original 1985-style icon resources,
8086 instructions and only early Windows USER/GDI/KERNEL imports.

## Blank dialog / Windows hang repair (October 2026)

A Windows 1.04 test showed a **blank message box with an OK button**, then a freeze.
The earlier binary used Windows APIs and initialization patterns that were too risky
for Windows 1.x. The follow-up rebuild:

- Removes all startup `MessageBox` calls (the previous blank dialog)
- Uses an original Windows 1.03 SDK-style FAR PASCAL window procedure, exported resident
- Links to `windows1`, using `-0 -zu -zw -bg` and separate real-mode code/data segments
- Uses an authentic **64x64 monochrome 1985-style ICO** resource
- Defers Edit/Button control creation until after the parent window exists
- Avoids synchronous `UpdateWindow` during startup
- Creates `CHRONO.LOG` with numbered steps when launched from a writable DOS directory
- Includes a separate lightweight `PROBE.EXE` to test GUI loader and GDI text rendering
- Verifies PE/NE headers and imports in GitHub Actions and treats compiler warnings as errors

## Download

On a phone, open [latest GitHub Actions build](https://github.com/itswiktoragain/ye/actions/workflows/win104-viewer.yml) and download
`CHRONOVIEW-WIN104-STARTUP-REPAIR`.

Package includes `CHRONO.EXE`, `PROBE.EXE`, `IMAGE.CV1`, `convert.html` and `INSTALL.TXT`.

## How to diagnose

1. Copy `PROBE.EXE`, `CHRONO.EXE` and `IMAGE.CV1` to a writable disk visible in Windows 1.04.
2. Launch **PROBE.EXE** from MS-DOS Executive. It should open a tiled window that says
   `Windows 1.04 callback and text output OK` on the screen.
3. Close the probe and launch **CHRONO.EXE**.
4. It should show a text path box and Open / Fit / About buttons.
5. Type `C:\\IMAGE.CV1` and click **Open**. Alternatively launch CHRONO with the image path as an argument.
6. If the viewer hangs, check the file `CHRONO.LOG` in its launch directory.
   Its final STEP number helps distinguish class registration, window creation,
   child controls, or WM_PAINT. Share the last line with the emulator name.

**There has been no actual Windows 1.04 emulator boot test in this workflow.**
The CI checks establish a real 16-bit Win1 NE binary, not proof of runtime success.

## Image support

The 16-bit executable directly reads small 16-colour **CV1** images (up to
60,000 pixels); it **does not natively decode PNG, JPEG or WebP**. The phone-side
`convert.html` supports those modern formats using a browser decoder, reduces
them to indexed CV1, and makes the image available to be copied into the
emulator's DOS disk.

Native codec support for all three modern formats is a separate
work item, not included in this bug fix. The unlinked `inflate.c` is an
experimental DEFLATE component, not a production PNG decoder.

Sources: [Microsoft Windows 1.03 Hello sample as annotated by NCommander](https://github.com/NCommander/win1-hello-world-annotations)
and [doslib Windows 1.0 cross-build examples](https://github.com/joncampbell123/doslib/tree/master/windows/hello).
