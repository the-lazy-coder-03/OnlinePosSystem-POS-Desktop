# Roadmap and stage gates

Stage 3 is explicitly authorized. JavaFX uses Jakarta CDI/Weld SE; the separate backend retains Spring DI.
Stop after Stage 3 review. Stage 4 and subsequent stages require explicit approval one at a time.

| Stage | Deliverable |
| --- | --- |
| 0 | Source audit of cloud master ad708b93; architecture and compatibility map |
| 1 | Separate Maven repository, JavaFX preview, backend health, identity/money contracts, CI/tests |
| 2 | Unsigned development Windows installer, CDI, protected setup/pairing, LAN TLS/discovery; production signing and Windows 10/11 validation gated |
| 3 | Local PostgreSQL, staff auth, revisions, audit, migrations, transactional outbox/inbox, backup smoke |
| 4 | Consistent full catalogue/price import from an approved isolated cloud snapshot, parity validation |
| 5 | Full JavaFX sales, customers, external-card/cash ledger, printing, branch price/availability |
| 5A | Local driver accounts, shifts, dispatch, rates, payment-aware cash-up and paper reconciliation |
| 6 | Multiple terminals, concurrency, realtime hints and cursor refresh |
| 7 | Encrypted file H2 emergency sales, indefinite enrolled staff access and safe import/purge |
| 8A | Async standby, monitored lag, manual fenced recovery/rejoin; automatic promotion disabled |
| 8B | Conditional automatic HA after independent quorum/fencing hardware and separately approved tests |
| 9 | Approved additive cloud UUID/RLS/sync changes, snapshots/exchange/replay, initially shadow mode |
| 9A | Cloud dispatch/staff/payment/settlement projections and phone-web command adapters |
| 10 | Checkout liveness, online delivery, local cashier confirmation and customer-approved re-quotes |
| 11 | Versioned website price requests, branch extra prices, confirmed catalogue projections |
| 12 | Complete backup/restore, monitoring, signed coordinated manager-approved updates and rollback |
| 13 | Failure/load/printing matrix, Kenridge pilot sign-off, then separate Uitzicht pilot approval |

Every stage reports exact changes, executed and unexecuted tests, Windows evidence, remaining risks
and rollback steps. No stage grants permission to deploy, activate sync, migrate production or hand over
branch authority. Automatic HA cannot be enabled with the approved initial two-machine arrangement.

Before any live cutover, verify deployed cloud revision, actual schema, RLS ownership and migration state,
reconcile existing shifts/orders/payments, restore a real backup, and rehearse branch ownership rollback.
