# Immutable audit history and owner-controlled tracking

Implemented in OnlinePosSystem-POS-Desktop. Customer data, completed sales, payments, receipt printing and cloud synchronization are not added by this feature.

## Architecture and supported activity

`AuditService` is the recording and query boundary. `CommandJournal` calls it inside the existing business transaction, independently of the always-preserved command receipt and outbox. Authentication and protected provisioning use the same recorder. The database checks policy at insertion, under the existing branch stream lock; changing settings cannot race a business audit decision.

| Optional, initially ON | Mandatory, never switchable |
| --- | --- |
| Draft creation and cancellation | Catalogue/staff bootstrap |
| Catalogue records and catalogue price changes | Staff credentials, roles, permissions and owner changes |
| Successful staff/module login and explicit logout | Audit policy changes, owner PIN approval success/failure, audit access refusals and integrity failures |

A catalogue price change is prospective configuration, not a completed financial transaction adjustment. Disabling its detailed audit entry does not disable the authoritative catalogue state, command receipt or sync outbox. Draft status and totals likewise remain intact. Existing historical events are never removed. Enabled events fail closed: an audit write failure rolls back the business mutation, receipt and outbox together. Command retries return the original receipt; changed content or origin conflicts.

The only implemented order mutations are draft creation/cancellation. There are no fictional sale-completion, refund, cash-drawer, failover or offline-recovery events. `Auditing.PlannedIntegration` and the validated `OfflineEvent` identity/content contract mark these future boundaries; there is deliberately no offline ingest endpoint or new Slave database/queue.

## Migration and database privileges

Flyway `V6__immutable_audit.sql` is additive. It extends `pos.audit_record`, adds event classifications, branch policy/settings and internal approval tables, and adds timestamp, branch, staff, terminal, transaction and action indexes. New events retain server occurrence/receipt time, originating terminal, username snapshot, owner approval identity, transaction/entity identity, correlation and a branch audit sequence. Protected console actions identify that terminal explicitly. Command-origin event IDs are deterministic UUIDs scoped by command, action and entity.

Legacy rows are preserved unchanged. Unknown legacy receipt time, terminal, username snapshot and sequence stay NULL. The detail endpoint withholds historical unvalidated JSON instead of exposing old payload formats. Legacy summary endpoints remain available for compatibility.

The runtime role can read history/settings and execute tightly scoped functions. It cannot directly insert/update/delete/truncate history or modify policy/approval rows. UPDATE, DELETE and TRUNCATE triggers also reject accidental schema-owner edits. Owner-approved policy changes use security-definer functions with a fixed search path, live-session/ownership checks, full event-set validation and revision checks. Test fixture resets temporarily disable only the truncate trigger as the disposable database's schema owner; this is not an application capability. Runtime immutability and schema-owner UPDATE/DELETE/TRUNCATE rejection are tested separately.

The owner/migration credential remains privileged and can technically drop triggers or replace functions. Runtime database credentials and the trusted PIN-verification backend are part of the security boundary; direct access to them is not exposed to JavaFX or Slaves.

## Fresh owner approval

1. An authenticated Administration session stages a complete optional-settings map plus command UUID and expected branch revision.
2. Save requires a new masked owner PIN. The existing salted PBKDF2/HMAC lookup and branch/terminal throttles verify enabled staff, current credentials and designated ownership. Manager permissions alone are insufficient.
3. A fresh internal owner session is marked for audit approval. It is not returned to JavaFX. The database checks that verification is less than 60 seconds old.
4. A single-use approval binds the exact JSON settings, revision, branch, originating terminal, initiating session and owner session. The mutation function rechecks both sessions and current owner identity, consumes the proof, and atomically records approval/configuration events and policy state.
5. The internal owner session is discarded after the operation. Existing receipts allow identical uncertain retries without another mutation. A new save always needs fresh verification.

Wrong PIN/non-owner attempts increment persistent throttles and create mandatory security events. Cooldown rejects repeated guesses without flooding audit history. Invalid-session requests at the settings endpoint are logged with bounded per-terminal rate limiting. Security failure writes commit independently of the rejected operation. No PIN, PIN hash, lookup digest, password, bearer token or raw exception enters audit data, response receipts or command hashing. Both the initiating staff and approving owner IDs survive a successful delegated approval.

The current verification policy is five failed attempts per terminal or thirty per branch in a five-minute window, followed by a five-minute cooldown. Owner PINs share the existing throttling boundary rather than introducing an alternate authentication system.

## API and JavaFX

All routes use the existing authenticated terminal transport and Administration module session:

