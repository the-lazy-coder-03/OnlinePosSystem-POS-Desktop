# OnlinePosSystem: Staged Codex Implementation Plan

**Date:** 24 October 2026  
**Purpose:** Guide Codex through building a new Windows JavaFX point-of-sale system and its local services in controlled, independently tested stages, while preserving the existing OnlinePosSystem cloud website.

## 1. Decisions already made

| Topic | Agreed requirement |
| --- | --- |
| Current cloud system | The existing OnlinePosSystem website, Spring Boot backend and hosted PostgreSQL are already complete apart from targeted changes. |
| Branches | Kenridge and Uitzicht, separately authoritative for their own prices and order decisions. |
| New desktop project | Separate GitHub repository containing JavaFX, local Spring Boot and Windows installer/build tooling. |
| Windows hardware | At each store, one Windows POS terminal is initially the primary Master. Other terminals are Slaves. |
| First-run role selection | Master or Slave during setup; role changes require protected administrative action. |
| Master installation | Obtain and configure required Windows dependencies, PostgreSQL, local Spring Boot service and JavaFX. Internet is required during initial setup. |
| Slave installation | Install JavaFX and an embedded, file-based H2 cache and temporary order queue. Do not install PostgreSQL or the local Spring Boot server in ordinary Slave mode. |
| Backup Master | A designated second Windows terminal can be provisioned as a PostgreSQL standby and automatically promoted only by a verified safe failover process. |
| Discovery | Automatically discover the current Master on the LAN; provide manual IP/hostname fallback. |
| Initial menu | Import the existing cloud menu, extras, branch configuration and prices. Once bootstrapped, the local branch owns authoritative prices. |
| POS scope | Full POS: orders, cash and external card terminal payments, per-terminal printing, local customer features, menu and price management. |
| Authentication | Separate local staff accounts, with secure offline access to permitted emergency actions. |
| Price edits | Each branch manages its own prices. Valid website price-edit requests are automatically applied locally when versions and permissions permit. |
| Online orders | Staff manually accept/reject cloud-originated orders from JavaFX. Any authenticated cashier is allowed. |
| Cloud outage | Local Master remains operational. Cloud checkout for an unreachable branch is disabled after approximately 30 seconds, then automatically restored only after healthy reconnection. |
| Master outage | Prefer safe automatic Backup Master promotion. If unavailable, individual Slaves enter H2 emergency mode. |
| H2 emergency orders | Permit in-store orders and telephone delivery orders with cached menu/prices and temporary customer contact/address details. Hold them durably until Master acknowledges successful import, then purge according to retention policy. |
| Backups | Automatic daily PostgreSQL backups, destination configurable to external storage or network path, with tested restore. |
| Updates | Alert a manager and install approved software updates; never silently interrupt a running shift. |
| Sync identity | Every order has an immutable UUID `sync_id`. Database-local numeric primary keys remain independent, and human-facing receipt/order references are separate. |
| Project sequencing | Local Windows backend and JavaFX first. Add cloud sync after standalone local operation works. |

## 2. Target architecture

```text
                              EXISTING CLOUD
                       OnlinePosSystem website
                      Cloud Spring Boot + PostgreSQL
                                    |
                         Outbound store-initiated
                     HTTPS sync + WebSocket wake-ups
                                    |
            +-----------------------+-----------------------+
            |                                               |
     KENRIDGE STORE                                   UITZICHT STORE
            |                                               |
   Active Windows Master                             Active Windows Master
   JavaFX + Local Spring Boot                        JavaFX + Local Spring Boot
   PostgreSQL + Sync Worker                          PostgreSQL + Sync Worker
            |                                               |
   PostgreSQL standby on                             PostgreSQL standby on
   designated Backup Master                          designated Backup Master
            |                                               |
   Slave JavaFX terminals                            Slave JavaFX terminals
   Embedded H2 cache/queue                           Embedded H2 cache/queue
            |                                               |
   Each POS prints locally                           Each POS prints locally
```

