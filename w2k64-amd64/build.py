#!/usr/bin/env python3
"""Boot-preserving W2K64 AMD64 prototype builder.

This is a custom-media XP Professional x64 NT 5.2 prototype, NOT an
NT 5.0 / genuine Windows 2000 64-bit kernel. WOW64 comes from XP x64.
Neither NTVDM nor native 16-bit Windows/DOS support is added here.

Crucially: import the ORIGINAL ISO with xorriso and replay its El Torito
boot settings; do not reconstruct the original installation filesystem.
"""
import argparse
import hashlib
import pathlib
import re
import shutil
import struct
import subprocess
import sys
import tarfile
from iso9660 import ISO9660, SECTOR

HERE = pathlib.Path(__file__).resolve().parent


def require(cmd):
    path = shutil.which(cmd)
    if not path:
        raise RuntimeError(f"Missing required program: {cmd}")
    return path


def execute(*args):
    args = [str(a) for a in args]
    print("+", " ".join(args), flush=True)
    subprocess.run(args, check=True)


def digest_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for data in iter(lambda: f.read(4 * 1024 * 1024), b""):
            h.update(data)
    return h.hexdigest()


def hash_iso_entry(iso, ent):
    h = hashlib.sha256()
    iso.f.seek(SECTOR * ent["extent"])
    remaining = ent["size"]
    while remaining:
        data = iso.f.read(min(1024 * 1024, remaining))
        if not data:
            raise ValueError("Premature EOF reading an ISO file")
        h.update(data)
        remaining -= len(data)
    return h.hexdigest()


def inspect_bios_boot(path):
    """Read El Torito's BIOS default entry and actual 2048-byte boot payload."""
    with open(path, "rb") as f:
        f.seek(17 * SECTOR)
        descriptor = f.read(SECTOR)
        if descriptor[:7] != b"\x00CD001\x01" or not descriptor[7:30] == b"EL TORITO SPECIFICATION":
            raise ValueError(f"{path} has no El Torito boot descriptor at sector 17")
        catalog_sector = struct.unpack_from("<I", descriptor, 71)[0]
        f.seek(catalog_sector * SECTOR)
        catalog = f.read(SECTOR)
        if catalog[0] != 1:
            raise ValueError("Invalid El Torito validation entry")
        if catalog[1] != 0:
            raise ValueError("First El Torito entry is not BIOS/x86")
        if catalog[32] != 0x88 or catalog[33] != 0:
            raise ValueError("BIOS default entry is missing or not no-emulation")
        count = struct.unpack_from("<H", catalog, 38)[0]
        image_sector = struct.unpack_from("<I", catalog, 40)[0]
        if count < 4 or not image_sector:
            raise ValueError("BIOS boot image sector count or location is invalid")
        f.seek(image_sector * SECTOR)
        payload = f.read(SECTOR)
        if len(payload) != SECTOR:
            raise ValueError("BIOS boot image is truncated")
        return {
            "platform": catalog[1],
            "type": catalog[33],
            "segment": struct.unpack_from("<H", catalog, 34)[0],
            "count": count,
            "system_type": catalog[36],
            "payload_sha256": hashlib.sha256(payload).hexdigest(),
        }


def original_xp_iso(args, workspace):
    if args.xp_iso:
        xp = args.xp_iso.expanduser().resolve()
        if not xp.is_file():
            raise FileNotFoundError(xp)
        return xp
    parts = args.xp_7z.expanduser().resolve()
    if not parts.name.endswith(".7z.001"):
        raise ValueError("--xp-7z must point to the .7z.001 file")
    if not pathlib.Path(str(parts)[:-3] + "002").is_file():
        raise ValueError("Missing the adjacent .7z.002 file")
    tool = shutil.which("7z") or shutil.which("7zz")
    if not tool:
        raise RuntimeError("Install p7zip-full / 7zip")
    folder = workspace / "XP_source_extracted"
    folder.mkdir(parents=True, exist_ok=True)
    execute(tool, "x", "-y", f"-o{folder}", parts)
    files = [f for f in folder.rglob("*") if f.is_file() and f.suffix.lower() == ".iso"]
    if len(files) != 1:
        raise ValueError(f"Expected exactly one ISO inside the 7z, found {len(files)}")
    return files[0]


