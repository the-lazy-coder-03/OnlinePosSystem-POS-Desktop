#!/usr/bin/env python3
"""Start the file-backed H2 development schema on loopback port 18091."""
import json
import os
from pathlib import Path
import secrets

os.umask(0o077)
root = Path(__file__).resolve().parents[1]
state = root / 'runtime/h2'
state.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(state, 0o700)
credential = state / 'credentials.json'
if not credential.exists():
    fd = os.open(credential, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump({'password': secrets.token_urlsafe(36)}, output)
password = json.loads(credential.read_text())['password']
java = Path(os.environ.get('JAVA_HOME', str(Path.home() / 'Library/Java/JavaVirtualMachines/openjdk-25/Contents/Home'))) / 'bin/java'
jar = root / 'store-server/target/store-server-0.1.0-SNAPSHOT-exec.jar'
os.chdir(root)
os.execve(java, [str(java), '-jar', str(jar), '--spring.profiles.active=h2-development'],
          {**os.environ, 'POS_H2_PASSWORD': password})
