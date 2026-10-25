# Roadmap and stage gates

Stages 3 and 4 are explicitly authorized by the user request on 25 October 2026. JavaFX uses Jakarta CDI/Weld SE; the separate backend retains Spring DI.
Stage 3 local verification is complete. Stage 4 import round-trips passed for both branches from the user-provided local database. Windows Server CI and Windows 11 client checks passed. Interactive UAC, Windows 10/separate Windows Slave acceptance, full quote processing and production cutover remain separate gates. The user has now authorized Stage 5 increment 1: the module home grid and PIN/RBAC access. The user subsequently authorized increment 2: the Administration staff/access panel. Catalogue Management now adds local items, ingredients, recipes, options and prices as the next authorized increment. Accepted sales, other operational module content and the shared sales-screen builder remain subsequent work.

| Stage | Deliverable |
| --- | --- |
| 0 | Source audit of cloud master ad708b93; architecture and compatibility map |
| 1 | Separate Maven repository, JavaFX preview, backend health, identity/money contracts, CI/tests |
| 2 | Unsigned development Windows installer, CDI, protected setup/pairing, LAN TLS/discovery; production signing and Windows 10/11 validation gated |
| 3 | Local PostgreSQL, staff auth, revisions, audit, migrations, transactional outbox/inbox, backup smoke |
| 4 | Consistent full catalogue/price import from an approved isolated cloud snapshot, parity validation |
| 5 | Module home/PIN/RBAC and Administration staff/access; then full JavaFX sales, customers, external-card/cash ledger, printing, branch price/availability |
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

Stage 5 increment 1: [module home, PIN access and operations](stages/05-module-home.md).

Stage 5 increment 2: [Administration staff/access, owner setup and recovery](stages/05-administration.md).

Catalogue Management now supports local item, ingredient, recipe, size, price and option editing with explicit RBAC. See [Stage 5 catalogue management](stages/05-catalogue-management.md).
