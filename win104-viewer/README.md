# ChronoView — image viewer for Windows 1.04

A tiny native **8086 / Windows 1.x** image viewer, built with Open Watcom's
genuine `windows1` target.

## What's new: blank canvas + Open… in the title-bar menu

The earlier viewer finally launched in Windows 1.04, but its permanent
keyboard/path interface was awkward. This version preserves the working
CV1 pixel loader and replaces the interface with:

- **Completely white canvas when you open the app.**
- Click the **Windows 1.04 title-bar system menu → Open…**.
- A period-correct file chooser with a list of **.CV1 images**, **folders**,
  **DOS drive letters**, a filename field, Open, Cancel and Refresh.
- Click a file and choose Open, or double-click a file to render it.
- Double-click a folder or drive to browse into it.
- **Clear** returns to white and **Fit image** toggles scaling.
- **O** remains an optional keyboard shortcut for Open, not the primary UI.

**Windows 1.04 has no modern Common Dialog library**. File selection is built
with the original `GetSystemMenu`, `ChangeMenu`, `DialogBox`,
`DlgDirList` and `DlgDirSelect` APIs. A real `RT_DIALOG` resource is
embedded in `CHRONO.EXE`.

## Files and formats

The guest-side `CHRONO.EXE` loads indexed 16-color **CV1** files, up to
16,384 pixels. For **PNG, JPEG, WebP**, use the companion **convert.html**
offline on a modern browser, then transfer the resulting `.CV1` to the
emulated DOS drive. The old operating system cannot directly decode these
modern formats with this build.

The converter preserves aspect ratio while reducing image size and converts
colors to the standard EGA 16-color palette.

## Installation and use

1. Put `CHRONO.EXE` and `IMAGE.CV1` onto your emulated DOS drive.
2. Start Windows 1.04 from a **clean cold boot** and launch `CHRONO.EXE`.
3. Its window should be white; open the **title-bar menu → Open…**.
4. Choose `IMAGE.CV1` (from the file list), then click Open.
5. The image displays in the viewer. Use **Clear** to make it white again.

Use a disposable emulator snapshot when testing experimental Win1 software;
earlier versions had serious 16-bit memory/callback bugs.

## Code and build

- `win104picker.c` — **current** white-canvas window and native file picker
- `win104picker.rc` / `win104picker.h` — 1985-compatible modal picker template
- `win104safe.c` — last boot-confirmed viewer, kept as a fallback
- `verify_ne.py` — Windows 1.x NE format and imported ordinal checks
- `verify_picker.py` — checks `RT_DIALOG`, dialog APIs, and both callback DGROUP relocations
- `convert.html` — modern photo to DOS CV1 conversion
- `.github/workflows/win104-viewer.yml` — complete reproducible build

The GitHub Actions workflow checks the Windows 1.00 NE target header, permitted
Win1 USER/KERNEL/GDI imports, actual dialog resource, and memory-relocation
code for the window **and** dialog callbacks. It prevents DOS-oriented CRT
file-reading routines from being linked. The image loader uses Windows 1.x
`KERNEL._lopen`, `_lread`, and `_lclose`.

**CI build/static verification is not an actual Windows 1.04 emulator test.**
The new file dialog still needs a test on the target guest.

Period API references:
[Microsoft Windows 1.01 SDK examples](https://github.com/joncampbell123/doslib/tree/master/mssample/win1.01/C)
and [MS Windows 3.0 SDK file-open example](https://www.pcjs.org/documents/books/mspl13/win/w3guide/)
(using early `DlgDirList` and `DlgDirSelect`).
