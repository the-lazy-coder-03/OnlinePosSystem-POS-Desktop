# Approved architecture decisions

1. Use this separate repository for JavaFX, local Spring Boot, contracts and Windows tooling.
   Do not change, push to or deploy the operational cloud application during local foundation work.
2. Shared product identities/recipes/definitions remain centrally managed. Each branch owns effective
   prices, availability, acceptance, order operations, staff, dispatch and settlements after cutover.
3. PostgreSQL is the normal local authority. JavaFX calls its backend, never PostgreSQL directly.
   H2 is terminal-owned encrypted cache/journal storage, never a replica or a promoted Master.
4. Hardware initially comprises two POS machines without independent witness/fencing. Use asynchronous
   standby replication later, keep the designated Master writable, and disable automatic promotion.
   Safe manual promotion requires independently fencing the former Master and reconciling any recovery gap.
   Replication outage exposure can be unbounded; there is no blanket zero-data-loss or manual RTO claim.
5. All orders/commands obtain immutable UUID identities before persistence or transmission. Numeric IDs
   and receipt numbers have separate purposes. Retrying a command must preserve its original identity.
6. Before adding any authoritative writes, implement PostgreSQL transactions, revision checks, audit,
   outbox/inbox and persistent deduplication. Stage 1 supplies types only, not delivery guarantees.
7. Website prices become versioned requests, effective after local application. Website submission for
   activated branches is pending until an authenticated local cashier commits acceptance/rejection.
8. Heartbeats target 10 seconds, checkout expiry approximately 30 seconds; enforce on the server. Only
   healthy authenticated branch recovery restores checkout. These are later-stage behaviours.
9. Preserve current prices/specials with no new separate tax, delivery fee or discretionary order discount.
   Use decimal money locally and preserve historical cloud amounts at migration boundaries.
10. Driver phone web actions and JavaFX share one local operational authority through durable commands.
    Paper is the outage fallback; manager reconciliation precedes final cash-up. No separate offline
    mobile page is included in the approved initial scope.
11. Offline staff access has no time expiry for explicitly enrolled terminal users. Disconnected terminals
    cannot learn revocations. Apply revocations on reconnect and retain minimal local verifier records only.
12. H2 emergency mode permits only terminal-local in-store/telephone sales, cash or independently approved
    external card payments, printing and queue inspection. No refund, price edit, cloud confirmation,
    shared assignment or settlement. No queue is created or sale accepted in Stage 1.
13. H2 import keeps original UUIDs, amounts and hashes. Same identity/different payload is a conflict.
    Purge only after committed import in the active recovery generation, a default 24-hour safety period
    and verified off-node recoverability. Never expire an unresolved order automatically.
14. Pair terminals to branch certificate identity; discovery is a hint. Bundle runtimes and verify installer
    payloads. Stage 1's HTTP process is loopback-only and has no credentials or writable endpoints.
15. Backups, update signing, retained events and branch ownership handover are recovery mechanisms;
    toggling a feature flag or restoring an old database is not sufficient rollback after accepting sales.

16. JavaFX uses Jakarta CDI/Weld SE 6.0.4.Final with constructor injection, explicit scopes and lifecycle
    disposal. Spring DI remains confined to the backend. Domain/contracts are container independent;
    installation ports isolate Windows, filesystem and HTTPS operations from presenters.
17. Stage 2 installers are unsigned development packages in a private repository. Production signing,
    Windows 10/11 client UAC/reboot/LAN checks and later business stages require their separate gates.
    An unseeded Backup cannot promote or run as an installed Master.

These decisions refine the original staged plan. A stage's completion does not authorize the next stage,
deployment, production migrations, branch activation or automatic failover.
