# Configurable Sales readiness baseline

Baseline inspected 2026-10-29; delivery verified 2026-10-30. Preserve all existing staged and uncommitted work.

## Migration inventory

The installed Master at 192.168.1.16 was queried using a READ ONLY transaction. The store service was stopped; PostgreSQL was running. No migration was applied.

| Applied version | Description | Flyway checksum |
|---|---|---|
| 1 | local authority | 705883066 |
| 2 | catalogue bootstrap | 1463029665 |
| 3 | module rbac | 1771422558 |
| 4 | administration | -742917607 |
| 5 | catalogue management | -1382202340 |
| 6 | immutable audit | -340618992 |

Repository source has PostgreSQL V1–V18 and H2 V1–V12 (V4 is the existing Java migration `db.h2.V4__exact_catalogue_money`, rather than a SQL file). Reporting V19 exists only under scripts/onlinepos_reporting and is NOT an applied application migration. No V20 builder migration exists. Builder allocation: PostgreSQL V19, H2 V13. The uploaded reporting package remains a reference: future integration needs a fresh unused version, not a second V19.

## Capabilities

| Capability | Actual readiness |
|---|---|
| Catalogue/prices/customisation/combos | Implemented; authoritative quotation and configured draft snapshots |
| Draft create/read/cancel | Implemented with stable UUIDs, command receipts, revisions, audit/outbox |
| Draft edit/list | Added with stable line IDs, notes, expected revisions, authoritative re-quotation and durable command receipts |
| Hold/resume lifecycle | Not implemented; action remains hidden |
| Accepted sale, tax ledger, cash settlement, card provider | Missing; financial controls must remain unavailable |
| Refunds/discount ledger/PIN action approvals | Missing; configuration cannot grant these capabilities |
| Receipt/docket routing and printer integration | Placeholder module; no operational print success |
| Master TLS/pairing/terminal identity | Implemented; configuration distribution must reuse authenticated transport |
| Operational emergency transaction journal/replay | Placeholder module; no financial emergency parity |
| PostgreSQL staff/module authorization/audit | Implemented; use qualified Administration sessions for authoring |
| H2 local authentication | Persistent core exists, but development grid actor is not an authorized publisher |
| Layout authoring/rendering/publication/cache | Implemented for draft ordering; see delivery and owner guide for acceptance evidence and limits |

No reporting fact or order_draft row is an accepted sale. No mocked finalization, card capture, refund or printer acknowledgement is permitted. Installed-store upgrade requires a tested disposable V6 upgrade plus a separate rollout. The referenced IMG_7158.jpeg and IMG_7159.jpeg are absent; starter layout follows the written specification.
