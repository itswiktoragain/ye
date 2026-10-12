# ChronoView for Windows 1.04

## Current build — 8086 kernel-file-I/O rewrite

The earlier `CHRONO.EXE` builds caused severe Windows 1.04 guest instability:
blank dialogs, damaged text, a hung MS-DOS Executive, and apparent DOS
file-system failures until cold reboot. **Do not continue using those EXEs.**

The current CHRONO.EXE has been rewritten in `win104safe.c`, **not**
`viewer.c` or `sdkviewer.c`. The old files are kept for reference,
not included in the current CHRONO link.

### What changed

- Removed `stdio.h`, `fopen`, `fread`, `fgetc`, `_dos_open` and the
  large DOS-oriented C file I/O implementation from the viewer.
- Uses Windows **1.x KERNEL._lopen, KERNEL._lread, KERNEL._lclose**
  to load CV1 image bytes (checked as ordinal imports 85, 82, 81).
- Never opens or writes a file during startup or routine repaint.
- Restores the app data segment with `FAR PASCAL __loadds`, exports the
  callback, and validates the resulting NE relocation in CI.
- Disables the Watcom stack-checker call that previously preceded
  the callback's DS restoration.
- Uses a single tiled window, with no child controls, resources, dialogs
  or dynamic GDI allocations during painting.
- Rejects second instances to avoid copying state from a hung process.
- Caps images at **16,384 16-colour EGA pixels** to reduce 16-bit pressure.
- Includes a host-side PNG/JPEG/WebP-to-CV1 converter.

The Open Watcom Windows1 startup routine still contains its normal DOS
version check and process-exit instructions. The code does **not** perform
raw DOS file operations when opening images.

### Testing

**This is statically verified, not Windows 1.04 emulator-verified.**
Compiled with Open Watcom's genuine `system windows1` linker target,
8086 CPU, non-discardable movable code, and no compiler warnings. CI
checks the Windows 1.00 NE header, Win1 imports, DGROUP callback relocation,
and absence of the unwanted DOS-oriented file I/O handlers in the map.

Always make a disposable VM snapshot before testing. Power off the virtual
machine fully after a crash; do not restore its corrupted saved RAM.

Copy `CHRONO.EXE` and `IMAGE.CV1` to an emulated DOS drive. Launch
`CHRONO.EXE`, then first verify it can remain open and be moved around
safely. Press **O** to open `C:\\IMAGE.CV1`. **P** edits the file path,
**F** toggles fit, **Q** exits. If you need a modern photo, open
`convert.html` on your phone and convert to CV1 before transferring it.

If it freezes without any image being opened, **do not repeatedly launch**
it. This would point to the Windows 1.x ABI/loader/message-loop path,
not the CV1 decoder, and needs a targeted emulator test.

### Limitations

The Windows 1.04 executable itself does **not** decode PNG/JPEG/WebP.
It accepts the companion 16-colour CV1 format. The browser converter
supports modern image formats and constrains them to the viewer's
16,384-pixel limit.

### Build sources

- `win104safe.c` — **current native Win1 viewer source**
- `verify_ne.py` — static Windows 1.x binary validator
- `convert.html` — offline converter for modern PNG/JPEG/WebP
- `.github/workflows/win104-viewer.yml` — GitHub Actions build
- `viewer.c`, `sdkviewer.c`, `inflate.c` — obsolete/experimental source,
  not linked into current CHRONO.EXE

Original API references: [Microsoft's Windows 1.01 SDK sample](https://github.com/joncampbell123/doslib/tree/master/mssample/win1.01/C)
and [Open Watcom's Windows 1.x target](https://github.com/open-watcom/open-watcom-v2).
