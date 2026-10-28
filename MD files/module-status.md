# POS module status and remaining work

Updated: 28 October 2026
Project: **OnlinePosSystem-POS-Desktop**

Most day-to-day POS functionality still needs to be built. Administration and Catalogue Management are furthest along. Opening a module in the preview does not mean its operational features are complete.

## Module overview

| Module | What works | Main work remaining |
| --- | --- | --- |
| **Sales & Orders** | Shared configured draft ordering in Sales and Catalogue Management; backend draft creation/cancellation | Order editing beyond the configured draft cart, checkout, payments, refunds and receipts |
| **Customers** | PostgreSQL profiles and permissions; persistent H2 development profiles, archive/restore, bans, draft linkage/history and Account Conflicts | Live website API/transport, production sync activation and Windows operational acceptance |
| **Catalogue Management** | Items, recipes, extras, swaps, options, combos and quoted drafts, now persistent through H2 development as well as the PostgreSQL path | Promotions beyond configured combos and cloud synchronisation |
| **Cash Management** | Placeholder | Drawer movements, shifts, cash-up, discrepancies and payment reconciliation |
| **Reports** | Placeholder | Sales, payment, tax, staff, product and reconciliation reports |
| **Administration** | PostgreSQL staff/roles/owner controls; real H2 development overview and read-only history, with owner/authentication actions deferred | Slip Setup (receipt appearance and print settings), remaining operational settings, printers, backups and update management |

## Planned Administration feature: Slip Setup

Add **Administration → Slip Setup** so authorised staff can configure how printed slips look and print.

Planned capabilities:

- Configure business details, header/footer text and which receipt details appear.
- Adjust the layout, text size, alignment and spacing.
- Choose receipt paper width, including 58 mm and 80 mm options.
- Select the terminal's receipt printer and number of copies.
- Show a live sample-slip preview while editing.
- Provide a clearly labelled test print using sample data.
- Save settings for subsequent printing, with Cancel and restore-defaults actions.

This feature is pending implementation. It should integrate with the receipt-printing work and preserve authoritative order totals and payment records; changing a slip's appearance must not change the transaction.

## Work across modules

These areas still require substantial implementation and validation:

- Receipt printing.
- Reliable multi-terminal updates and concurrency handling.
- Offline sales and safe recovery.
- Master failover and recovery.
- Cloud synchronisation.
- Production rollout and end-to-end operational testing.

Existing foundations such as the command journal, outbox/inbox, terminal pairing and database backup smoke tests do not by themselves complete these operational workflows. Installed customer profiles now use the Master PostgreSQL backend; fictional profiles are isolated to preview. Website synchronization remains disabled. See [customer persistence](customer-persistence.md).

## Recommended implementation order

1. **Customers local increment verified:** PostgreSQL profiles, lifecycle controls, linked draft history and conflict handling are implemented; live cloud synchronization remains a separate pending integration.
2. **Build Sales & Orders:** implement the sales screen, cart, modifiers and order lifecycle using the existing catalogue and draft foundations.
3. **Add payments and printing:** complete cash/external-card recording, checkout, refunds and receipt workflows, including Administration Slip Setup.
4. **Build Cash Management:** add shifts, drawer movements, cash-up and reconciliation.
5. **Build Reports:** use the established order/payment records for operational and financial reporting.
6. **Complete multi-terminal operation, offline recovery and cloud integration:** follow the dependencies and approval gates in the roadmap.

This order aims to deliver a usable in-store checkout before adding offline recovery and cloud features. It is a recommended sequence, not an estimate of percentage completion or development time, and it does not authorise deployment or production migrations.

## Related documentation

- [Roadmap and stage gates](../docs/roadmap.md)
- [Catalogue Management](../docs/stages/05-catalogue-management.md)
- [Audit history and owner-controlled tracking](audit-history.md)

## Customers validation

The local Customers increment passed the JDK 25 Maven build, formatting/static analysis, real PostgreSQL runtime-role tests, migration preservation, restart/backup restore and JavaFX checks on macOS (136 passed, two Windows-only tests skipped). Website synchronization is disabled. See [implementation and API details](customer-persistence.md). No existing installed-store database was migrated during development.

## Item customization increment

Catalogue Management now includes **Item setup & combos** with Recipe, Extras & Swaps, Options, Combos and Order builder tabs. Category extras inherit live; recipes define free allowances; compatible swaps charge only positive price differences; combo replacements use configured surcharges. The Master validates and snapshots every configured draft. See [item customization and API details](item-customization.md). Checkout and the Sales/PIN flow are unchanged; installed-store rollout and Windows acceptance are separate operational steps.

Item customization validation passed the JDK 25 Maven build, formatting/static analysis, actual PostgreSQL runtime-role integration checks and JavaFX tests: 145 passed, two Windows-only skips. See the item-customization document for migration/API details and rollout limits.