**Normal mode:** All terminals call the active local Spring Boot API. The active Master writes to branch PostgreSQL. H2 is a local cache and emergency queue, not a second authority.

**Cloud internet failure:** Local POS and branch PostgreSQL keep working. Cloud order delivery and publishing resume later. Online checkout is disabled for the affected branch after the configured liveness timeout.

**Primary Master failure:** The cluster attempts failover to an up-to-date, eligible Backup Master. Clients rediscover the newly active service. A safe failover requires credible quorum/witness and fencing: two concurrent writable Masters are forbidden. PostgreSQL itself does not provide a complete automatic failover manager.

**No eligible Master:** Slaves use cached branch-specific product snapshots and file-backed embedded H2 to accept permitted emergency orders. H2 orders survive application and Windows restarts. On recovery, each order is imported using its immutable UUID, with durable acknowledgements before local deletion.

**Critical constraint:** A Slave's H2 copy is never automatically promoted into a fully authoritative PostgreSQL Master. It cannot know other terminals' unacknowledged orders or guarantee authoritative pricing and inventory decisions. Emergency orders require subsequent validation/reconciliation, not silent overwriting.

## 3. Rules Codex must always obey

1. Work on **one stage at a time**. Before editing, inspect the relevant code and describe the exact files and contracts that will change. Stop at the end of every stage for user approval.
2. Treat `https://github.com/the-lazy-coder-03/OnlinePosSystem.git` as the existing production cloud application. Keep the existing site functional. Do not force-push, reset history, delete tables, edit production credentials or run migrations against production without specific approval.
3. Keep the new Windows POS implementation in a **different repository**. Suggested local project name: `OnlinePosSystem-POS-Desktop`. Do not assume a remote URL has already been created.
4. Within the new repository, keep JavaFX, local Spring Boot, shared contracts and Windows packaging as separate build modules. Do not hide server logic inside a JavaFX controller.
5. Use existing cloud business logic and API semantics as the compatibility reference, but do not assume its database schema can be copied unchanged into the local database. Inspect it first.
6. Design every mutation as a branch-scoped, authenticated, audited transaction. Do not expose PostgreSQL to the internet or give JavaFX clients direct database privileges on the Master.
7. Preserve **local PostgreSQL authority** over confirmed menu prices, availability, store order acceptance/rejection, and authoritative store order status.
8. Website-originated price changes are versioned **requests**. They become effective only after the local branch accepts them. Reject stale revisions instead of silently overwriting local changes.
9. No business event may be lost due to connection loss, process crash or missing acknowledgement. Use transactional outboxes, inbox deduplication, durable queues and cursor-based replay where applicable.
10. Use immutable `sync_id` UUIDs for orders. Numeric IDs remain database-local. Each order item, extra, payment record, and status transition must be linked to the correct UUID/accounting record across sync boundaries.
11. Online order confirmation requires a locally recorded human cashier action. Never promote `PENDING_STORE_CONFIRMATION` to `CONFIRMED` from the cloud alone.
12. No automatic online price increase or charge increase is allowed without a customer-approved re-quote. Online orders with stale totals need an explicit resolution flow.
13. H2 emergency mode must use **file-backed encrypted storage or Windows disk encryption** plus application-level access controls. In-memory-only persistence is prohibited for accepted orders.
14. No full customer database, raw payment card information, or remote staff password database should be distributed to Slaves. Cache only what is necessary for emergency processing and offline staff login.
15. Default H2 permissions are reduced: view cached items, create in-store orders, create telephone delivery orders, print receipts, and inspect the terminal's own pending queue. Do not permit price changes, refunds, cloud order confirmations, or edits requiring a global view while no Master is available.
16. Card payment support means recording approved transactions from an **external** card terminal. It does not authorize storage of PAN/CVV or claiming card transactions work without the card provider's own connectivity/approval.
17. Every stage must include automated tests where feasible, Windows-specific validation steps, a rollback plan, updated developer documentation and an explicit completion report.
18. Never implement unsafe two-node auto promotion. If the environment lacks adequate fencing/quorum or replication safety, do not promote. Fall back to H2 emergency mode and alert the user.

