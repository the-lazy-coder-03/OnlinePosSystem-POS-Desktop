"""Disposable PostgreSQL test cluster; never uses an existing database or store installation."""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile

bin_dir = Path(os.environ["POS_TEST_PG_BIN"])
root = Path(tempfile.mkdtemp(prefix="onlinepos-stage3-"))
if os.name == "nt":
    user = subprocess.check_output(["whoami"], text=True).strip()
    subprocess.run(["icacls", str(root), "/inheritance:r", "/grant:r", user + ":(OI)(CI)F", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F"], check=True, stdout=subprocess.DEVNULL)
else:
    root.chmod(0o700)

def run(name, args, **kwargs):
    command = [str(bin_dir / (name + (".exe" if os.name == "nt" else "")))] + args
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL, **kwargs)

with socket.socket() as connection:
    connection.bind(("127.0.0.1", 0))
    port = connection.getsockname()[1]
bootstrap, owner, runtime = [secrets.token_urlsafe(24) for _ in range(3)]
password_file = root / "init.password"
password_file.write_text(bootstrap)
try:
    run("initdb", ["-D", str(root / "data"), "--username=pos_test_bootstrap", "--auth-host=scram-sha-256", "--auth-local=trust", "--encoding=UTF8", "--locale=C", "--pwfile=" + str(password_file)])
finally:
    password_file.unlink()
# A unique, private directory and random port, listening only on loopback.
options = "-h 127.0.0.1 -p " + str(port)
if os.name != "nt":
    options += " -k " + str(root)
run("pg_ctl", ["-D", str(root / "data"), "-l", str(root / "postgres.log"), "-o", options, "-w", "start"])
sql = "CREATE ROLE pos_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '" + owner + "';CREATE ROLE pos_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '" + runtime + "';CREATE DATABASE pos_stage3_test OWNER pos_owner;CREATE DATABASE pos_stage3_restore OWNER pos_owner;"
environment = dict(os.environ, PGPASSWORD=bootstrap)
run("psql", ["-X", "-h", "127.0.0.1", "-p", str(port), "-U", "pos_test_bootstrap", "-d", "postgres", "-v", "ON_ERROR_STOP=1"], input=sql, text=True, env=environment)
settings = {"POS_TEST_DATABASE_URL": "jdbc:postgresql://127.0.0.1:" + str(port) + "/pos_stage3_test", "POS_TEST_OWNER_PASSWORD": owner, "POS_TEST_RUNTIME_PASSWORD": runtime, "POS_TEST_PG_BIN": str(bin_dir), "POS_TEST_CLUSTER_ROOT": str(root)}
if os.environ.get("GITHUB_ENV"):
    for password in (bootstrap, owner, runtime):
        print("::add-mask::" + password)
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as target:
        for key, value in settings.items():
            target.write(key + "=" + value + "\n")
else:
    target = root / "test-configuration.json"
    target.write_text(json.dumps(settings))
    if os.name != "nt":
        target.chmod(0o600)
print("Disposable PostgreSQL test cluster ready (loopback only).")
