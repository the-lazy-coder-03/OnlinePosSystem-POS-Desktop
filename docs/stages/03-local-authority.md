# Stage 3: local PostgreSQL authority

Stage 3 was explicitly authorized on 25 October 2026. This implementation is in the separate
`OnlinePosSystem-POS-Desktop` repository. The operational cloud repository and databases are unchanged.
JavaFX remains the desktop application, using Weld CDI; the backend remains a separate Spring service.
Stage 4 is not authorized or started. No live-store deployment is authorized.

## Delivered scope

- Flyway V1 creates a new **local** `pos` schema. It does not reproduce, migrate or write cloud tables.
- A database is bound to exactly one branch, cluster and installation. All local business records reference
  that branch identity. Installed startup rejects another installation, missing schema or invalid checksums.
- The elevated provisioning helper applies migrations as `pos_owner`; the backend uses `pos_runtime`.
  Runtime cannot create tables, alter installation identity, insert catalogue definitions, rewrite audit
  history, delete outbox history or migrate schemas. Flyway clean is disabled.
- A separate, protected console operation bootstraps the first staff manager exactly once. This is not
  the installation administrator, a website customer or a copied cloud password database.
- Staff passwords use salted PBKDF2-HMAC-SHA256, 600,000 iterations and independent 24-byte salts.
  Staff policy is 12–128 characters. The user-selected **installation administrator minimum remains 5**.
- Managers create/update/disable cashiers and managers. Updates require expected revisions and invalidate
  sessions; removal of the last enabled manager is refused. Persisted login failures lock an account for
  five minutes after five failures. A bounded terminal-level limiter allows ten login requests/minute.
- Sessions use random 256-bit bearer tokens, store only SHA-256 hashes, expire after eight hours, and are
  bound to an enrolled TLS certificate fingerprint. Enrolment/revocation is rechecked on every installed
  request. Logout deletes the session; credential/permission changes invalidate existing sessions.
- Authenticated menu reads and manager price/availability revisions are available. No catalogue editing
  or import endpoint exists. A clean install therefore has an empty catalogue until Stage 4.
- Draft commands use original UUIDs, server-read prices, exact decimal totals, immutable line snapshots,
  revision checks and cancellation. They **do not accept sales**, take payment, issue final receipts or
  implement the complete pizza/burger/special builder. Those require Stage 4 parity and Stage 5 sales.
- Successful price/draft commands commit business changes, command receipt, audit and outbox together.
  Repeating the same command returns its original result. Changed content/origin produces HTTP 409.
  Staff mutations also deduplicate command UUIDs and commit receipts/audit/outbox with their state; stale actors are revalidated under the lock. Password-bearing commands use a slow per-command PBKDF2 proof before hashing their canonical payload, rather than persisting a fast password fingerprint.
- Stream positions are allocated under a branch row lock held until transaction commit. A rolled-back
  mutation leaves no stream gap, command receipt or audit entry. No BIGSERIAL commit-order assumption.
- Inbox receipts are schema foundations only. No inbox receiver, outbox delivery worker or cloud API exists.
- Printer and service metadata explicitly report unavailable printing/sales/cloud/promotion capabilities.
- Master provisioning and repair run migrations before starting the backend; Backup stays inactive/unseeded.
  The backend gets only a DPAPI-protected runtime credential, never the owner/bootstrap bundle.
- An elevated, installation-admin-authenticated backup command writes a custom PostgreSQL dump, encrypts
  it with Windows machine DPAPI and deletes the successful plaintext dump. It uses a protected directory
  and never passes passwords in argv. There is no destructive restore button or automatic data deletion.

## Actual local schema

Defined in `store-server/src/main/resources/db/migration/V1__local_authority.sql`:

| Table | Purpose |
| --- | --- |
| `store_identity`, `stream_head` | Singleton branch/installation binding and serialized event position |
| `staff`, `staff_session` | Local permissions, password verifiers, throttling and terminal-bound sessions |
| `catalog_item`, `branch_price` | Future imported source identities/definitions and local decimal price revisions |
| `local_customer` | Separate local customer identity/address foundation; no customer API yet |
| `order_draft`, `order_line`, `order_line_extra` | Draft identity, references and immutable line/extra foundations |
| `payment_metadata` | Reserved cash/external-card metadata structure; no tender writes or PAN/CVV |
| `command_receipt`, `audit_record`, `outbox_event`, `inbox_receipt` | Durable mutation/deduplication/audit foundations |
| `terminal_printer` | Reserved per-terminal metadata; no print subsystem yet |

