#!/usr/bin/env python3
"""설치된 Eclipse 라이브러리로 오프라인 빌드합니다. Python 3과 JDK 21 이상이 필요합니다."""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument(
    "--eclipse",
    type=Path,
    default=Path("/Applications/Eclipse JEE.app/Contents/Eclipse"),
)
parser.add_argument("--output", type=Path, default=root / "dist")
args = parser.parse_args()

plugins = args.eclipse / "plugins"
if not plugins.is_dir():
    raise SystemExit("Eclipse plugins directory not found")

classpath = os.pathsep.join(str(path) for path in sorted(plugins.glob("*.jar")))
manifest = root / "META-INF/MANIFEST.MF"
version_match = re.search(
    r"^Bundle-Version: (\S+)$",
    manifest.read_text(),
    re.MULTILINE,
)
if version_match is None:
    raise SystemExit("Bundle-Version not found in manifest")

version = version_match.group(1)
classes = root / "build/classes"
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir(parents=True)

sources = sorted((root / "src").rglob("*.java"))
subprocess.run(
    [
        "javac",
        "--release", "21",
        "-encoding", "UTF-8",
        "-Xlint:all,-path",
        "-cp", classpath,
        "-d", str(classes),
        *map(str, sources),
    ],
    check=True,
)

args.output.mkdir(parents=True, exist_ok=True)
jar = args.output / f"eclipse.macos.koreanime.styledtext.fix_{version}.jar"
subprocess.run(
    [
        "jar", "--create",
        "--file", str(jar),
        "--manifest", str(manifest),
        "-C", str(classes), ".",
        "-C", str(root), "plugin.xml",
        "-C", str(root), "LICENSE",
    ],
    check=True,
)

with zipfile.ZipFile(jar) as archive:
    assert archive.testzip() is None
    for name in archive.namelist():
        if name.endswith(".class"):
            assert int.from_bytes(archive.read(name)[6:8], "big") == 65

print(jar.resolve())