## 4. Important design decisions to validate during Stage 0

- **Failover quorum:** Recommend one primary Master, one replica-capable Backup Master and an independent witness/arbiter on a third reliable LAN device or equivalent reliable fencing. Two machines alone cannot reliably distinguish a failure from network partition. A witness is not an order database.
- **PostgreSQL replication:** Evaluate synchronous vs asynchronous replication, performance and operational availability. Document RPO/RTO and any data-loss possibility. Do not label a configuration "zero data loss" unless its guarantees and failure tests justify the claim.
- **Initial database import:** Define an idempotent, branch-scoped cloud-to-local snapshot contract, migration/version mapping and a cutover check. No newly generated UUIDs for records that have already been synchronised under a matching identity.
- **Master discovery:** Consider service discovery (e.g. mDNS/DNS-SD) and manual address fallback, paired with authenticated endpoint identity validation. Discovery alone is not proof that the endpoint is legitimate.
- **Emergency references:** Issue provisional references that are unique per branch, terminal and order UUID when the Master is absent. Do not attempt `MAX(id)+1` or globally sequential receipt numbers in disconnected Slaves.
- **Temporary data deletion:** Keep H2 orders until the active Master has durably imported the entire order and acknowledged the UUID. Reconcile any payment/fulfilment duplicates before displaying them as accepted by the branch. Delete only after verified acknowledgement plus a configurable safety retention window.
- **Windows distribution:** Build and test Windows installers on a Windows build agent. Installation requires administrative consent and code-signed or checksum-verified dependencies. Bundle the Java runtime needed by JavaFX; package the Spring Boot service rather than trying to download an arbitrary remote backend JAR.

## 5. Stage-by-stage execution plan

### Stage 0: Read-only audit and final technical specification

**Goal:** Learn the real cloud structure before writing code.

**Codex tasks:**
- Inspect existing OnlinePosSystem packages, API routes, database migration strategy, branch IDs, menu tables (pizzas/sizes/extras/combos/promotions), customer/order/payment tables, permissions, receipts and current order lifecycle.
- Inventory how the cloud currently receives online orders, manages menu pricing, updates stock/availability and sends realtime notifications.
- Identify endpoints/contracts to reuse and cloud modifications needed. Identify security and data-migration risks.
- Propose the local architecture, Windows installation mechanism, repo modules, authentication, H2 cache schema and safe high-availability approach.
- Produce a read-only Stage 0 report in the Codex response or an approved external planning location. Recommend the future document layout (`docs/architecture/00-audit.md`, `docs/architecture/contracts.md`, `docs/architecture/decisions.md`, `docs/roadmap.md`), but do not write into either repository yet. Record unresolved choices and assumptions.

**Acceptance:** Architecture and compatibility map explain current code versus proposed additions. Neither repository, database schema nor deployment is changed. Stop for approval.

### Stage 1: New repository and build foundation

**Goal:** Create an independent, buildable Windows POS codebase.

**Codex tasks:**
- Create a clean Git repository for the new system. Preserve the cloud repository intact.
- Establish Gradle/Maven multi-module layout, e.g. `pos-desktop`, `store-server`, `pos-contracts`, `windows-installer`, `integration-tests`.
- Choose compatible JDK, JavaFX, Spring Boot, PostgreSQL driver, H2 and testing versions; pin versions and document them.
- Add application configuration, build pipelines, static analysis, secret scanning, formatting and Windows CI.
- Add skeletal JavaFX first-run wizard and local Spring Boot health API; no incomplete fake order acceptance.

**Acceptance:** Windows CI builds and launches the skeleton; tests pass; repository is independent and has no production secrets.

### Stage 2: Windows installation, JavaFX CDI and trusted LAN connectivity

**Goal:** Install Master and Slave from one controlled distribution, preserving loose coupling.