def make_media_assets(media, win2k):
    from PIL import Image, ImageDraw, ImageFont
    media.mkdir(parents=True, exist_ok=True)
    legacy = media / "LEGACY"
    legacy.mkdir(exist_ok=True)

    # Extract optional original Windows 2000 tools to a separate experiment folder.
    iso = ISO9660(win2k)
    for src in ("I386/EXPLORER.EX_", "I386/PROGMAN.EX_"):
        if src in iso.entries:
            iso.extract(src, str(legacy / src.rsplit("/", 1)[-1]))
    iso.close()

    def font(size, bold=False):
        prefix = "/usr/share/fonts/truetype/dejavu/DejaVuSans"
        path = prefix + ("-Bold" if bold else "") + ".ttf"
        return ImageFont.truetype(path, size)

    about = Image.new("RGB", (510, 100), "#ffffff")
    d = ImageDraw.Draw(about)
    d.rectangle((0, 0, 11, 99), fill="#195582")
    d.text((20, 11), "Windows 2000 Professional", font=font(27, True), fill="#15476f")
    d.text((20, 62), "64-bit edition", font=font(22, True), fill="#15476f")
    about.save(media / "ABOUT.BMP")
    about.save(media / "ABOUT.PNG")

    boot = Image.new("RGB", (640, 480), "#ffffff")
    d = ImageDraw.Draw(boot)
    d.text((50, 180), "Windows 2000", font=font(46, True), fill="#164b7c")
    d.text((50, 255), "64-bit edition", font=font(26, True), fill="#164b7c")
    d.text((50, 304), "Community-made prototype", font=font(18), fill="#444444")
    boot.save(media / "BOOT2000_64BIT.PNG")

    wallpaper = Image.new("RGB", (1024, 768), "#195787")
    d = ImageDraw.Draw(wallpaper)
    d.text((115, 333), "Windows 2000 Professional", font=font(36, True), fill="#ffffff")
    d.text((115, 400), "64-bit edition", font=font(28, True), fill="#ffffff")
    wallpaper.save(media / "WALLPAPER.BMP")

    (media / "README.TXT").write_text(
        "Windows 2000 Professional (64-bit edition, a community made prototype)\r\n"
        "This is a Windows XP Professional x64 (NT 5.2) core, not an NT 5.0 kernel port.\r\n"
        "WOW64 supports 32-bit applications. Native DOS/Win16 is NOT available.\r\n"
        "Windows 2000 EXPLORER.EX_ is an optional experiment and is not the default shell.\r\n"
        "BOOT2000_64BIT.PNG and ABOUT.PNG are preview artwork, not system patches.\r\n"
        "This is legacy BIOS boot media, unless the original ISO has its own EFI boot entry.\r\n",
        encoding="ascii",
    )
    (media / "16BIT.TXT").write_text(
        "WOW64 is for 32-bit Windows programs, NOT 16-bit applications.\r\n"
        "A Windows 2000 32-bit virtual machine is the safe compatibility solution.\r\n",
        encoding="ascii",
    )
    (media / "WOW64_CHECK.CMD").write_text(
        "@echo off\r\n"
        "if exist \"%windir%\\System32\\wow64.dll\" echo WOW64 is present\r\n"
        "pause\r\n",
        encoding="ascii",
    )
    (media / "INSTALL.CMD").write_text(
        "@echo off\r\n"
        "if not exist \"%SystemDrive%\\W2K64\" mkdir \"%SystemDrive%\\W2K64\"\r\n"
        "xcopy \"%~dp0*\" \"%SystemDrive%\\W2K64\\\" /E /I /Y\r\n"
        "start \"\" \"%SystemDrive%\\W2K64\\ABOUT.EXE\"\r\n",
        encoding="ascii",
    )

    gcc = require("x86_64-w64-mingw32-gcc")
    execute(gcc, "-O2", "-s", "-mwindows", HERE / "about.c",
            "-o", media / "ABOUT.EXE", "-luser32", "-lgdi32")


