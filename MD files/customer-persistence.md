# Persistent Customers

Project: **OnlinePosSystem-POS-Desktop**. Live website/customer synchronization is disabled.

## Local authority

Installed Customers opens through the PIN-protected Customers module. JavaFX calls the authenticated Master backend; it never connects to PostgreSQL. A new installation has an empty real directory. The isolated preview uses fictional profiles in memory and never writes to the Master.

Profiles use the SQL customer fields, UUID identities and optimistic revisions. A first or last name and primary phone are required. South African `0…` numbers normalize to `+27…`; explicit international prefixes are preserved. Email is optional and normalized to lowercase. Primary phone and nonempty email are unique among canonical local identities. Existing ambiguous legacy phones become Account Conflicts without deleting either profile.

The list displays Address. Country, province, latitude and longitude remain hidden and survive edits. Preferred store is read-only: new local profiles use the current branch; subsequent changes require the future authenticated website integration. Contact details and ban reasons are protected customer data, excluded from general audit details.

Active/Archived is branch-local. Archive, restore and ban are separate states. Ban requires a reason; unban does not. Neither archiving nor restoring clears a ban. Local bans immediately reject new identified-customer drafts. Website and other branches remain **unconfirmed** until a future authenticated transport acknowledges them.

## Migration and write boundaries

`V9__persistent_customers.sql` adds profile JSON, normalized contacts, trusted server field timestamps, identity mappings/aliases, archive/ban state and history, conflicts and durable customer outbox status to `pos.local_customer`. Existing customer UUIDs, revisions and draft foreign keys remain intact. Legacy contact/address values are preserved; unavailable historical timestamps remain NULL/empty. Migration collisions are review work rather than an implicit merge.

The runtime role cannot directly insert/update customer profiles or mutate conflict, alias, status or pending tables. Session-checking database functions require a live Customers session and the specific action permission. Backend commands serialize on the existing branch stream lock, verify expected revisions, and commit business changes, command receipts, audit and outbox records together. Failed required persistence rolls the transaction back. Identical command retries return the receipt; changed payloads or origins with the same command UUID fail.

Five configurable permissions are initially granted to Managers: `customers.view`, `customers.manage`, `customers.archive`, `customers.ban`, `customers.resolve`. Module entry permission is additionally required. Administration role management can assign these to other roles; changing permissions or credentials takes effect on subsequent requests and database writes.

Customer create/update/archive/restore audit types are optional, ON by default. Ban/unban, conflict resolution and incoming identity application are mandatory. Disabling optional audit does not disable receipts, customer state, revisions or outbox creation.

## Local API

All routes require the authenticated terminal and Customers module bearer session. Scope is fixed to the Master's branch.

| Method and suffix under `/api/local/customers` | Purpose / permission |
| --- | --- |
| GET `/context` | Branch, permissions, disabled sync status / view |
| GET `?query=&archived=false&cursor=` | Name/contact search; 50 rows, opaque nextCursor / view |
| GET `/{uuid}` | Profile, lifecycle, mappings, revision / view |
| PUT `/{uuid}` | `{commandId, customerId, expectedRevision, fields}`; new revision -1 / manage |
| PUT `/{uuid}/archive` | `{commandId, expectedRevision, value, reason}` / archive |
| PUT `/{uuid}/ban` | Same shape; reason required for true / ban |
| GET `/{uuid}/history?page=0` | Linked drafts including cancellations; status history / view |
| GET `/lookup?phone=` | Exact normalized local phone; absent remote reports REMOTE_UNAVAILABLE / view |
| GET `/conflicts?page=0` | Open protected Account Conflicts / resolve |
| PUT `/conflicts/{uuid}` | `{commandId, expectedRevision, action, targetCustomerId, correctedFields, customerRevision}`; LINK or SEPARATE / resolve |
| GET `/sync` | Disabled adapter, pending count and explicit unconfirmed propagation / view |

HTTP 400 indicates invalid input, 401 invalid session, 403 missing permission, 404 missing record, 409 identity/revision/retry conflict and 503 unavailable persistence. Reload after stale revisions; do not overwrite another terminal's edit. SEPARATE resolution checks both the conflict revision and the reviewed customerRevision (new profiles use -1).

