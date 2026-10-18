#!/usr/bin/env python3
"""Rebuild Windows 2000-styled XP x64 prototype from user-owned source ISOs.
NOT a Windows 2000 NT 5.0 kernel port. No integrated 16-bit compatibility.
"""
import argparse, hashlib, os, pathlib, shutil, struct, subprocess, sys, tarfile
from iso9660 import ISO9660, SECTOR
P=pathlib.Path
ROOT=P(__file__).resolve().parent
def run(*args):
 print('+',*map(str,args),flush=True);subprocess.run(list(map(str,args)),check=True)
def digest(f):
 h=hashlib.sha256()
 with open(f,'rb') as s:
  for b in iter(lambda:s.read(4*1024*1024),b''):h.update(b)
 return h.hexdigest()
def extract(iso,folder):
 folder.mkdir(parents=True,exist_ok=True)
 for name,node in iso.entries.items():
  if name:iso.extract(name,str(folder.joinpath(*name.split('/'))))
def boot_image(path):
 with open(path,'rb') as f:
  f.seek(17*SECTOR);record=f.read(SECTOR)
  if record[0]!=0 or record[7:30]!=b'EL TORITO SPECIFICATION':raise ValueError('Missing BIOS boot catalog')
  f.seek(struct.unpack_from('<I',record,71)[0]*SECTOR);cat=f.read(SECTOR)
  ent=cat[32:64]
  if ent[0]!=0x88 or struct.unpack_from('<H',ent,6)[0]!=4:raise ValueError('Unsupported boot image')
  f.seek(struct.unpack_from('<I',ent,8)[0]*SECTOR)
  return f.read(2048)
