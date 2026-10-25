# Stage 4: initial catalogue and price import

Authorized on 25 October 2026 together with completion of Stage 3. This authorization supersedes the
older stop-after-Stage-3 wording in the plan. Work stays in the desktop repository; no cloud source,
production database, branch ownership or running Windows installation is changed.

## Implementation

`CatalogueSnapshot` protocol 1 preserves all 31 audited catalogue tables as branch-scoped source rows.
`catalogue-schema-v1.json` is a reviewed allowlist derived from cloud revision
`ad708b93e1026605f15e39dc02ebce790948ba0a`, `SQL files/migration.sql`. It includes branch data,
menu/pizza categories, menu items, sizes, allowed sizes, default ingredients, ingredient price categories,
modifiers, reusable burger recipes/components, salad additions, base choices, specials/days/components,
selection constraints, customization rules and special add-ons. No customers, staff, credentials,
orders, drivers or payment records enter the export.

The source has no independent tax, discount or fulfilment-rule tables. Protocol 1 explicitly records
`CLOUD_V1_NO_SEPARATE_TAX_DELIVERY_DISCOUNT`: cloud OrderTotalCalculator uses zero separate delivery fee
and discount, and no additional tax line. Existing recipes, special prices, scheduling and selection rules
are retained without inventing new charges. This is an import contract, not a Stage 5 selection/quote engine.

The export CLI requires an explicitly attested reviewed revision, a loopback database named
`pos_catalogue_snapshot`, and database authentication. It uses a read-only repeatable-read transaction,
selects only the allowlist, filters branch prices and specials (including nested special children), and
rejects column drift. It never exports from the operational cloud. Restore preparation and verification
remain operator responsibilities. Global modifier/salad prices become independent local branch baselines.

Table counts and canonical SHA-256 hashes cover sorted source keys, sorted JSON object keys and exact
normalized decimals. A whole-envelope SHA-256 also binds snapshot UUID, branch, source revision, capture
time, policy and manifest. No floating-point conversion is used in baseline validation. Fractional cents
are rejected for review, never silently rounded. Hashes detect corruption and support operator review;
a trusted terminal plus a manager session supplies API authorization, not the hash alone.

`POST /api/local/branches/{branch}/catalogue/bootstrap` accepts `CatalogueSnapshot.Import` containing
`commandId`, `expectedSha256` and `snapshot`. It requires an enrolled terminal and a manager staff session
in installed mode, checks the installed branch, validates every source foreign key and source identity,
and checks counts/hashes before writing. The HTTP body limit is 16 MiB; chunked/unknown-length writes
remain refused. Other commands retain their 64 KiB bound.

Flyway V2 adds one immutable bootstrap record and a narrowly scoped SECURITY DEFINER function. The runtime
still cannot insert catalogue definitions directly, update bootstrap records, migrate, or change the
installation identity. The function uses a fixed search path and PUBLIC has no EXECUTE grant. It locks
the branch stream and refuses any existing catalogue, baseline prices, drafts or previous bootstrap.
It inserts complete source definitions plus nine families of decimal price baselines at local revision 0.
Item/price UUIDs are deterministic from branch, record family and original composite identity. Existing
cloud identifiers are retained, never replaced with database-local sequence numbers.

The service compares persisted source JSON and every effective price baseline before committing the
bootstrap receipt, audit and outbox event in the same transaction. Concurrent imports serialize on the
branch stream. Same command/content returns its original receipt; the identical snapshot with another
command is harmless; another snapshot cannot overwrite a completed import. Replaying the original
snapshot after local price edits does not reset those prices. Special prices are retained but marked
unavailable for generic drafts until Stage 5 resolves schedules and selections.

`GET /api/local/branches/{branch}/catalogue` returns the immutable source snapshot, cutover timestamp,
source hash and current local effective prices/revisions. The source snapshot is baseline evidence;
its prices are not a replacement for the effective price list after a manager edit. Existing authenticated
`/api/local/menu` reads continue to work offline. No network client, cloud sender or automatic cutover runs.

## Evidence and exit status

Stage 3 baseline: full Maven install passed before Stage 4 edits, including 11 actual PostgreSQL scenarios,
architecture, format and static analysis. Windows-only tests were skipped on macOS.

Stage 4 representative fixtures include all 31 tables with extras, recipes, modifiers and scheduled
specials/add-ons. Tests cover exact readback, replay, stale/new snapshot rejection, manager permission,
branch isolation, corrupted manifests/references/money, runtime grant denial and injected outbox failure
rolling back catalogue, prices, receipt and stream together.