**Approved Stage 2 refinement:** JavaFX remains the desktop UI with Jakarta CDI/Weld SE 6.0.4.Final;
Spring Boot remains a separate Windows service using Spring DI. Unsigned development installers and
private Windows CI artifacts are authorized; production signing and Windows 10/11 client validation
remain separate release gates. No business schema, staff accounts, sales, H2 order persistence,
cloud synchronisation or replication is authorized in this stage.

**Installation administrator refinement (24 October 2026):** The user selected a five-character
minimum. Enforce it consistently in JavaFX and the protected helper; retain PBKDF2 verification,
persistent retry limits and separate Windows elevation. This credential is not a staff account.

**Dependency injection and boundaries:**
- Start one Weld SE container in JavaFX startup; close it on normal shutdown and failed startup.
  JavaFX owns Application/Stage and all control construction stays on the JavaFX Application Thread.
- Resolve only the composition root from CDI. Use constructor injection, final dependencies,
  application-scoped thread-safe services, dependent presenters and explicit dependent disposal.
  Producer disposers release executors, discovery listeners and network clients. Keep programmatic views.
- Put InstallationService, PlatformProvisioner, InstallationRepository, SecretStore, PairingClient,
  MasterDiscovery, StoreApiClient and DiagnosticsService behind narrow substitutable ports.
  Controllers delegate; Windows/HTTPS/filesystem adapters implement the operations.
- Keep domain/contracts free of Spring/CDI. Never start Weld in the backend or mix container lifecycle
  annotations. Architecture tests reject framework leakage and circular package dependencies.
- Never select fake provisioning automatically on an unsupported operating system.

The current implementation/contract details and validation evidence are maintained in
[Stage 2 report](../docs/stages/02-windows-setup.md),
[CDI architecture](../docs/architecture/stage2-cdi.md) and
[Windows operations](../docs/development/stage2-windows.md).

**Codex tasks:**
- Keep JavaFX unelevated; elevate only a validated installation helper. Support resumable Master/Slave setup, protected installation-administrator authentication and deliberate role changes.
- Master: acquire official/checksummed PostgreSQL installer if not already installed, provision dedicated app DB roles, install/register the packaged Spring Boot service, start services on boot, configure local firewall for limited LAN API exposure, and install JavaFX.
- Slave: install JavaFX with embedded file-backed H2 dependencies, but do not install PostgreSQL or start the store server by default.
- Support self-test diagnostics, automatic authenticated LAN discovery and manual host/IP fallback; bind a Slave to the correct branch and server identity.
- Define an advanced managed `Backup Master` provisioning workflow that installs the extra server/database components only on a deliberately selected candidate.
- Provide uninstall/update/rollback steps that protect local production data.

**Acceptance:** CDI resolution, cleanup, dependent disposal and adapter substitution pass; packaged JavaFX
runs with its bundled runtime. Disposable Windows CI validates Master/Slave/unused Backup infrastructure,
service restart, pairing/TLS/revocation, repair and data preservation. Windows 10/11 client VM UAC,
reboot and real LAN discovery evidence remains an explicit release gate. Role changes require installation
administrator authentication, elevation and safe ownership checks. Backup components remain inactive and
unseeded; automatic promotion is disabled. Review this stage before granting Stage 3 permission.

### Stage 3: Local Spring Boot, local PostgreSQL and staff authentication

**Goal:** Build the local authority independently of the cloud.

**Codex tasks:**
- Add branch-scoped PostgreSQL migrations for menu, prices, orders, order lines, extras, local customers, staff, payment metadata, sync UUIDs, provisional/final references and audit records.
- Implement local API auth, password hashing, staff permissions, secure bootstrap admin and session invalidation. Separate store staff from website customer credentials.
- Use transactional command handlers and optimistic locking for price/order changes.
- Implement `/api/local/health`, `/api/local/menu`, order APIs, price edit APIs, staff login, printer metadata and service configuration.
- Use UUIDs for new orders from day one and keep numeric internal IDs where helpful.

**Acceptance:** Local API and PostgreSQL work without cloud access. Unauthorized cross-branch and unauthenticated operations are rejected. Migration/rollback tests pass on disposable DBs.

### Stage 4: Initial menu and price import

