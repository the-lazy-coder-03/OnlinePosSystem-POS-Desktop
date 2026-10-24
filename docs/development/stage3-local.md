# Running the Stage 3 local backend

Use JDK 25 and the root Maven wrapper. Default startup still exposes only loopback health without PostgreSQL.
JavaFX remains the Stage 2 CDI setup application; cashier/login/sales screens are Stage 5.

For Windows Master setup, run the unsigned development installer and the JavaFX first-run workflow. The elevated
helper creates the dedicated PostgreSQL database, applies local Flyway migrations and gives the service only its
runtime credential. No catalogue/staff accounts are silently seeded. For planned restarts, stop the backend, restart PostgreSQL, then start the backend and verify API readiness. Backup remains inactive/unseeded.

Create the first staff manager in an elevated console on the configured Master:

```powershell
& 'C:\Program Files\OnlinePOS\setup\Local-Database.ps1' -Operation BootstrapStaff -Username store.manager
```

The tool prompts separately for the installation administrator (minimum 5) and the new staff password
(12–128 characters). This is one-time bootstrap; subsequent staff administration uses a logged-in manager API.
Never enter passwords in command arguments, source files or diagnostic output. Controlled redirected input uses the tool's `--password-stdin` flag; the Windows helper writes UTF-8 directly to its child process input. Interactive use keeps a masked console prompt.

Create an encrypted local backup:

```powershell
& 'C:\Program Files\OnlinePOS\setup\Local-Database.ps1' -Operation Backup
```

Backups stay under protected `C:\ProgramData\OnlinePOS\backups`. DPAPI encryption is machine-bound;
portable disaster recovery and retention are later release gates. Do not delete the Windows key/identity files.
No restore operation overwrites the live database. Stage 3 tests restore a dump into an isolated second database.

## Explicit loopback development

Create a **new disposable** PostgreSQL database `pos_store` owned by a `pos_owner` login, plus a distinct
`pos_runtime` login with CONNECT permission only. Do not use cloud credentials or a production database.
Migrations refuse public business tables, unknown database names and a different installed identity.

Configure environment variables outside the repository:

- `POS_DB_URL` — loopback JDBC URL ending `/pos_store`
- `POS_DB_OWNER_PASSWORD` — owner credential used only by the console migration/bootstrap tool
- `POS_DB_RUNTIME_PASSWORD` — service credential
- `POS_BRANCH_ID` — 1 or 2
- `POS_CLUSTER_ID`, `POS_INSTALLATION_ID` — persistent UUIDs for this disposable installation
- `POS_DEV_TERMINAL` — explicit terminal UUID enabling the loopback-only development API client

Run the packaged backend console migration tool, then bootstrap the first manager by password prompt:

```sh
java -jar store-server/target/store-server-0.1.0-SNAPSHOT-exec.jar --database-tool=migrate
java -jar store-server/target/store-server-0.1.0-SNAPSHOT-exec.jar --database-tool=bootstrap-staff --username=store.manager
java -jar store-server/target/store-server-0.1.0-SNAPSHOT-exec.jar --spring.profiles.active=local-store
```

The `local-store` profile is an explicit, loopback-only development adapter. An empty `POS_DEV_TERMINAL`
leaves staff endpoints inaccessible without a terminal identity. Installed mode ignores this adapter and
requires enrolled TLS certificates. Do not expose development HTTP on a LAN or run it as a Windows service.

## Real database tests

`tools/postgres-test.py` creates its own random-port loopback cluster with SCRAM host authentication and
new owner/runtime credentials. It never adopts an existing cluster. Set `POS_TEST_PG_BIN` to PostgreSQL 16's
binary directory, run it, and load the protected temporary test configuration into the Maven process environment.
In GitHub Actions it exports masked variables automatically.

Required test variables: `POS_TEST_DATABASE_URL` (must end `/pos_stage3_test`), `POS_TEST_OWNER_PASSWORD`,
`POS_TEST_RUNTIME_PASSWORD`, `POS_TEST_PG_BIN`. A second `pos_stage3_restore` database is used only for restore
checks. The helper provisions both. Run `./mvnw -B -ntp clean install`, then stop the disposable cluster with
`pg_ctl -D <temporary-cluster>/data -m fast -w stop` and remove its temporary files when no longer needed.

The database tests deliberately truncate fixture tables in `pos_stage3_test`. Never point their configuration
at an operational database. Skipped database tests are not acceptance evidence.
