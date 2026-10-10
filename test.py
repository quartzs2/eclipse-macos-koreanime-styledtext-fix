#!/usr/bin/env python3
"""SWT/JFace integration scenarios. Native IME keyboard checks are separate."""
from pathlib import Path
import argparse, subprocess
root=Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument('--eclipse',type=Path,default=Path('/Applications/Eclipse JEE.app/Contents/Eclipse'))
args=parser.parse_args()
classpath=':'.join([str(root/'build/classes'),*map(str,sorted((args.eclipse/'plugins').glob('*.jar')))])
dest=root/'build/test-classes';dest.mkdir(parents=True,exist_ok=True)
subprocess.run(['javac','--release','21','-encoding','UTF-8','-cp',classpath,'-d',str(dest),*map(str,(root/'tests').rglob('*.java'))],check=True)
raise SystemExit(subprocess.run(['java','-XstartOnFirstThread','-cp',str(dest)+':'+classpath,'korean.ime.styledtext.KoreanImeFixTest']).returncode)
