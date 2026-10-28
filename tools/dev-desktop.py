#!/usr/bin/env python3
"""Launch the real H2 development backend and JavaFX grid, without exposing database credentials."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def data_root():
    if sys.platform == 'darwin':
        return Path.home() / 'Library/Application Support/OnlinePOS/dev'
    if os.name == 'nt':
        return Path(os.environ.get('LOCALAPPDATA', Path.home() / 'AppData/Local')) / 'OnlinePOS/dev'
    return Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local/share')) / 'OnlinePOS/dev'


def verified(root):
    try:
        config = json.loads((root / 'connection.json').read_text())
        if config['port'] != 18091 or len(config['token']) < 32:
            raise RuntimeError('Invalid development connection metadata')
        request = urllib.request.Request('http://127.0.0.1:18091/api/local/dev/data-manager/status',
                    headers={'Authorization': 'Bearer ' + config['token']})
        # No environment proxy may receive the development bearer credential.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=2) as response:
            status = json.load(response)
        if (status['application'], status['profile'], status['database'], status['connection'], status['instanceId']) != (
                'onlinepos-data-manager', 'dev', 'H2', 'CONNECTED', config['instanceId']):
            raise RuntimeError('Unexpected backend identity')
        return config
    except (OSError, ValueError, KeyError):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data-dir', type=Path)
    parser.add_argument('--no-build', action='store_true')
    parser.add_argument('--backend-only', action='store_true')
    args = parser.parse_args()
    root = args.data_dir or data_root()
    if not root.is_absolute():
        parser.error('--data-dir must be absolute')
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    if os.name != 'nt':
        root.chmod(0o700)
    os.umask(0o077)
    java_home = Path(os.environ.get('JAVA_HOME', Path.home() / 'Library/Java/JavaVirtualMachines/openjdk-25/Contents/Home'))
    env = dict(os.environ)
    if java_home.exists():
        env['JAVA_HOME'] = str(java_home)
    wrapper = str(ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw'))
    if not args.no_build:
        subprocess.run([wrapper, '-B', '-ntp', '-pl', 'store-server,pos-desktop', '-am', 'install', '-DskipTests'],
                       cwd=ROOT, env=env, check=True)
    config = verified(root)
    backend = None
    log = None
    try:
        if config is None:
            with socket.socket() as probe:
                if probe.connect_ex(('127.0.0.1', 18091)) == 0:
                    raise RuntimeError('Port 18091 is occupied by an unverified backend. Stop it before launching development mode.')
            log = open(root / 'backend.log', 'ab', buffering=0)
            java = str(java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home.exists() else 'java'
            backend = subprocess.Popen([java, '-jar', str(ROOT / 'store-server/target/store-server-0.1.0-SNAPSHOT-exec.jar'),
                        '--spring.profiles.active=dev', '--pos.dev.data-dir=' + str(root),
                        '--pos.dev.legacy-dir=' + str(ROOT / 'runtime/h2')], cwd=ROOT, env=env, stdout=log, stderr=log)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                if backend.poll() is not None:
                    raise RuntimeError('Development backend failed. See the protected backend.log; no existing data was deleted.')
                config = verified(root)
                if config:
                    break
                time.sleep(0.3)
            if config is None:
                raise RuntimeError('Timed out waiting for the development backend')
        print('Verified H2 development backend. Database directory:', root, flush=True)
        if args.backend_only:
            if backend:
                backend.wait()
            return
        # Only the API credential reaches JavaFX, never the H2 password.
        safe_env = {k: v for k, v in env.items() if not k.startswith(('POS_DB_', 'POS_H2_', 'SPRING_DATASOURCE_'))}
        desktop_env = {**safe_env, 'POS_DESKTOP_PROFILE': 'dev', 'POS_DEV_API_TOKEN': config['token'],
                       'POS_DEV_INSTANCE': config['instanceId'], 'POS_DEV_PORT': str(config['port'])}
        subprocess.run([wrapper, '-pl', 'pos-desktop', 'javafx:run',
                        '-Ddesktop.mainClass=za.co.onlinepos.desktop.DevDesktop'], cwd=ROOT, env=desktop_env, check=True)
    finally:
        if backend and backend.poll() is None:
            backend.terminate()
            try:
                backend.wait(timeout=10)
            except subprocess.TimeoutExpired:
                backend.kill()
                backend.wait()
        if log:
            log.close()


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as failure:
        print('Development launch failed:', str(failure), file=sys.stderr)
        sys.exit(1)
