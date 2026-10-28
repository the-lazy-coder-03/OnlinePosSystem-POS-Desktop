#!/usr/bin/env python3
"""Explicit macOS/local development setup; source .env is read, never executed."""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / 'runtime/local-store'
PG = Path('/opt/homebrew/opt/postgresql@16/bin/psql')
JAVA = Path(os.environ.get('JAVA_HOME', str(Path.home() / 'Library/Java/JavaVirtualMachines/openjdk-25/Contents/Home'))) / 'bin/java'
JAR = ROOT / 'store-server/target/store-server-0.1.0-SNAPSHOT-exec.jar'


def sql(text, database='postgres'):
    result = subprocess.run([str(PG), '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-d', database],
                            input=text, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('Local PostgreSQL setup failed; no credentials were printed.')
    return result.stdout.strip()


def save(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump(value, output, indent=2)


def setup(branch):
    values = {}
    for line in (ROOT / '.env').read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip().strip('\"\'')
    source = urllib.parse.urlparse(values['SPRING_DATASOURCE_URL'].removeprefix('jdbc:'))
    if source.hostname not in ('localhost', '127.0.0.1') or source.port not in (None, 5432):
        raise RuntimeError('This local setup supports only the local PostgreSQL server on port 5432.')
    STATE.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(STATE, 0o700)
    if (STATE / 'runtime.json').exists():
        raise RuntimeError('Already configured; use start. Existing credentials are never replaced.')
    if sql("select count(*) from pg_database where datname='pos_store';") != '0':
        raise RuntimeError('pos_store already exists; refusing to adopt another installation.')
    if sql('select rolsuper from pg_roles where rolname=current_user;') != 't':
        raise RuntimeError('Local PostgreSQL administrator required for initial provisioning.')
    for role in ('pos_owner', 'pos_runtime'):
        if sql(f"select count(*) from pg_roles where rolname='{role}';") == '0':
            sql(f'CREATE ROLE {role} NOLOGIN;')
    suffix = secrets.token_hex(5)
    owner_user, runtime_user = 'pos_setup_' + suffix, 'pos_app_' + suffix
    owner_password, runtime_password = secrets.token_urlsafe(36), secrets.token_urlsafe(36)
    # Credentials travel only over stdin to psql, never command arguments or output.
    sql(f"CREATE ROLE {owner_user} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '{owner_password}';\n"
        f"GRANT pos_owner TO {owner_user};\n"
        f"CREATE ROLE {runtime_user} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '{runtime_password}';\n"
        f"GRANT pos_runtime TO {runtime_user};\n"
        "CREATE DATABASE pos_store OWNER pos_owner;\n"
        "REVOKE ALL ON DATABASE pos_store FROM PUBLIC;\n"
        f"GRANT CONNECT ON DATABASE pos_store TO {owner_user},{runtime_user};")
    url = 'jdbc:postgresql://127.0.0.1:5432/pos_store'
    common = dict(POS_BRANCH_ID=str(branch), POS_CLUSTER_ID=str(uuid.uuid4()),
                  POS_INSTALLATION_ID=str(uuid.uuid4()), POS_DEV_TERMINAL=str(uuid.uuid4()))
    owner = dict(common, POS_DB_URL=url + '?options=-c%20role%3Dpos_owner',
                 POS_DB_OWNER_USERNAME=owner_user,
                 POS_DB_OWNER_PASSWORD=owner_password)
    runtime = dict(common, POS_DB_URL=url, POS_DB_RUNTIME_USERNAME=runtime_user,
                   POS_DB_RUNTIME_PASSWORD=runtime_password,
                   POS_PIN_KEY=base64.b64encode(secrets.token_bytes(32)).decode())
    save(STATE / 'owner.json', owner)
    save(STATE / 'runtime.json', runtime)
    migrate(owner)
    print('Dedicated Kenridge POS database provisioned.' if branch == 1 else 'Dedicated Uitzicht POS database provisioned.')


def migrate(owner):
    subprocess.run([str(JAVA), '-jar', str(JAR), '--database-tool=migrate'],
                   env={**os.environ, **owner}, check=True, cwd=ROOT)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['setup', 'start', 'migrate', 'desktop'])
    parser.add_argument('--branch', type=int, choices=[1, 2])
    args = parser.parse_args()
    if args.operation == 'setup':
        if not args.branch:
            parser.error('setup requires --branch')
        setup(args.branch)
    elif args.operation == 'migrate':
        migrate(json.loads((STATE / 'owner.json').read_text()))
    else:
        config = json.loads((STATE / 'runtime.json').read_text())
        os.chdir(ROOT)
        if args.operation == 'desktop':
            # JavaFX receives identity only, never database or PIN-key credentials.
            identity = {k: v for k, v in config.items() if k in ('POS_BRANCH_ID', 'POS_CLUSTER_ID', 'POS_INSTALLATION_ID', 'POS_DEV_TERMINAL')}
            os.execve(ROOT / 'mvnw', [str(ROOT / 'mvnw'), '-pl', 'pos-desktop', 'javafx:run',
                      '-Ddesktop.mainClass=za.co.onlinepos.desktop.LocalStoreDesktop'], {**os.environ, **identity})
        os.execve(JAVA, [str(JAVA), '-jar', str(JAR), '--spring.profiles.active=local-store'],
                  {**os.environ, **config})


if __name__ == '__main__':
    main()