def check_unchanged_files(source, result):
    """Ensure every original ISO9660 path points to identical original bytes."""
    src = ISO9660(source)
    out = ISO9660(result)
    try:
        if "AMD64/WOW64CPU.DL_" not in src.entries:
            raise ValueError("Input does not appear to be Windows XP Professional AMD64")
        missing = set(src.entries) - set(out.entries)
        if missing:
            raise ValueError("The rebuilt ISO changed/removed original names: " + repr(sorted(missing)[:12]))
        checked = 0
        for name, entry in src.entries.items():
            if not name or (entry["flags"] & 2):
                continue
            other = out.entries[name]
            if other["size"] != entry["size"] or hash_iso_entry(src, entry) != hash_iso_entry(out, other):
                raise ValueError("A Windows setup file changed: " + name)
            checked += 1
        for extra in ("W2K64/ABOUT.EXE", "W2K64/README.TXT"):
            if extra not in out.entries:
                raise ValueError("Missing prototype file: " + extra)
        return checked
    finally:
        src.close()
        out.close()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    xp = p.add_mutually_exclusive_group(required=True)
    xp.add_argument("--xp-iso", type=pathlib.Path)
    xp.add_argument("--xp-7z", type=pathlib.Path, help="Path to .7z.001; .002 must be alongside")
    p.add_argument("--win2000-iso", type=pathlib.Path, required=True)
    p.add_argument("--output", type=pathlib.Path, default=pathlib.Path("W2K64_output"))
    p.add_argument("--no-archive", action="store_true", help="Produce ISO only (faster debugging)")
    args = p.parse_args()

    xorriso = require("xorriso")
    require("x86_64-w64-mingw32-gcc")
    output = args.output.expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    workspace = output / "_work"
    workspace.mkdir(exist_ok=True)
    source = original_xp_iso(args, workspace)
    win2k = args.win2000_iso.expanduser().resolve()
    if not win2k.is_file():
        raise FileNotFoundError(win2k)

    original_boot = inspect_bios_boot(source)
    if original_boot["count"] != 4:
        print("Note: Original BIOS boot-load-size differs from the common 4 sectors:", original_boot)

    media = workspace / "W2K64"
    make_media_assets(media, win2k)

    iso_output = output / "W2K64_AMD64_Community_Prototype_FIXED.iso"
    if iso_output.exists():
        iso_output.unlink()

    # THIS REPLACES THE OLD REBUILDER. Preserve source ISO tree and boot details.
    execute(xorriso, "-indev", source, "-outdev", iso_output,
            "-map", media, "/W2K64", "-boot_image", "any", "replay", "-commit", "-end")

    new_boot = inspect_bios_boot(iso_output)
    if original_boot != new_boot:
        raise ValueError("The repacked ISO changed the boot loader's payload or El Torito options! "
                         f"Original: {original_boot}; new: {new_boot}")

    file_count = check_unchanged_files(source, iso_output)
    print(f"Verified BIOS boot image and {file_count} XP setup files unchanged.")
    print("NOTE: This is a structural verification, NOT a full Windows XP x64 boot test.")

    paths = [iso_output]
    if not args.no_archive:
        archive = output / (iso_output.stem + ".tar.xz")
        print("Writing full compressed archive:", archive, flush=True)
        with tarfile.open(archive, "w:xz", preset=1) as t:
            t.add(iso_output, arcname=iso_output.name)
        paths.append(archive)
        index = 0
        with archive.open("rb") as f:
            while True:
                block = f.read(64 * 1024 * 1024)
                if not block:
                    break
                piece = output / f"{archive.name}.part{index:02d}"
                piece.write_bytes(block)
                paths.append(piece)
                index += 1
        print("Split archive into", index, "parts.")

    with (output / "SHA256SUMS.txt").open("w") as f:
        for path in paths:
            f.write(f"{digest_file(path)}  {path.name}\n")

    print("FINISHED:", iso_output)
    print("For QEMU, use SeaBIOS/legacy BIOS, not OVMF/UEFI, unless source supports EFI.")
    print("Example: qemu-system-x86_64 -M pc -m 2048 -cdrom '" + str(iso_output) +
          "' -boot order=d -drive file=disk.qcow2,if=ide -net none")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, RuntimeError) as e:
        print("BUILD FAILED (no verified image):", e, file=sys.stderr)
        sys.exit(1)