**Goal:** Bootstrap from the existing website without corrupting menu state.

**Codex tasks:**
- Define an authenticated, idempotent, branch-scoped initial snapshot export/import contract.
- Import menu categories, pizza sizes, add-ons, specials, discounts, taxes, fulfilment rules, branch data, and price revision baselines from existing cloud records.
- Validate referential integrity and compare counts/hashes on cloud and local snapshots before marking bootstrap complete.
- Set local PostgreSQL as authoritative for confirmed per-branch prices after a documented cutover point.
- Reject accidental re-bootstrap over modified live local data.

**Acceptance:** Both branches' menus and price calculations match expected cloud snapshots at cutover, including extras/promotions. Repeat import is safe. Internet can be turned off and the menu remains usable.

### Stage 5: Complete JavaFX POS foundation

**Goal:** Deliver the full requested normal-operation POS against the local Master.

**Codex tasks:**
- Build login, role-aware navigation, catalogue browsing/search, products/extras/size selection, cart, order editing, in-store orders, telephone delivery orders, customer lookup/entry, order history, manager menu/price editing, cashier sessions and logout.
- Implement cash tender/change and external card terminal payment recording with clear paid/unpaid/declined states; do not emulate bank authorisation.
- Add per-terminal configurable receipt printers, printer test and error/retry flow, reprint audit logging, and accessibility/keyboard-friendly cashier interaction.
- Implement branch-scoped pricing calculation and immutable order-item price snapshots, taxes, discounts, delivery fee handling and order totals.
- Provide resilient local API error handling and clear terminal status.

**Acceptance:** Multiple real or simulated terminal sessions can create and print normal orders using only the LAN. Cash/card metadata reconciles with totals, and JavaFX does not bypass the local API.

### Stage 6: Multiple terminals and local realtime updates

**Goal:** Keep all JavaFX screens consistent with one active Master.

**Codex tasks:**
- Add branch-local WebSocket notifications for new orders, status changes, item availability and price changes.
- Use refresh/cursor recovery after reconnect; do not rely on transient notifications as the only source of truth.
- Add concurrency protection so two cashiers cannot both successfully perform conflicting operations.
- Display Master identity, LAN connectivity, version, current terminal printer and pending queue count.

**Acceptance:** A price edit on Terminal A becomes visible on Terminal B; missed notifications recover. No duplicate accepted action occurs under concurrent requests.

### Stage 7: Slave H2 emergency mode and recovery queue

**Goal:** Continue limited store sales when no Master is reachable.

**Codex tasks:**
- Keep a small, branch-specific, last-confirmed read-only cache of menu items, sizes, extras, price revisions and only the minimal offline authentication/permission information.
- Implement encrypted or Windows-protected file-backed H2 with a durable local queue of emergency orders and minimal related customer/payment metadata.
- Enter emergency mode after robust Master health checks and after safe failover has not succeeded. Label the UI prominently and warn about potentially stale prices and inventory.
- Permit only in-store and phone delivery creation, with cash and externally approved card payments; use globally unique UUIDs and collision-resistant provisional receipts.
- Block unsafe operations: price changes, refunds, cloud-confirmation decisions, cross-terminal order edits and operations requiring authoritative inventory.
- Once an active Master is available, send each queued order to an idempotent import API. Retry after timeouts, reconcile order totals and fulfilment conflicts, and delete only after durable acknowledgement plus a safety retention policy.
- Never lose accepted orders because of JavaFX crash, Windows restart, failed import or lost acknowledgements.

**Acceptance:** Disconnect all Masters, create orders on two Slaves, restart both terminals, then restore service. Every order imports exactly once. Duplicate retries are harmless. H2 queues empty only after verified durable acknowledgement.

### Stage 8: Backup Master, Windows HA and split-brain protection

**Goal:** Achieve safe automatic local failover instead of assuming any Slave can replace the database.