Flyway history is in `public.flyway_schema_history`. These are new local structures, not existing cloud tables.
Definitions and effective variants will be fully mapped and parity-tested in Stage 4. Test catalogue fixtures
are created only by integration tests in disposable PostgreSQL databases; production installers seed none.

## Validation

Local `./mvnw -B -ntp install` passed with PostgreSQL 16.15 on macOS ARM64, JDK 25, format, Checkstyle,
architecture rules and all available tests. Ten PostgreSQL integration scenarios cover:

1. Repeat migration, checksum tampering, wrong installation, runtime DDL/audit/delete denial, cross-branch foreign keys and one-time bootstrap.
2. HTTP staff authentication, role and branch checks, login lockout, logout and unavailable sale routes.
3. Command replay, payload collision, stale revisions, fractional-cent and excessive-exponent rejection.
4. Concurrent price edits with exactly one winner and committed stream consistency.
5. Original draft UUID, immutable charged-price snapshots, stale quotes and cancellation/retry.
6. Staff command retries/collisions, session invalidation, stale actor rejection, last-manager protection and verifier redaction.
7. Injected outbox failure rolling back price, audit, command receipt and stream counter together.
8. Actual `pg_dump`/`pg_restore` into a second isolated database, preserving identity, verifiers and command/event state.
9. Exact cents beyond IEEE floating-point integer precision, including saved JSON replay.
10. Registered certificate plus staff token, wrong-terminal token, insecure transport and immediate terminal revocation.

The default build without `POS_TEST_DATABASE_URL` explicitly skips database tests. CI configures real
PostgreSQL on Linux and Windows, so database tests must run there. No H2 substitution is used.

Windows CI additionally launches programmatic JavaFX with CDI, runs the packaged runtime, tests Slave and
unseeded Backup boundaries, provisions/repairs/restarts/uninstalls/reinstalls Master, verifies PostgreSQL
runtime grants, and calls real installed HTTPS staff APIs from a trusted certificate client. Staff bootstrap
and encrypted backup creation are included. Final CI run and artifact evidence are recorded below when complete.

**Release gates:** Windows 10/11 client UAC, reboot and actual multi-machine LAN checks remain unexecuted;
Windows Server hosted CI does not prove those. Installers are unsigned development builds. No production
signing, sales readiness, menu parity, printing, staff JavaFX screens, H2, replication, cloud sync or HA claim.

## Recovery and limitations

The backup command is an early local recovery aid. DPAPI backups require the original Windows machine key;
they are **not** portable/off-node disaster recovery. Stage 12 must supply approved portable encryption,
key escrow, retention, large-database streaming, scheduled/off-node backups and complete restore drills.
The current helper buffers a dump while encrypting; do not use it as a production-scale backup system.
A failed dump/encryption can leave a partial plaintext file under the restricted backup directory; it is not
reported as successful and must be inspected/removed by an administrator. Never delete unresolved business data.

Before upgrades, stop new commands, retain a verified backup and preserve the identity/key files. The helper
stops an existing backend before owner migrations. Migration failure leaves it stopped for diagnosis; retry
uses the same identities and checksum history. Do not edit an applied migration or enable Flyway clean.

Rollback stops the Stage 3 service and retains the additive schema, staff, command receipts, audit and outbox.
Stage 2 binaries can still provide setup/pairing with this retained schema; they cannot serve Stage 3 APIs.
Do not down-migrate/drop tables or restore an older database over newer commands. Validate a dump in a
separate database before considering a controlled restore. Runtime and owner credentials stay separate.
No rollback here changes cloud or branch ownership.

## Review

Draft PR #4 is stacked on Stage 2 PR #3 until that review is merged. Stage 3 ends at review of this change,
its CI artifacts and the remaining release gates. Stage 4 catalogue import needs explicit permission.
