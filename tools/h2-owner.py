#!/usr/bin/env python3
"""Enrol the first H2 development owner through an interactive local console; stop its backend first."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('data_dir', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
if not args.data_dir.is_absolute() or not (args.data_dir / 'onlinepos.mv.db').is_file():
    parser.error('Supply the absolute existing development data directory containing onlinepos.mv.db')
if not sys.stdin.isatty():
    parser.error('An interactive terminal is required; PINs cannot be passed as arguments')
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home else 'java'
jar = root / 'store-server/target/store-server-0.1.0-SNAPSHOT-exec.jar'
if not jar.is_file():
    parser.error('Build store-server first')
raise SystemExit(subprocess.call([java, '-Dloader.main=za.co.onlinepos.server.H2OwnerTool',
    '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', str(args.data_dir)], cwd=root))