**Codex tasks:**
- Provision a distinct Backup Master Windows terminal with compatible PostgreSQL standby and local Spring Boot service in standby mode.
- Implement replication monitoring, automatic election/promotion only with a verified single-writer mechanism, witness/quorum or adequate fencing, client redirection and controlled rejoining of an old Master.
- Document sync/async replication choice, RPO/RTO, lag threshold, promotion eligibility, transaction durability, witness failure cases and planned role changes.
- Preserve local-only branch authority when cloud connection is lost. Prevent two Masters accepting orders or writing prices concurrently.
- Support fall back to H2 emergency mode if safe automatic failover is impossible, rather than promoting an unverified or stale node.

**Acceptance:** Repeated tests of abrupt Master power loss, network partition, standby loss, stale replicas, reboot/rejoin, and witness loss do not produce split-brain or silent data loss. Report measured RPO/RTO with limitations.

### Stage 9: Global order IDs and durable cloud sync infrastructure

**Goal:** Make cloud/store message delivery idempotent and safe despite independently generated numeric IDs.

**Codex tasks:**
- Add immutable `sync_id` UUIDs to cloud orders through an additive migration. Preserve existing numeric IDs and foreign keys. Reconcile previously duplicated/shared orders before backfill.
- Add branch ID, `order_source` (`ONLINE` / `IN_STORE`), `fulfilment_type` (`DELIVERY` / `COLLECTION` / `DINE_IN` where relevant), revision and customer-visible order reference as needed.
- Add local/cloud transactional `sync_outbox`, `sync_inbox`, checkpoints, conflict tracking and cloud branch delivery queue; ensure branch-scoped committed event ordering and replay.
- Build secure store-initiated HTTPS sync endpoints, replay-safe authentication, acknowledgement tracking, retry/backoff and a simple WebSocket wake-up mechanism with HTTP polling fallback.
- Bind remote order/item/status references by UUID, not database-local `BIGINT`.

**Acceptance:** Same UUID can be retried arbitrarily without duplicate orders. Events survive restart, lost acknowledgement and long disconnection. Cloud numeric ID 152 can coexist safely with unrelated local numeric ID 152.

### Stage 10: Online order delivery and store confirmation

**Goal:** Deliver website orders to JavaFX rapidly while preserving local confirmation authority.

**Codex tasks:**
- Cloud saves an online order with a UUID and `PENDING_STORE_CONFIRMATION`. Durable branch-specific delivery and wake-up follows.
- Active local Master imports the complete order idempotently, including options, item price snapshots, customer/delivery details and payment intent metadata.
- All relevant JavaFX terminals show the pending order. An authenticated cashier accepts or rejects it. The decision commits locally and syncs to the website.
- Apply local price/availability validation. If increased pricing or other material changes are required, stop confirmation and obtain an explicit customer-approved update; do not charge extra automatically.
- Target normal connected visibility within 1 to 3 seconds, with configurable fallback polling around 5 seconds. These are targets, not hard guarantees.
- Add heartbeat at a sensible short interval (initially 10 seconds), disable affected branch checkout after roughly 30 seconds of missing healthy contact, and re-enable only after confirmed branch recovery. Account for stale leases, reconnect races and pending checkout sessions.
- Keep checkout behavior and order statuses transparent to website users. A store cannot confirm online orders while only individual Slave H2 emergency modes remain.

**Acceptance:** Order created in cloud appears once in correct branch, is accepted/rejected by cashier, and cloud shows exactly that decision. Checkout disables and restores correctly under failure tests, including simultaneous submissions during disconnect.

### Stage 11: Bi-directional price change requests and cloud projections

**Goal:** Allow changes to originate on website or JavaFX without two competing price authorities.

**Codex tasks:**
- Local JavaFX price edits commit with optimistic revision checks, emit durable events and update cloud confirmed projections.
- Website admin edits create branch-scoped `PriceChangeRequested` commands with the expected confirmed revision and audit identity.
- Active Master validates and automatically applies requests only when valid and revision-matched, then reports acceptance/rejection to cloud.
- During outage, website requests remain pending; website still displays the last confirmed price. Stale requests become visible conflicts and are not force-applied.
- Cover branch-specific extras, combinations, sizes, specials, discounts and other actual price-bearing records found during Stage 0.

