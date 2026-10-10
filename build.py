#!/usr/bin/env python3
"""Offline build against installed Eclipse. Python 3 and JDK 21 or newer."""
from pathlib import Path
import argparse, subprocess, zipfile, shutil, re, os
root=Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument('--eclipse',type=Path,default=Path('/Applications/Eclipse JEE.app/Contents/Eclipse'))
parser.add_argument('--output',type=Path,default=root/'dist')
args=parser.parse_args()
plugins=args.eclipse/'plugins'
if not plugins.is_dir():raise SystemExit('Eclipse plugins directory not found')
classpath=os.pathsep.join(str(p) for p in sorted(plugins.glob('*.jar')))
manifest=root/'META-INF/MANIFEST.MF'
version_match=re.search(r'^Bundle-Version: (\S+)$',manifest.read_text(),re.MULTILINE)
if version_match is None:raise SystemExit('Bundle-Version not found in manifest')
version=version_match.group(1)
classes=root/'build/classes'
if classes.exists():shutil.rmtree(classes)
classes.mkdir(parents=True)
subprocess.run(['javac','--release','21','-encoding','UTF-8','-Xlint:all,-path','-cp',classpath,'-d',str(classes),*map(str,sorted((root/'src').rglob('*.java')))],check=True)
args.output.mkdir(parents=True,exist_ok=True)
jar=args.output/f'eclipse.macos.koreanime.styledtext.fix_{version}.jar'
subprocess.run(['jar','--create','--file',str(jar),'--manifest',str(manifest),'-C',str(classes),'.','-C',str(root),'plugin.xml','-C',str(root),'LICENSE'],check=True)
with zipfile.ZipFile(jar) as z:
 assert z.testzip() is None
 for n in z.namelist():
  if n.endswith('.class'):assert int.from_bytes(z.read(n)[6:8],'big')==65
print(jar.resolve())
