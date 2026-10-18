# Windows 2000 AMD64 community prototype builder

This project creates a **complete bootable BIOS ISO** from **your own Windows XP Professional x64 AMD64 ISO and Windows 2000 Professional ISO**. The final ISO is split into 64 MiB parts and also packed into a TAR.XZ. The original XP x64 kernel and WOW64 installer binaries stay unchanged.

**It is NOT an actual NT 5.0 / Windows 2000 x64 port.** The running OS is XP Professional x64, NT 5.2. This also does **not** enable native 16-bit programs. Those need a separate emulator or an x86 Windows VM. Boot and About branding is additional artwork / a separate About utility, not replacements for Windows' actual boot or winver dialog.

## Ubuntu build

```bash
sudo apt update
sudo apt install -y python3 python3-pil gcc libarchive-dev mingw-w64 p7zip-full cabextract git
git clone https://github.com/itswiktoragain/ye.git
cd ye/w2k64-amd64
python3 build.py \
  --xp-7z "$HOME/Downloads/Windows XP Pro x64 English.7z.001" \
  --win2000-iso "$HOME/Downloads/Windows 2000 Professional.ISO" \
  --output "$HOME/Downloads/W2K64_output"
```

Keep the `.7z.001` and `.7z.002` files in the same folder. If you already extracted the XP x64 ISO, replace `--xp-7z ...` with `--xp-iso "/path/to/xp-x64.iso"`.

All output goes in `~/Downloads/W2K64_output/`:
- `W2K64_AMD64_Community_Prototype.iso` — the complete ISO
- `W2K64_AMD64_Community_Prototype.tar.xz` — full ISO in a TAR.XZ
- `W2K64_AMD64_Community_Prototype.tar.xz.part00`, `part01` etc. — sequential, up-to-64-MiB chunks
- `SHA256SUMS.txt` — hashes for integrity checks

To reassemble parts downloaded from GitHub artifacts or copied separately:

```bash
cat W2K64_AMD64_Community_Prototype.tar.xz.part[0-9][0-9] > W2K64_AMD64_Community_Prototype.tar.xz
tar -xJf W2K64_AMD64_Community_Prototype.tar.xz
sha256sum -c SHA256SUMS.txt
```

Note: `sha256sum -c` checks all files listed; if you only transferred parts, check after reconstructing or use the specific entry from the sums file.

## GitHub delivery limitations

The connected GitHub API cannot upload binary files located in the assistant container directly as large release assets, and this **public repository must not contain copyrighted Windows installer binaries or ISOs**. This repository contains build source only. To distribute build results in GitHub, do so using a repository/private upload or a runner under your control, rather than putting ISO parts into the public source repository.

Test the image in an isolated VM using legacy BIOS boot before real hardware. Windows XP x64 is unsupported and insecure online.