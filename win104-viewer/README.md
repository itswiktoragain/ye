# ChronoView — Windows 1.04 native image viewer

Experimental real-mode **8086 / Windows 1.04** NE executable. Built in your
`ye` GitHub repository with the Open Watcom `system windows1` linker target.

The viewer starts with a **completely white canvas**. Select **Open...** from
the upper-left Windows 1.x title-bar system menu to choose a file.

## Actual image decoding in the guest

| File | Opens without conversion? | Supported varieties |
| --- | --- | --- |
| **PNG** | **Yes** | Non-interlaced gray, RGB, palette, gray+alpha, RGBA; 1/2/4/8/16-bit valid combinations; PNG filters; DEFLATE, PLTE and basic transparency |
| **JPEG / JPG** | **Yes** | Baseline grayscale and YCbCr JPEG, several common subsampling modes. Progressive JPEG is not supported. |
| **WebP** | **Not yet** | The RIFF/WEBP signature is detected; VP8 lossy and VP8L lossless decoding are not implemented in the current 8086 EXE. |
| **CV1** | **Yes** | Original 16-color indexed format. |

**PNG and baseline JPEG are decoded inside Windows 1.04**. They do not need a
browser converter, helper process or preconverted image.

Colors are mapped to **16 EGA colors** and the native decoder reduces images
to a maximum output of **128 × 128 pixels**, because Windows 1.04's segmented
memory and early GDI are highly constrained. This is a real rendered preview,
not original full-resolution output. Source images up to 8192 × 8192 pixels
are accepted by the decoders, subject to available guest RAM; very large files
may take minutes on an emulated 8086.

The existing `convert.html` remains in source control solely for legacy CV1
creation. It is **not required or included in the current EXE package**.

## Test it

1. Restore a disposable Windows 1.04 VM snapshot and perform a cold boot.
2. Copy `CHRONO.EXE`, `COLOR.PNG`, `PHOTO.JPG` to a DOS drive.
3. Launch `CHRONO.EXE`; the canvas should be white.
4. Open the title-bar system menu, choose **Open...**, and select **COLOR.PNG**.
5. The PNG should be decoded and shown. Use **Open...** again for **PHOTO.JPG**.
6. Try a PNG or baseline JPEG from your own collection (on a DOS-compatible
   8.3 filename). The EXE recognizes file signatures even if extensions are
   shortened by the DOS filesystem.

An invalid, unsupported or truncated file should leave the previous picture
unchanged and show an error, rather than being handed to the wrong decoder.

## Development and validation

- `win104picker.c`, `win104picker.rc`: Windows 1.0 system menu and dialog
- `codecs.c`, `codecs.h`: bounded 16-color EGA decode buffer
- `codec_png.c`: custom streaming PNG + zlib/DEFLATE reader
- `codec_jpeg.c`: low-RAM JPEG adapter
- `picojpeg.c` fetched at pinned commit on build: Rich Geldreich's
  public-domain/MIT-compatible [picojpeg](https://github.com/richgel999/picojpeg),
  with fixes for undefined signed shifts and SS-to-DS pointer hazards
- `codec_webp.c`: **unsupported stub**, not a claim of WebP decoding
- `tests/make_fixtures.py`, `tests/hostcodec.c`: native compressed image
  regression fixtures and ASAN/UBSAN host tests
- `verify_ne.py`, `verify_picker.py`: Win1 NE format, resources,
  imported ordinals and callback DS relocation checks
- `.github/workflows/win104-viewer.yml`: build + tests + downloadable EXE

Tests cover real compressed RGB PNG, indexed+transparent PNG, stored-DEFLATE
PNG, RGBA PNG and baseline JPEG, with expected EGA sums. The native code
compiles for 8086 and passes static Win1 ABI checks.

**Important:** no actual Windows 1.04 emulator test has been performed in CI.
Earlier versions of ChronoView froze/corrupted guest memory, so use only a
throwaway/snapshotted VM until this build is confirmed stable on your emulator.

WebP is a separate VP8/VP8L decoder-engineering problem; unlike PNG and
picojpeg, no verified 16-bit low-memory decoder has been integrated. It
would be misleading to claim WebP works by merely recognizing its header.