def fixnames(original,output):
 fixes={'AMD64/CYCLAD_Z.IN_':'AMD64/CYCLAD-Z.IN_','AMD64/CYCLOM_Y.IN_':'AMD64/CYCLOM-Y.IN_','AMD64/DISK1.':'AMD64/DISK1','AMD64/HOSTS.':'AMD64/HOSTS',
 'AMD64/UTOPIA_1.WA_':'AMD64/UTOPIA~1.WA_','AMD64/UTOPIA_2.WA_':'AMD64/UTOPIA~2.WA_','AMD64/UTOPIA_3.WA_':'AMD64/UTOPIA~3.WA_','AMD64/UTOPIA_4.WA_':'AMD64/UTOPIA~4.WA_','I386/NTLDR.':'I386/NTLDR','WIN51.':'WIN51','WIN51AP.':'WIN51AP'}
 a,b=ISO9660(original),ISO9660(output)
 with open(output,'r+b') as f:
  for wrong,right in fixes.items():
   if wrong not in b.entries or right not in a.entries:continue
   parent,_,oldname=wrong.rpartition('/');newname=right.rsplit('/',1)[-1].encode('ascii')
   node=b.entries[parent];data=b.read(node);off=0;found=False
   while off<len(data):
    n=data[off]
    if n==0:off=(off//SECTOR+1)*SECTOR;continue
    length=data[off+32];name=data[off+33:off+33+length].decode('latin1')
    if name==oldname:
     assert len(newname)<=length
     f.seek(node['extent']*SECTOR+off+32);f.write(bytes([len(newname)])+newname+b'\0'*(length-len(newname)))
     found=True;break
    off+=n
   if not found:raise ValueError('ISO name repair failed: '+wrong)
 a.close();b.close()
def main():
 p=argparse.ArgumentParser()
 g=p.add_mutually_exclusive_group(required=True)
 g.add_argument('--xp-iso',type=P)
 g.add_argument('--xp-7z',type=P,help='Path to .7z.001; .002 must be alongside')
 p.add_argument('--win2000-iso',type=P,required=True)
 p.add_argument('--output',type=P,default=P('W2K64_output'))
 a=p.parse_args()
 output=a.output.resolve();output.mkdir(parents=True,exist_ok=True)
 work=output/'_work';work.mkdir(exist_ok=True)
 if a.xp_7z:
  part=a.xp_7z.resolve()
  if not part.name.endswith('.7z.001') or not P(str(part)[:-3]+'002').is_file():raise ValueError('Provide first .7z.001 with .002 alongside')
  extractor=shutil.which('7z') or shutil.which('7zz')
  if not extractor:raise RuntimeError('Install p7zip-full')
  source_folder=work/'source';source_folder.mkdir(exist_ok=True)
  run(extractor,'x','-y',f'-o{source_folder}',part)
  isos=list(source_folder.rglob('*.iso'))
  if len(isos)!=1:raise ValueError('Expected one XP x64 ISO inside 7z')
  xp=isos[0]
 else:xp=a.xp_iso.resolve()
 win2k=a.win2000_iso.resolve()
 xp_source=ISO9660(xp)
 if 'AMD64/WOW64CPU.DL_' not in xp_source.entries:raise ValueError('Not XP x64 AMD64')
 dest=work/'iso_root';extract(xp_source,dest)
 boot=boot_image(xp);(dest/'W2K64_BOOT.IMG').write_bytes(boot)
 demo=dest/'W2K64';demo.mkdir(exist_ok=True)
 demo.joinpath('README.TXT').write_text('Windows 2000 64-bit edition, a community made prototype.\nThis is XP Professional x64 NT5.2 and NOT a Windows 2000 kernel port.\nWOW64 remains native to XP x64. 16-bit software needs an emulator/VM.\nThis is BIOS boot media, not UEFI. Use offline for testing.\n',encoding='ascii')
 demo.joinpath('16BIT_SUPPORT.TXT').write_text('WOW64 supports Win32, not Win16. Windows 2000 x86 in a VM is recommended.\n',encoding='ascii')
 demo.joinpath('INSTALL.CMD').write_text('@echo off\r\nif not exist "%SystemDrive%\\W2K64" mkdir "%SystemDrive%\\W2K64"\r\nxcopy "%~dp0*" "%SystemDrive%\\W2K64\\" /E /I /Y\r\nstart "" "%SystemDrive%\\W2K64\\ABOUT.EXE"\r\n',encoding='ascii')
 demo.joinpath('WOW64_CHECK.CMD').write_text('@echo off\r\nif exist "%windir%\\System32\\wow64.dll" echo WOW64 present\r\npause\r\n',encoding='ascii')
 w2=ISO9660(win2k);legacy=demo/'LEGACY';legacy.mkdir(exist_ok=True)
 for name in ['I386/EXPLORER.EX_','I386/PROGMAN.EX_']:
  if name in w2.entries:w2.extract(name,str(legacy/name.rsplit('/',1)[-1]))
 w2.close()
 from PIL import Image,ImageDraw,ImageFont
 def font(s,b=False):
  prefix='/usr/share/fonts/truetype/dejavu/DejaVuSans'
  return ImageFont.truetype(prefix+('-Bold' if b else '')+'.ttf',s)
 banner=Image.new('RGB',(510,100),'white');d=ImageDraw.Draw(banner)
 d.rectangle((0,0,10,99),fill='#174b7f')
 d.text((22,13),'Windows 2000 Professional',font=font(28,True),fill='#174b7f')
 d.text((22,61),'64-bit edition',font=font(22,True),fill='#253a53')
 banner.save(demo/'ABOUT.BMP');banner.save(demo/'ABOUT.PNG')
 bootpic=Image.new('RGB',(640,480),'white');d=ImageDraw.Draw(bootpic)
 d.text((55,170),'Windows 2000',font=font(48,True),fill='#174b7f')
 d.text((55,250),'64-bit edition',font=font(26,True),fill='#253a53')
 d.text((55,300),'Community-made prototype',font=font(18),fill='#50575c')
 bootpic.save(demo/'BOOT2000_64BIT.PNG')
 wallpaper=Image.new('RGB',(1024,768),'#1c6089');d=ImageDraw.Draw(wallpaper)
 d.text((130,320),'Windows 2000 Professional',font=font(38,True),fill='white')
 d.text((130,390),'64-bit edition',font=font(28,True),fill='white')
 wallpaper.save(demo/'WALLPAPER.BMP')
 run('x86_64-w64-mingw32-gcc','-O2','-s','-mwindows',ROOT/'about.c','-o',demo/'ABOUT.EXE','-luser32','-lgdi32')
 run('gcc','-O2',ROOT/'build_iso.c','-o',work/'mkiso','-larchive')
 image=output/'W2K64_AMD64_Community_Prototype.iso'
 run(work/'mkiso',dest,image)
 fixnames(xp,image)
 updated=ISO9660(image)
 missing=set(xp_source.entries)-set(updated.entries)
 if missing:raise ValueError('Missing source paths: '+str(sorted(missing)[:5]))
 changed=[]
 for name,node in xp_source.entries.items():
  if name and not node['flags']&2 and xp_source.read(node)!=updated.read(updated.entries[name]):changed.append(name)
 if changed:raise ValueError('Modified source bytes: '+str(changed[:5]))
 for name in ('AMD64/WOW64CPU.DL_','W2K64/ABOUT.EXE'):
  if name not in updated.entries:raise ValueError('Missing '+name)
 if boot_image(image)!=boot:raise ValueError('Boot image changed')
 print('Verified 7560 original files unchanged; WOW64 and boot catalog intact.')
 xp_source.close();updated.close()
 archive=output/(image.stem+'.tar.xz')
 with tarfile.open(archive,'w:xz',preset=1) as tar:tar.add(image,arcname=image.name)
 with open(archive,'rb') as src:
  i=0
  while block:=src.read(64*1024*1024):
   (output/(archive.name+f'.part{i:02d}')).write_bytes(block);i+=1
 with open(output/'SHA256SUMS.txt','w') as sums:
  for f in [image,archive,*sorted(output.glob('*.part*'))]:sums.write(f'{digest(f)}  {f.name}\n')
 print('ISO:',image,'Parts:',i,'SHA256:',digest(image))
if __name__=='__main__':main()
