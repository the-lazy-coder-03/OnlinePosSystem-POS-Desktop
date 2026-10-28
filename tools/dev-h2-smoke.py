#!/usr/bin/env python3
"""Real JavaFX -> HTTP -> H2 smoke test using an isolated disposable database."""
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    with tempfile.TemporaryDirectory(prefix='onlinepos-h2-smoke-') as temporary:
        root = Path(temporary)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            port = sock.getsockname()[1]
        java_home = Path(os.environ.get('JAVA_HOME', Path.home() / 'Library/Java/JavaVirtualMachines/openjdk-25/Contents/Home'))
        java = str(java_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if java_home.exists() else 'java'
        args = [java, '-jar', str(ROOT / 'store-server/target/store-server-0.1.0-SNAPSHOT-exec.jar'),
                '--spring.profiles.active=dev', '--server.port=' + str(port),
                '--pos.dev.data-dir=' + str(root / 'data'), '--pos.dev.legacy-dir=' + str(root / 'absent')]
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

        def ready(process):
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('Smoke backend failed; see its temporary log')
                try:
                    config = json.loads((root / 'data/connection.json').read_text())
                    request = urllib.request.Request('http://127.0.0.1:' + str(port) + '/api/local/dev/data-manager/status',
                                headers={'Authorization': 'Bearer ' + config['token']})
                    with opener.open(request, timeout=2) as response:
                        if json.load(response)['instanceId'] == config['instanceId']:
                            return config
                except (OSError, ValueError, KeyError):
                    pass
                time.sleep(0.2)
            raise RuntimeError('Smoke backend startup timed out')

        def stop(process):
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()

        with open(root / 'server.log', 'ab') as log:
            process = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=log)
            try:
                config = ready(process)
                env = {**os.environ, 'POS_TEST_FX': 'true', 'POS_TEST_DEV_CONNECTION': str(root / 'data/connection.json')}
                if java_home.exists():
                    env['JAVA_HOME'] = str(java_home)
                wrapper = str(ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw'))
                subprocess.run([wrapper, '-B', '-ntp', '-pl', 'pos-desktop', 'test', '-Dtest=LocalDataManagerLiveTest,GridModulesLiveTest'],
                               cwd=ROOT, env=env, check=True)
            finally:
                stop(process)
            process = subprocess.Popen(args, cwd=ROOT, stdout=log, stderr=log)
            try:
                reopened = ready(process)
                assert reopened['instanceId'] != config['instanceId']
                request = urllib.request.Request('http://127.0.0.1:' + str(port) + '/api/local/dev/data-manager/categories?state=all',
                            headers={'Authorization': 'Bearer ' + reopened['token']})
                with opener.open(request, timeout=2) as response:
                    records = json.load(response)['records']
                records=[r for r in records if r['name']=='H2 UI persisted']
                assert len(records) == 1 and records[0]['name'] == 'H2 UI persisted' and records[0]['active']
                assert records[0]['sortOrder'] == 7
                request=urllib.request.Request('http://127.0.0.1:'+str(port)+'/api/local/dev/data-manager/grid/customers?query=Grid+UI',headers={'Authorization':'Bearer '+reopened['token']})
                with opener.open(request,timeout=2) as response:
                    customers=json.load(response)['items']
                assert len(customers)==1
                request=urllib.request.Request('http://127.0.0.1:'+str(port)+'/api/local/dev/data-manager/grid/customers/'+customers[0]['id']+'/history',headers={'Authorization':'Bearer '+reopened['token']})
                with opener.open(request,timeout=2) as response:
                    assert len(json.load(response)['items'])==1
                print('REAL_H2_JAVAFX_AND_BACKEND_RESTART_OK')
            finally:
                stop(process)


if __name__ == '__main__':
    main()
