# Stage 1 contracts

## Implemented

`Branch` preserves 1 Kenridge and 2 Uitzicht. Unknown IDs fail validation.
`InstallationRole` defines MASTER, SLAVE and BACKUP_MASTER; the first-run preview allows only the first two.
Setup selections are transient; no role configuration or pairing credential is persisted.

`OrderIdentity` consists of `sync_id` (non-nil UUID) and `branchId`. The origin generates a UUID v4 once;
deserialization preserves an existing UUID without regeneration. No database numeric key enters this type.
`CommandIdentity` includes non-nil command/terminal UUIDs, order identity and nonnegative expected revision.
Commands must retain the same identity for retries. There is no accepting command endpoint or durable
deduplication store yet; those belong to Stage 3.

`Money` uses BigDecimal with canonical scale 2 and rejects fractional cents that would need rounding.
It has no double constructor. Negative amounts are valid for accounting differences, not permission to
accept negative-priced sales. Pricing/quantity/payment validation is added with business operations.

The only public local endpoint is `GET /api/local/health`:

```json
{
  "protocolVersion": 1,
  "service": "onlinepos-store-server",
  "status": "UP",
  "role": "UNCONFIGURED",
  "acceptingSales": false,
  "cloudSyncEnabled": false,
  "automaticPromotionEnabled": false
}
```

HTTP 200 proves process liveness only. Responses use Cache-Control: no-store.
All other requests are denied by Spring Security; there is no generated password, login endpoint,
database, role provisioning, order submission, public actuator endpoint or cloud client.
The process binds to 127.0.0.1:18090 by default. Do not bind it to a LAN before Stage 2 TLS/pairing.

## Reserved later contracts

- Stage 3: authenticated branch commands, complete typed order snapshots, revision checks and persisted
  command receipts committed with business state, audit and outbox.
- Stage 7: original emergency UUID/payload hash, price/payment/fulfilment snapshots and durable import
  receipt; differing payloads cannot overwrite a matching identity.
- Stage 9: cloud snapshot, exchange and heartbeat under `/api/store-sync/v1/branches/{branchId}`.
  Exchange envelopes need protocol version, event/command UUID, branch, origin, aggregate UUID,
  revision, ordered stream position and payload hash. Use UTC timestamps and decimal money.
- Shared streams must serialize commit ordering. A BIGSERIAL assignment alone cannot provide a safe
  replay watermark. Notifications only wake durable replay; no business event relies on transient delivery.

These later interfaces are design commitments, not runnable APIs in Stage 1.

## Stage 2 extension

The loopback development profile retains the Stage 1 contract. The installed Windows service uses
HTTPS and authenticated terminal certificates. It adds GET /api/local/identity,
GET /api/local/diagnostics and POST /api/local/setup/pair. Pairing requires an unexpired ten-minute
administrator invitation pinned to the branch CA, with terminal key proof and one-time consumption.
Replay is rejected. Revocation is checked on each authenticated request. No LAN management API exists.
Installation state now persists version, installation/cluster/terminal UUIDs, branch, role, terminal name
and provisioning state; credentials live separately in DPAPI files. See stage2-cdi.md for ownership.
Business command APIs remain unavailable; acceptingSales, cloudSyncEnabled and automaticPromotionEnabled
remain false in all installed health responses.

## Stage 3 local authority APIs

Installed business requests require an enrolled TLS terminal and a terminal-bound staff bearer token.
`POST /api/local/auth/login` requires the terminal plus username/password; it returns a token valid for eight
hours. `POST /api/local/auth/logout` invalidates it; `GET /api/local/auth/me` returns the current local staff
identity. No cookies or customer credentials authorize these APIs.

| API | Scope |
| --- | --- |
| `GET /api/local/menu` | Read the installed branch's confirmed catalogue/variants (empty before Stage 4) |
| `GET /api/local/branches/{branch}/menu` | Explicit branch-checked equivalent |
| `PATCH /api/local/branches/{branch}/prices/{priceId}` | Manager: command UUID, expected revision, exact amount, availability |
| `POST /api/local/branches/{branch}/drafts` | Command UUID, original order UUID, unique line UUIDs, price IDs/revisions and quantities |
| `GET /api/local/branches/{branch}/drafts/{orderId}` | Read persisted draft and immutable line snapshots |
| `POST /api/local/branches/{branch}/drafts/{orderId}/cancel` | Command UUID plus expected draft revision |
| `GET /api/local/staff` | Manager: redacted account list |
| `PUT /api/local/staff` | Manager: command/staff UUIDs, username, role, enabled, expected revision, optional new password |
| `GET /api/local/printer` | Current terminal, printing unavailable |
| `GET /api/local/configuration` | Installed branch/cluster and explicitly disabled business capabilities |

`LocalCommands` is container-independent Java. Monetary input rejects fractional cents and negative prices.
Draft creation prices from PostgreSQL, never a client total. Same UUID/content/origin retries return the
committed result; UUID reuse with changed content/origin or stale revisions returns 409. Unknown/malformed
input returns 400, missing staff session 401, cross-branch/permission errors 403, missing draft 404.

Price and draft commands lock the branch stream row through commit and write receipt, audit and outbox in
the same transaction. Canonical payload hashing sorts object keys and preserves decimal precision. Staff
administration also deduplicates commands, serializes and invalidates stale sessions; a concurrent already-authenticated write
rechecks staff version/role while holding the stream lock.

No `/api/local/orders` sales endpoint, payment, staff JavaFX screens, catalogue import, cloud exchange,
outbox sender or promotion endpoint exists. Health remains liveness with `acceptingSales=false`. Installed
diagnostics reports local database readiness without connection strings, password hashes or tokens.

## Stage 4 catalogue contract

Authenticated manager POST `/api/local/branches/{branch}/catalogue/bootstrap` consumes a protocol-1
`CatalogueSnapshot.Import` with command UUID, reviewed envelope SHA-256 and complete catalogue snapshot.
Authenticated GET `/api/local/branches/{branch}/catalogue` returns retained baseline evidence plus current
local effective prices. See `../stages/04-catalogue-bootstrap.md` for mapping, replay, cutover and rollback.
No operational cloud export endpoint is introduced. The exporter reads an approved isolated restore.
