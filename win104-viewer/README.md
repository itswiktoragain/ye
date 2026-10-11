# ChronoView / Microsoft Windows 1.04 (1987)

**Experimental native 16-bit Windows 1.0 application**, not a Windows 3.x app.

The first `CHRONO.EXE` build falsely claimed to target Windows 1.0 after being
linked as Windows 3.0. It imported newer USER APIs and could crash on launch.

This revision compiles with **Open Watcom's real `system windows1` linker target**,
an explicit 8086 CPU and Windows 1.0 API target, `-zu -zw` for Windows real-mode
SS/DS differences, and a resident exported window procedure with
`FAR PASCAL __loadds`. It uses `ChangeMenu` instead of
Windows 3.x `AppendMenu` / `CreatePopupMenu`.

The CI job includes a static NE import check to reject unsupported
newer APIs and creates a test image. The build also uses *zero* pointer
truncations into the 16-bit C runtime.

## Downloads

Open the [GitHub Actions workflow](https://github.com/itswiktoragain/ye/actions/workflows/win104-viewer.yml)
and select the newest successful run of the `agent/win104-image-viewer` branch.
Download `CHRONOVIEW-WIN104-8086`, containing:

- `CHRONO.EXE` - native Windows 1.04 image viewer
- `IMAGE.CV1` - small built-in 16-color test image
- `INSTALL.TXT` - setup instructions

## Windows 1.04 usage

Copy `CHRONO.EXE` and `IMAGE.CV1` into the same emulated DOS drive,
for example `C:\\`.

Start `CHRONO.EXE` from MS-DOS Executive. The window contains a
path text field and an Open button. Enter `C:\\IMAGE.CV1`, click **Open**,
and the test image should appear. The system menu has a Fit / Actual size toggle.

### PNG, JPEG and WebP

These codecs are **not yet compiled into the Windows 1.04 EXE**.
Use `convert.html` in a modern browser (including your Android phone)
to convert any PNG, JPEG or WebP to `CV1` and transfer the CV1 file
to the emulated DOS disk. Conversion is local, no image upload needed.

The modern-side converter automatically reduces images to at most
**60,000 16-color pixels** (maximum width 640, height 480).
This is necessary because a real-mode 16-bit segment cannot store
hundreds of thousands of pixels at once.

## Source

- `viewer.c`: 1980s API-only Win16 GUI and CV1 image renderer
- `convert.html`: phone/browser-side modern codec converter
- `verify_ne.py`: static NE header and imported ordinal verifier
- `inflate.c`: unlinked work-in-progress DEFLATE decoder (not available yet)
- `.github/workflows/win104-viewer.yml`: reproducible build

Background and Windows 1.0 toolchain reference:
[joncampbell123/doslib](https://github.com/joncampbell123/doslib/tree/master/windows/exmenus) and
[NCommander's annotated official Windows 1.03 Hello sample](https://github.com/NCommander/win1-hello-world-annotations).

**Build-verified, not emulator-verified.** A Windows 1.04 boot test remains
necessary to prove runtime compatibility. Do not infer successful boot merely
from a compliant NE header or import table.