**Acceptance:** Updates propagate both ways when connected. Two opposing edits based on one revision cannot silently overwrite each other. Kenridge edits never modify Uitzicht prices unless an explicitly authorized future feature allows it.

### Stage 12: Backups, monitoring, audit and manager-approved updates

**Goal:** Make the product maintainable on Windows in busy stores.

**Codex tasks:**
- Implement scheduled daily PostgreSQL backups to a configurable external/network destination, retention, encryption where appropriate, verification and restore drills. Ensure backups also include necessary application configuration and protected keys through approved secure channels.
- Expose status: active/standby Master, replication lag, H2 pending count, cloud heartbeat, sync backlog, last successful sync, missed imports, price conflicts and Windows service health.
- Provide operator alerts and actionable troubleshooting for failed printing, database unavailable, corrupted cache, absent Master, full disk and sync backlog.
- Build a manager-approved update workflow using signed/checksummed artifacts, safe preflight checks, backup, rollback and coordinated Master/Slave compatibility versions.
- Apply least-privilege Windows service accounts and firewall rules, credential rotation, encrypted transport and audit logging.

**Acceptance:** Fresh restore from a real backup works. Manager-approved update and rollback preserve pending orders and store operation. No unencrypted secrets appear in logs or installers.

### Stage 13: Failure testing, branch pilot and production rollout

**Goal:** Prove readiness before migrating live cashier operations.

**Codex tasks:**
- Build an integration test matrix for network failure, cloud outage, primary Master crash, standby promotion, split-brain attempts, stale replicas, no quorum, H2 offline queue, retry storms, order UUID collisions, DB crash, power failure, lost response, concurrent cashier decisions and inconsistent pricing.
- Test both branches with independent menus and prices. Test peak multiple-terminal load and printer behaviour.
- Compare the imported menu and pricing rules against the production website using staging snapshots and documented discrepancies.
- Run a Kenridge pilot, analyse logs/backlog/reconciliation outcomes, then pilot Uitzicht only after sign-off. Rehearse rollback to existing ordering workflows.
- Provide operational docs for setup, recovery, safe promotion, staff onboarding, backup restoration, customer disputes and update approval.

**Acceptance:** Test evidence and documented recovery procedures are complete. No unresolved data-loss or duplicate-order risk is hidden. Pilot approval precedes wider deployment.

## 6. End-to-end scenarios that must pass

| Scenario | Expected result |
| --- | --- |
| Cloud internet loss, Master available | JavaFX normal LAN operation continues; sync queues changes; website disables affected checkout when heartbeat expires. |
| Primary Master fails, safe standby available | Standby becomes the one active Master and terminals reconnect without a second writer. |
| Primary and standby unavailable | Slave H2 emergency mode accepts only in-store/telephone delivery orders with cached menu. |
| Slave reboots during emergency | Accepted emergency orders remain in its file-backed H2 queue. |
| Master returns and import acknowledgement is lost | Slave retries unchanged UUID; server deduplicates; no duplicate order is created. |
| Two Slaves produce numeric order 152 | UUIDs remain unique and distinct; order receipts remain collision-safe. |
| Website order arrives during valid connectivity | Cloud holds pending order; local cashier decides; only authoritative decision updates cloud. |
| Website price edit while disconnected | Request stays pending; cloud confirmed price does not change until store applies it. |
| Both store and website edit one price concurrently | Revision mismatch causes an explicit conflict, not silent overwrite. |
| Primary and standby both claim leadership | Fencing/quorum prevents two writable PostgreSQL authorities; otherwise use emergency mode. |
| Card terminal unreachable | POS cannot invent approval or mark payment completed without authorized payment evidence. |
| Master backup restore | Menu, orders, staff, accounting and unprocessed sync events are recoverable from verified backup. |

## 7. The initial Codex prompt: run Stage 0 only

Copy the prompt below into Codex with the existing cloud repository available for **read-only inspection**. The new repository will be created in Stage 1.