| Method / route | Access and purpose |
| --- | --- |
| `GET /api/local/admin/audit/types` | Administration; classified supported actions |
| `GET /api/local/admin/audit/settings` | Administration; current branch settings, revision and last approver |
| `PUT /api/local/admin/audit/settings` | Administration initiator plus fresh designated-owner PIN |
| `GET /api/local/admin/audit/events` | `audit.view`; cursor-paginated summaries |
| `GET /api/local/admin/audit/events/{id}` | `audit.view`; read-only allowlisted details |

Settings payload: `commandId`, `expectedRevision`, complete `settings` object keyed by supported optional action, and `ownerPin`. PIN-bearing DTOs redact `toString`. Saving a stale version returns 409; malformed settings return 400; expired sessions/wrong owner credentials return 401/403; throttles return 429. No success notification is shown before confirmed persistence.

History filters: `from`/`to` as instants, exact `terminal`, username snapshot `staff`, transaction UUID `transaction`, `category`, `action`, and opaque `cursor`. Pages are bounded to 50 rows, sorted by occurrence time then UUID. `recordedAt` and branch sequence distinguish Master reception/order from terminal occurrence time for future recovery; terminal clocks are not the ordering authority. Branch scope comes from trusted server identity, never a user-controlled branch selector. There is no export, edit, delete or clear endpoint.

In Administration, **Audit History** opens the viewer. **Security / Audit Tracking** opens **Security → Audit History → Tracking Settings**. Optional toggles, locked mandatory labels, Enable All, Disable Optional Tracking, Save and Cancel are provided. Cancel discards local edits. Save prompts once for the exact batch, clears PIN input, and retains local edits on failure. Conflicts require Reload; known disconnection disables saving/toggles until Reload. Parent navigation still warns about unsaved edits; lock/close clears views and dialogs. All I/O uses worker threads.

The explicit sample preview shows empty audit history and a read-only policy screen. It cannot issue approvals, connect to a store or persist settings. Normal owner checks remain enforced by the backend and database.

## Operations, rollout and recovery

Apply V6 with the existing protected owner migration flow before starting the new backend. Runtime startup validates migrations rather than escalating privileges. Missing PIN provisioning or missing owner designation fails closed. No offline audit-configuration changes are supported.

All enrolled terminals query one trusted Master for policy and history. Temporary loss of that connection makes settings unavailable/read-only. No new automatic Master promotion, cloud event synchronization or recovery queue is claimed. Future offline ingestion must authenticate origin, validate transaction identity, deduplicate the entire event content, preserve original occurrence time separately from Master receipt time, and append rather than overwrite.

Before rollback, take a verified database backup. Roll back application code while retaining additive audit tables, policy and historical rows; never remove V6, rewrite its checksum or clear history. Older binaries which directly insert audit rows are intentionally incompatible with V6's revoked INSERT grant: keep the V6-aware central recorder, or issue a separately reviewed compatibility migration before deploying an older binary. Do not silently restore broad runtime write privileges. Restored backups must retain the policy revision, event types, session invalidation protections and audit triggers, and pass privilege/integrity checks before accepting traffic.

### Stronger tamper resistance design

Local append-only controls do not protect against a compromised schema owner or host administrator. A separate backup/verifier identity should periodically stream canonical event batches, include branch identity, audit sequence range, prior-manifest digest, row count and schema version, and sign each SHA-256 batch manifest with a signing key unavailable to the POS runtime. Anchor manifests and encrypted database backups off-host in object storage with immutable retention/object lock and separate administrator ownership. Handle legacy NULL-sequence rows as an explicitly identified baseline batch.

An independent verifier should compare manifests, gaps and restored batches against off-host anchors; failures must emit mandatory `AUDIT_INTEGRITY_FAILED` through the central service and alert operations, never repair/delete history automatically. The signer, off-host transport, verifier scheduling, retention policy and notification destination are a documented deployment design, not an enabled subsystem in this increment.

## Validation

Run `POS_TEST_FX=true ./mvnw verify` with the protected environment produced by `tools/postgres-test.py` against a newly created disposable PostgreSQL cluster. Never run fixture resets against a live store. The suite checks classifications/defaults, optional suppression, preserved receipts/outbox, rollback on audit failure, owner verification and throttling, API bypass attempts, payload/revision binding, expiry/replay, concurrent saves, privilege enforcement, cursor queries/details/redaction, and offline identity conflicts. GUI tests cover history/details, staged settings, mandatory locks, bulk actions, cancel, incorrect/correct owner PIN, stale versions and disconnected states.

The existing real PostgreSQL dump/restore regression also exercises the additive schema. JavaFX test snapshots are written to `pos-desktop/target/audit-history.png` and `audit-tracking-settings.png`. Windows-only protection/console/installer checks require a Windows environment; macOS test results must not be described as fresh Windows validation.
