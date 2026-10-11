# ChronoView for Windows 1.04

Experimental **native Win16 Windows 1.x** image viewer.

## Important limitation
This is an early prototype, not yet a verified Windows 1.04 binary.
Windows 1.x lacks PNG/JPEG/WebP decoders, and 16-bit conventional memory is severe.
The viewer reads **CV1** files after conversion in a modern browser. CV1 uses
the standard EGA palette, 1 byte per pixel, limited to 65520 pixels.
Open a file by putting it at C:\\IMAGE.CV1, or launch with a pathname as command-line argument.

A GitHub Actions workflow builds `CHRONO.EXE` using Open Watcom.
Artifacts appear under **Actions > Windows 1.04 Viewer > latest successful run**.

The PNG/JPEG/WebP converter runs entirely in the phone browser at
`convert.html` (GitHub Pages / raw HTML downloaded and opened).
It auto-resizes to fit the 64 KiB image memory limit.

## Files
- `viewer.c` Windows 1.x GUI prototype and indexed pixel renderer
- `inflate.c` *work-in-progress* DEFLATE implementation (not linked to viewer)
- `convert.html` PNG/JPEG/WebP to indexed CV1 converter
- `.github/workflows/win104-viewer.yml` Windows executable build

This prototype has **not been emulator-tested**. Don't confuse a successful
link with proven Windows 1.04 compatibility.
