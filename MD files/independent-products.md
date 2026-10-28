# Independent POS and cloud foundations

Status: 28 October 2026. This is a partially implemented increment, not a commercial deployment readiness claim.

## Running and storage

Use JDK 25 and `./scripts/run-dev-desktop.sh` from OnlinePosSystem-POS-Desktop. Development uses the existing loopback-only bearer-protected backend and persistent H2 under the platform application-data OnlinePOS/dev directory. Settings → Store Management operates on that Master's assigned store only. Existing imported databases require explicit adoption; a fresh empty database supports generated store creation. H2 development actions use a development actor, not a fabricated Owner.

The installed PostgreSQL path retains its existing local staff/PIN/module sessions. Administration → Store Management requires the designated local Owner. Changing profile details does not reassign the terminal or historical transactions.

## APIs

- Development `/api/local/dev/data-manager/grid/store`: GET current setup; POST initial business/store with commandId, businessName, storeName, code; POST `/adopt` with commandId, branchId, businessName; PUT details with expectedRevision. Development-only bearer and loopback restrictions apply.
- Installed `/api/local/admin/store`: GET profile, POST/PUT revisioned profile update with commandId, expectedRevision, businessName, name, code, address, phone, email, active. Requires authenticated Administration module session and designated ownership. Never send PINs in these payloads.
- Cloud `/api/branches`: active store IDs/names for the existing website.
- Cloud `/api/business-management/stores`: optional management API, disabled unless app.business-management.enabled=true. GET lists deployment stores; POST accepts commandId, businessId, optional storeId/expectedRevision, and values. Existing signed database actor context and explicit business-owner designation are mandatory. This is not a first-Owner bootstrap interface.

Catalogue category records accept optional parent_id. Missing parent/archive fields remain compatible with old snapshots. Cycles are rejected and ancestor unavailability blocks new quotes. Existing records are preserved.

## Migration and rollback

POS H2 migrations V8–V10 and PostgreSQL V12–V16 are additive. Cloud empty-schema-v1 is used for new migration histories; existing migration.sql checksums are retained and verified. commercial-identity-v1 adds deployment/business/store metadata and restricted management functions. Existing historical names and times are not fabricated.

No live PostgreSQL migration, cloud deployment or user H2 catalogue reset was performed. Back up with the relevant backend stopped for embedded H2, retaining its credential file and database together in protected storage. PostgreSQL backups must include schema, data and deployment keys using the existing operational procedure. A restore must retain business/store/terminal identity; never reuse a store's database or credentials for another store. Restore verification and the deliberate one-time H2 reset workflow remain outstanding.

Application rollback retains additive schema, IDs, command receipts and all audit/history records. Do not truncate operational tables or edit already-applied migrations. New application writes may require the new schema, so roll back binaries with compatible configuration and a verified backup.

## Synchronization and security limits

Integration remains disabled. The HLC contract orders timestamp, counter, source and operation UUID, validates allowlisted configuration fields, and separates security data from configuration. Tables and contracts do not constitute a working merger, transport or security refresh implementation. CashUpStarted is a typed contract only; Cash Management is unfinished.

LOCAL credentials must never enter configuration changes. Cloud Account provisioning, destination-bound verifier encryption, protected key storage, central revocations and 24-hour offline authorization are not implemented. Do not enable production Cloud Account access on these foundations.

The first cloud Owner still requires a protected deployment bootstrap flow; no website customer or generic administrator is silently promoted. No new default credentials are seeded. Initial full catalogue replacement, backup verification, pairing invitations, terminal keys and routine incremental synchronization remain unavailable.

## Verification

Isolated H2 tests cover empty setup, explicit legacy adoption, restart persistence, nested category availability/cycle rollback and real JavaFX/HTTP/H2 workflows. PostgreSQL tests use the actual runtime role and isolated databases. Cloud tests exercise the legacy migration path and explicit-owner store creation without changing orders. Check the module status and test output for exact available checks. Native Windows/security-store acceptance and complete independent-products end-to-end acceptance remain unavailable or unfinished.

Latest isolated validation: cloud unit tests (240) and PostgreSQL/RLS tests (26) passed. Fresh cloud database contains zero branches, customers and menu items. The real H2 JavaFX/HTTP workflow and backend-restart persistence check passed after the store-profile and pricing updates. No native Windows checks were available.


## LOCAL authentication backend increment

PostgreSQL V16 adopts existing staff password and PIN hashes verbatim into `pos.staff_auth_method`, retaining staff UUIDs and credential versions. Existing controlled provisioning remains the write authority; database triggers keep the method structure synchronized and invalidate sessions. Runtime has read-only access to method rows. Account provenance (`LOCAL`/`CLOUD`) is separate from role. Until central security distribution is implemented, CLOUD rows cannot sign in through local password/PIN routes.

Module PIN requests accept optional `staffId` alongside `pin` and `module`, preserving earlier requests without staffId. Multiple eligible identities without exact selection are rejected; disabled or wrong identities cannot be selected. This backend capability does not yet provide the employee-selection UI. PIN-only LOCAL credentials retain per-store uniqueness. Shared verifier code keeps the existing salted 600,000-iteration PBKDF2 format. Provider interfaces are ready for future fingerprint/RFID/NFC implementations; no such provider or card-UID authentication is active.

H2 V10 creates empty local staff, method, Owner, session and throttle structures. `H2LocalAuthentication` implements trusted initial Owner setup, fresh-Owner staff creation/editing, unique PINs, revisioned/idempotent writes, exact identity verification, role checks, five-minute module sessions, terminal/module binding, logout and immediate version revocation. Fresh Owner sessions expire after 60 seconds. It commits failed-attempt counters separately from rejected-owner audit persistence and rolls successful sessions back if required history cannot be stored. Terminal and branch cooldowns persist in H2; repeated cooldown requests increment an aggregate counter.

**The H2 core is not exposed as an unauthenticated provisioning API, and the development grid still uses its explicit development actor.** Connecting the existing setup/staff screens and enforcing H2 sessions across every module route is the next implementation step. Do not describe the current H2 grid as authenticated.

The H2 development verifier lookup key is `local-pin.key` in the protected application-data directory, separate from database/bearer credentials. Include it in protected backups with the database and credentials. Existing accounts with a missing, malformed or symlinked key fail closed; the application does not silently generate replacement verification material. This development file is not the future device-bound Cloud Account key store. LOCAL verifier material must never enter configuration sync or cloud payloads.