`LocalCommands.CreateDraft` accepts optional `customerId` while retaining the anonymous three-argument constructor and prior anonymous command hashes. Identified drafts additionally require `customers.view`. The database resolves aliases, validates status in the draft transaction and captures a customer snapshot. Historical links and snapshots are immutable. Later status changes do not cancel existing drafts. History represents implemented drafts, never completed sales/payments.

## Prepared customer synchronization contract

`CustomerSync` is shared contract/merge logic. `CustomerSyncStore` adapts existing durable outbox/inbox foundations; neither is registered as an application worker or exposed through a sync ingestion endpoint. No website project, production connector, scheduler or Slave database is added.

A future transport must authenticate its remote origin, return trusted backend UTC time and globally unique events, and supply opaque ordered checkpoints. Checkpoints and content digests persist in inbox receipts. Duplicate IDs with different content are rejected. Outgoing events retain authenticated staff/terminal/branch origin, profile revision and global identity; acknowledgements bind event/content and retain remote reference/mappings. An unacknowledged cloud link remains pending.

Directory membership uses preferred branch, not orders. Incoming unrelated other-branch profiles are skipped with durable receipt; known customers moving branch leave the normal directory. Exact visiting-customer lookup does not change preferred store. A single unambiguous primary-phone match links; phone/email disagreement or ambiguity creates protected Account Conflicts and pauses application. Manager resolutions preserve original UUIDs, aliases and historical draft references.

Field precedence uses trusted backend timestamps: later wins, cloud wins ties. Structured address fields form one group; ban status/reason form one change. A measured clock difference greater than two minutes rejects an exchange before applying or sending data and leaves queued changes intact. The future connector must surface this clock failure operationally; current production UI reports the connector disabled rather than fabricating remote health.

## Launch, validation and recovery

Use configured JDK 25 and `./mvnw verify`. Start the installed Master using the existing installation/run instructions, then enter Customers from the grid with an authorised staff PIN. For fictional-only preview follow [desktop preview](desktop-preview.md). Never point disposable integration tests at a real store database.

PostgreSQL integration tests use the actual `pos_runtime` grants and isolated PostgreSQL 16; GUI tests require `POS_TEST_FX=true`. Coverage includes legacy migration/reference preservation, normalized collisions, CRUD/retries/stale revisions, bans/archive, draft snapshots/history, rollback, protected SQL writes, fake remote contracts, durable inbox/outbox acknowledgement, restart and backup/restore. See the final delivery report for checks executed and any unavailable platform validation.

For deployment, take a verified database backup and apply migrations through the existing schema-owner migration path; runtime credentials cannot migrate. Application rollback must retain V9's additive schema, original UUIDs, customer records, aliases, receipts, queues and audit history. Do not reverse migrations, clear queues or delete audit data. An older application may require a compatibility build because customer direct writes are intentionally revoked and audit policy now has additional optional categories. Restore a backup only through the established recovery process with explicit recovery scope; it is not the normal application rollback.

Live website delivery, cloud authentication/API implementation, automated synchronization, recovery transport, multi-store confirmation and Windows operational acceptance remain pending. No completed sales, payments or credit accounts are introduced.

## Verified delivery — 26 October 2026

- JDK 25, full Maven reactor `spotless:apply verify` with `POS_TEST_FX=true` and isolated PostgreSQL 16: **138 tests, 136 passed, 2 platform skips, zero failures/errors**.
- Actual runtime-privilege PostgreSQL tests: 51 passed, including nine customer-specific scenarios; migration-upgrade tests: four passed, including V8 customer/draft preservation and legacy collisions.
- JavaFX customer tests: two passed, plus Customers grid/PIN navigation. Verified creation/validation, hidden fields and preferred-store control, disconnected retry, search, resizing, ban reason, archive state and read-only history. Rendered customer page visually reviewed.
- Formatting and Checkstyle/static analysis passed. Secret scan reported no leaks; diff whitespace check passed.
- Windows redirected administrator input and DPAPI tests were skipped on macOS. Windows operational/printing acceptance and real website integration were not run.
- Only a disposable loopback PostgreSQL cluster was migrated/restarted/restored and then removed. Existing installed-store databases were not migrated or seeded by this development run.
