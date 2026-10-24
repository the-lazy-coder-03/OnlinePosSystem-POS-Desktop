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