## Earlier H2 structural datasource (superseded)

A file-backed H2 structural mirror of the supplied website SQL is available via
`python3 tools/h2-store.py`, with 53 tables and protected local credentials.
Schema initialization, file reopen persistence, contact uniqueness and source-field
availability are tested. This is a database setup, not an H2 port of the authenticated
POS modules; PostgreSQL functions, audit enforcement and module write services
remain PostgreSQL-only. See [H2 development](h2-development.md).

## Earlier category-only H2 increment (superseded)

The explicit `dev` launcher now connects Settings → Developer Tools → Local Data
Manager to the persistent H2 backend for menu-category search, create, edit,
disable and reactivation. Writes have revisions, retry receipts and a transactional
development journal. Existing SQL data is retained during verified adoption into
the platform application-data directory. Other grid modules are explicitly
unavailable in this mode; PostgreSQL integration remains deferred. See
[H2 launch and API documentation](h2-development.md).


## H2 development grid — connected modules

Customers, Catalogue Management, configured draft saves and Administration's real
read-only history now use the persistent H2 backend in the explicit development
launch. Tiles open without a staff PIN; requests still require the local development
credential. Staff/PIN/role administration and owner-approved changes remain disabled
until authentication is implemented last. Sales checkout, Cash and Reports remain
unfinished. Both category editors share source rows and revisions.

A reviewed catalogue-only snapshot imports once (1,079 rows; no customers,
credentials or orders). New H2 migrations add module metadata, exact monetary
columns, aliases, snapshots and transactional history/outbox records. Conflicting
IDs and lossy money conversion fail rather than overwrite data. Uitzicht prices
are retained while the active UI/quotes use Kenridge. Cloud delivery and remote
lookup remain disabled; pending bans are not acknowledged by other branches.
See [H2 grid operations and APIs](h2-development.md).


## Dynamic category and combo increment

Sales & Orders reuses the catalogue customization/draft builder, with dynamic category
browsing. Category archival preserves product IDs and history and prevents new ordering.
Fixed and calculated combos support optional/multiple selections, per-entry quantities,
branch availability and immutable quote snapshots. Development remains click-to-open;
installed draft ordering uses a Sales module session. Checkout/payments/receipts remain
unfinished. See [configuration and pricing contracts](item-customization.md).


## Independent products increment — implementation status

This increment is **not complete**. The following foundations are implemented; pairing and configuration synchronization are not operational.

- Development installations start with no automatic restaurant catalogue import. Settings → Store Management creates a business/store in an empty H2 directory, or explicitly adopts an existing numeric branch without inferring identity from its name. Existing H2 catalogues are retained; no reset has been run.
- Additive H2 V8/V9 and PostgreSQL V12–V16 add stable deployment/store identities, store metadata and nested category support. Applied migration files are unchanged. Historical IDs and references remain intact.
- H2 store context now drives customer defaults, catalogue pricing, drafts and development history. Nested category availability propagates to descendants; inherited extras retain their directly assigned category scope.
- Installed PostgreSQL Administration exposes the local store profile to the designated Owner. The restricted mutation function rechecks the session, ownership and revision; command receipts, mandatory history and outbox writes share the transaction.
- Cloud fresh-install migrations contain structure rather than restaurant data or a default customer login. Legacy installations retain their original migration checksum and data. Explicit business owners can manage generated store identities through the optional management API; generic managers are not automatically owners.
- Shared HLC/change contracts and disabled integration metadata are preparation only. No cloud requests, cloud credential distribution, catalogue replacement or synchronization workers are enabled by these changes.

Remaining: complete independent production provisioning and dynamic terminal setup, H2 LOCAL authentication, Cloud Account security distribution and expiry, protected first-cloud-Owner bootstrap, full cloud management UI, pairing, verified catalogue replacement, incremental merge/transport/retries/checkpoints and acceptance tests. The production terminal setup still has legacy store-selection paths that require replacement. Checkout, payments, receipts, reporting, transaction sync and production H2 failover remain deferred.

See [independent-products.md](independent-products.md) for current contracts and operational boundaries.


### LOCAL authentication core progress

PostgreSQL V16 now preserves legacy hashes in an extensible authentication-method structure, reuses existing provisioning/session invalidation and rejects arbitrary first-match PIN selection. CLOUD account types are blocked from these LOCAL sign-in paths pending central security implementation.

H2 V10 and the backend account core support empty Owner setup, staff/PIN management, exact employee selection, durable throttles, fresh-Owner checks and terminal/module-bound sessions. Backend tests cover persistence, PIN changes, revocation, permissions, key-loss refusal and failure handling. **H2 setup/staff UI and route enforcement are still pending; development tiles remain explicitly unauthenticated development-actor operations.** Cloud authentication, pairing and synchronization remain pending and disabled.