Separately, the unmodified audited cloud migration SQL was executed only in this session's new disposable
`pos_catalogue_snapshot` database. The packaged exporter produced Kenridge (813 source rows, 241 price
baselines) and Uitzicht (809 rows, 237 baselines) fixtures. Both are checked in as source-seed fixtures,
with no production/customer/staff data. Integration tests import each into an independently branch-bound
local database fixture and compare every source record and all 478 effective amounts exactly. Both seed branches include nine specials and one special add-on; representative fixtures also cover
scheduling and selection relationships.

**Acceptance boundary:** the subsequent local-database validation below verifies actual local catalogue
imports for both branches. Current production contents/deployed revision, full selection/quote behaviour
and Windows acceptance remain unverified. No sales readiness or production authority handover is claimed.
Stage 5 is not started.

## Windows validation and rollback

See `docs/development/stage4-import.md` for authenticated import and manual checks. Existing Windows CI
runs the PostgreSQL integration class including Stage 4 scenarios; that does not replace Windows 10/11
client UAC/LAN acceptance. Windows import tests have not been executed locally on macOS.

V2 is additive; V1 is unchanged. Back up and restore-verify the local database before an upgrade. Apply
V2 with the protected owner migration helper while the service is stopped; the service only validates.
A failed import rolls back entirely, so retry the exact command and snapshot after resolving the error.
After success preserve V2, receipts, baseline evidence and any changed local prices. Roll back binaries
only to a build that recognizes V2; older Stage 3 binaries validate migration history and can refuse an
unknown V2. Do not drop tables, edit applied migration checksums or restore old data over new commands.
Re-bootstrap requires a separately reviewed fresh installation, never deleting live catalogue/order data.

## Final executed verification

`./mvnw -B -ntp spotless:apply clean install` passed all ten reactor projects against the disposable
PostgreSQL 16 cluster on macOS/JDK 25. All 15 PostgreSQL scenarios passed, along with architecture,
HTTP, format and Checkstyle checks. The packaged exporter was rerun successfully for both branches with
source-column drift detection enabled. `git diff --check` passed. Windows-only tests were explicitly skipped.

## User-provided local database verification (25 October 2026)

The user supplied the existing cloud application's local environment-file location for continued Stage 4
validation. Its connection points to PostgreSQL 16.15 on loopback, database `OnlinePos`. Credentials were
read into subprocess environments only and never printed, embedded in arguments or checked into Git.
No application using that environment file was launched. All source access used read-only connections.

All 31 source catalogue tables match the import allowlist exactly. Catalogue, pizza, specials and total
calculation sources were compared with audited revision ad708b93; the four differing source files only
remove unused imports. This verifies the reviewed catalogue contract locally, not deployed provenance.

A consistent catalogue-only pg_dump was restored into this session's separate new disposable PostgreSQL
cluster. Only allowlisted tables and their required sequence state were copied. Customers, staff, orders,
payments, account credentials and driver data were excluded. No source migration, update, insert, delete,
role change or schema change was performed. Restore preparation used only disposable destination objects.

The packaged exporter then read this isolated `pos_catalogue_snapshot` copy using a read-only repeatable-read
transaction. It validated branches, fields, counts, hashes, relations and exact cent precision successfully.

| Branch | Catalogue records | Price baselines | Specials | Special add-ons | Envelope SHA-256 |
| --- | ---: | ---: | ---: | ---: | --- |
| Kenridge | 815 | 241 | 9 | 1 | `a6e66b044ebbbb839ec2dad0ed85c91586c7d55c226c53bc8d489d0b89a4855f` |
| Uitzicht | 811 | 237 | 9 | 1 | `931155732d8ce3591dde4894c2068d1024073f41ad8a89b227a3e45ffbd41740` |

The local source has 41 menu-item/modifier-group links versus 39 in each seed fixture, demonstrating that
tests used this local snapshot rather than repeating the checked-in seeds. All these links were preserved.
The test runner now accepts `POS_TEST_CATALOGUE_SNAPSHOT_DIR` for external snapshots; without it CI retains
the deterministic checked-in seed fixtures. Local snapshots were kept outside the repository and removed
after validation; only aggregate evidence/hashes are retained here.

`./mvnw -B -ntp spotless:apply clean install` with the external local snapshots passed all ten reactor
projects: 43 tests passed, two Windows-only tests skipped, zero failures/errors. Both installations were
branch-bound independently; every source record and all 478 persisted baseline amounts matched exactly.
The same run covered import corruption/permission/replay/rollback and all Stage 3 database tests. Windows
client validation and full quote-engine acceptance are not implied by these import tests.

## Subsequent Windows automated verification

The complete Windows Server 2025 CI run at tested commit c812696 passed all 45 tests with no skips and
passed the installed-package catalogue import/replay/restart tests. See `04-windows-validation.md` for the
run, artifacts, exact tested scope and remaining Windows 10/11 manual client gates. Earlier references to
Windows skips in this report describe the macOS run and do not describe the subsequent Windows CI result.