> You are the lead architect for my OnlinePosSystem Windows JavaFX POS expansion. Read this entire `OnlinePosSystem_Staged_Codex_Implementation_Plan.md` document before doing anything else. This is a staged project. **Execute Stage 0 only. Do not implement Stage 1 or any other stage until I approve it.**
>
> The existing cloud application is `https://github.com/the-lazy-coder-03/OnlinePosSystem.git`. It is a completed, live Spring Boot website backed by PostgreSQL. Audit the code, API routes, schemas and migrations read-only. Our separate future POS repository will contain JavaFX, local Spring Boot and Windows installation tooling. Branches Kenridge and Uitzicht are independent. Local PostgreSQL will be authoritative for pricing, availability and acceptance/rejection of online orders. Normal POS operations must continue without internet.
>
> Important additional requirements: one Windows POS is primary Master; an eligible second Windows POS is Backup Master; Slave terminals run JavaFX plus an embedded H2 cache/temporary emergency order queue. Safe automatic Master failover must be designed with anti-split-brain protections; if no safe Master is available, Slaves may create in-store orders and telephone delivery orders in a durable H2 queue and reconcile later. Never claim H2 is a PostgreSQL replica. Cash and external card terminal payments, per-terminal printing, separate local staff accounts, manager-approved updates and daily backups are required. Online checkout must automatically disable for an unreachable branch after approximately 30 seconds and restore when safe. Each order needs an immutable global `sync_id` UUID, while existing numeric IDs remain local.
>
> Produce a detailed Stage 0 report covering: (1) existing cloud code structure, (2) schema and menu/order/payment mapping, (3) reusable API contracts, (4) required cloud modifications, (5) new repository module layout, (6) Windows installation/role setup, (7) local database/bootstrap cutover, (8) embedded H2 emergency queue and reconciliation, (9) Backup Master replication/failover/fencing design with stated RPO/RTO trade-offs, (10) security and permissions, (11) test and rollback strategy, and (12) any unknowns or conflicts. Verify assertions against code and give paths. If the repository is inaccessible, report this rather than guessing. **Do not modify either repository, create migrations, push commits or deploy anything in Stage 0. Stop and wait for approval.**

## 8. Template for every subsequent Codex stage

When Stage 0 is approved, send Codex the following template, replacing `<N>` and `<stage title>`:

> Implement **Stage <N>: <stage title>** from `OnlinePosSystem_Staged_Codex_Implementation_Plan.md` and the approved Stage 0 architecture. First inspect current code and summarize your planned file changes. Then implement only this stage, preserving existing behavior. Add unit, integration and applicable Windows tests. Run every test you can run, mark any test requiring external Windows hardware clearly, and do not claim unexecuted tests passed. Document migrations, rollback and manual acceptance steps. Finish with a summary of changed files, test results, outstanding risks and the exact stage exit criteria. Do not proceed to Stage <N+1> or deploy to production without my approval.

## 9. Deployment safety checkpoints

Before any cloud schema migration or production installation:

- Use a clone or isolated staging environment to rehearse the operation and rollback.
- Have a tested PostgreSQL backup and the documented return path.
- Verify existing cloud order submission and menu flows still work unchanged unless deliberately modified and approved.
- Keep cloud endpoints backward-compatible through the transition.
- Ensure secrets and staff passwords are never copied into code, Git history, install logs or diagnostics.
- Require explicit approval before replacing a live Master, promoting a standby during routine maintenance, updating cloud checkout behavior or enabling a production sync worker.

## 10. Technical references for Stage 0 verification

- PostgreSQL 16 high availability: https://www.postgresql.org/docs/16/high-availability.html
- PostgreSQL 16 failover and the requirement to avoid two primaries: https://www.postgresql.org/docs/16/warm-standby-failover.html
- PostgreSQL 16 standby replication and durability trade-offs: https://www.postgresql.org/docs/16/warm-standby.html
- H2 database features and embedded disk persistence: https://h2database.github.io/html/features.html
- Java `jpackage` for Windows native installers: https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html

**End of plan.**
