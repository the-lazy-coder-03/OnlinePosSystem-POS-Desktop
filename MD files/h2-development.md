# Persistent H2 development grid

Only OnlinePosSystem-POS-Desktop is changed. H2 development now has a real
backend/API/JavaFX Customers, Catalogue, draft-builder and Administration-history workflows. PostgreSQL integration and automatic
fallback are deferred; installed PostgreSQL migrations and data are unchanged.

## Launch and navigation

Use JDK 25 and Python 3, then run from any working directory:

```sh
/Users/matthewanton/IdeaProjects/OnlinePosSystem-POS-Desktop/scripts/run-dev-desktop.sh
```

On Windows use `scripts\run-dev-desktop.cmd`. The launcher builds the relevant
Maven modules, starts a loopback-only backend with the explicit `dev` profile,
verifies its authenticated identity and opens the existing JavaFX POS grid.
Open **Customers**, **Catalogue Management** or **Administration** directly from
the grid. **Settings → Developer Tools → Local Data Manager** remains available
for categories. Sales checkout, Cash and Reports remain unfinished; they do not
substitute sample transactions.
The sample preview remains separate under `scripts/preview-desktop.sh`.

Use `--no-build` after a successful build, `--backend-only` for API testing, or
`--data-dir /absolute/path` for an isolated development directory. A new directory
adopts the recognized old `runtime/h2` database if present; it does not seed rows.
The backend may also use `--pos.dev.data-dir=/absolute/path`. Profiles `installed`,
`local-store` and `h2-development` cannot be combined with `dev`.

The launcher owns and stops a backend it starts when JavaFX exits. An existing
backend is reused only after its protected credential, application/profile,
database and per-start instance identity match. An occupied, unverified port
causes an error. Only the backend accesses H2; JavaFX receives an API credential,
never the H2 password. No H2 console or TCP listener is enabled.

## Storage, adoption and backup

The working directory never determines the new database location:

- macOS: `~/Library/Application Support/OnlinePOS/dev/onlinepos.mv.db`
- Windows: `%LOCALAPPDATA%\OnlinePOS\dev\onlinepos.mv.db`
- Linux: `${XDG_DATA_HOME:-~/.local/share}/OnlinePOS/dev/onlinepos.mv.db`

The directory also holds `credentials.json`, `connection.json`, `backend.lock`
and a protected `backend.log`. POSIX directories use 0700 and credential/database
files use 0600. Windows relies on the current user's application-data directory
permissions; Windows ACL/platform validation remains an explicit release check.
The API credential rotates on every backend startup and is never returned by an
API. Do not share the directory or its credentials.

Before first adoption, stop the old H2 backend. The new backend exclusively locks
the old database while copying its database and credential into an empty
destination. It verifies every expected source table/column/type before applying
migrations, retains the original unchanged, and refuses partial credentials,
unrecognized schemas or an incompatible destination. Failed/incomplete adoption
retains `adoption.pending`; do not delete or overwrite either database as a retry
shortcut. Investigate the protected backend log and preserve both copies.

Only one backend can own a development directory. H2 also locks its database file.
After a successful adoption, the new location is authoritative; never run the old
launcher expecting its retained copy to receive new edits. The old
`tools/h2-store.py` structural-only launcher is superseded for this workflow.

Stop the owning backend before backing up. Copy the complete development directory,
including the database and credential, to a protected location. Restore only into
an empty directory and launch with its absolute `--data-dir`; do not overwrite an
active database. `connection.json` is regenerated when the restored backend starts.

## Schema and category rules

The structural source is the supplied website SQL: `migration.sql`, address
changes and driver migrations v6–v9. The checked-in source mirror has 53 tables.
PostgreSQL timestamptz maps to H2 TIMESTAMP WITH TIME ZONE; jsonb maps to H2 JSON.
Website accounts, credentials, RLS policies, triggers and business seeds are not
executed/imported. Partial/standalone indexes and ALTER-added constraints from the
website SQL are not all mirrored. This mode does not claim production security
or PostgreSQL audit equivalence.

Versioned Flyway migrations now live in `db/h2-migration`, independently of
PostgreSQL migrations. Flyway history is in `dev_meta`; source tables remain in
`public`. V1 creates/adopts the recognized source structure. V2 adds category
revisions, ID allocation, retry receipts and a development change journal. Future
changes require new additive migration files; never regenerate/rewrite an applied
V1/V2 migration. `tools/generate-h2-schema.py` remains a source-inspection aid,
not a production migration updater.

Menu Categories is the only generic Local Data Manager entity. The normal
Customers and Catalogue screens also persist their supported business records. ID is backend-generated/read-only;
name is trimmed, required and limited to 200 characters; sort order is a signed
32-bit integer; Active is editable. Case-insensitive trimmed name collisions are
rejected under the same lock that serializes writes. Existing duplicate names are
preserved, but colliding writes must be corrected rather than silently merged.
Categories are shared catalogue definitions, not filtered by branch.

**Delete disables** after confirmation. It never removes a row or product/order
references. Use Disabled/All filters, Edit, and Active to reactivate. Search is a
literal case-insensitive substring, with stable ordering and server pagination.
The screen retains unsaved edits after failed requests; Refresh requires discard
confirmation and revision conflicts require reload/review before saving.

Every mutation commits the category, command receipt and development journal
atomically. Retrying an identical command returns its original result. Reusing a
command UUID with another payload is rejected. Journal failure rolls back business
changes. This is a development journal, not the immutable PostgreSQL audit service.

## API contract

All routes are under `/api/local/dev/data-manager`, require a valid development
bearer credential and a loopback client, and exist only in `dev`:

| Method | Route | Behaviour |
|---|---|---|
| GET | `/status` | Verified H2 connection, application/profile, instance ID and supported entities; no secrets |
| GET | `/categories?search=&state=active&page=0&size=50` | Records, total, page and size; state active/disabled/all; size 1–100 |
| POST | `/categories` | Create with commandId UUID, expectedRevision null, name, sortOrder, active |
| PUT | `/categories/{id}` | Edit/disable/reactivate with commandId UUID and expectedRevision |

Category responses contain id, name, sortOrder, active and revision. Errors expose
safe code/field/message values: 400 validation, 403 unauthorized, 409 collision or
revision/command conflict, and 503 database operation failure. No arbitrary SQL or
generic table editor is exposed. No production staff PIN is required or bypassed:
these credentials authorize only the explicit local development API.

## Verification and limitations

Backend tests cover adoption, original-file preservation, incompatible files and
profiles, ownership locks, restart persistence, input validation, duplicate names,
pagination, concurrent/stale updates, idempotent retries and atomic rollback.
HTTP tests exercise real servers, authorization, loopback restrictions, response
redaction and endpoint absence outside dev. JavaFX tests cover background requests,
validation, retained edits, retry command identity and revision conflicts.

Sales checkout, payments, cloud sync, recovery and Master/Slave failover remain
unimplemented. Authentication-dependent Administration operations remain disabled. No permanent Slave
database is introduced. Windows execution and ACL checks require a Windows host.

### Delivery verification (27 October 2026)

The full root Maven `verify` completed with JavaFX tests enabled, formatting,
static analysis and integration checks. The isolated
`python3 tools/dev-h2-smoke.py` also passed: JavaFX created/edited a real category,
confirmed disable, reactivated it, reopened its screen, then the backend was
stopped/restarted and the saved row was verified through HTTP. A rendered test
screen is available at `pos-desktop/target/h2-real-data-manager.png`.

The actual retained development database was adopted and its authenticated status
and empty category directory verified. No smoke-test category was inserted into
that directory. Native manual interaction was unavailable because macOS was locked;
automated JavaFX interactions and rendered layout inspection completed. Windows
runtime checks and PostgreSQL fixture-dependent checks were skipped on this host;
they do not establish production PostgreSQL/H2 equivalence.


## Connected grid modules

The H2 development grid opens implemented modules without asking for a staff PIN.
The backend still requires its protected, loopback-only development bearer
credential for every operation. Operations are attributed to **Development actor**;
no staff login, designated owner or production permission approval is fabricated.
Authentication is a later stage. Staff/PIN/role administration, owner transfer,
audit-policy changes and developer recovery cannot be enabled through these APIs.
Administration shows actual counts and read-only event history; its tracking page
explains that all optional tracking remains enabled while owner approval is absent.

Customers supports the existing profile layout, normalized contacts, archive/restore,
ban/unban, protected reason/history, conflicts, aliases, pending-sync status and
linked drafts. Website credential columns are neither exposed nor overwritten by
profile edits. New profiles default to Kenridge; POS edits cannot change preferred
store. Remote lookup and cloud delivery remain disabled. A pending ban has not
been acknowledged by another branch or website. Historical website order/payment
records are never represented as completed POS sales.

Catalogue Management reads the same source tables as Local Data Manager. Both
editors update the category row revision and shared catalogue revision. Category
changes are immediately visible after refresh in either screen. The UI displays
Kenridge prices; Uitzicht prices are retained in the same source schema. Shared
recipes, category extras, overrides, swaps, option groups and combo rules use the
same pricing projection and exact-decimal pricing engine as the PostgreSQL path.
Explicitly configured combo prices/upgrades do not charge standalone prices again.
Imported scheduled specials require valid explicit combo configuration before quoting.

In **Catalogue Management → Item setup & combos → Order builder**, obtain and
review a backend quote, optionally find/select a customer, add quoted lines to the
cart and Save draft. Anonymous walk-ins remain supported. Saving revalidates
configuration, price revisions, availability and customer status under the same
transaction lock. A banned or locally archived customer cannot be linked to a new
draft. Saved quotes/components and customer snapshots are immutable. Cancellation
changes draft state without erasing the snapshot or customer history. No checkout,
payment, receipt acceptance or cloud order submission is implemented.

### Catalogue import and H2 migrations

The reviewed catalogue-only snapshot is `catalogue-h2-seed.json`: 1,079 rows in
31 catalogue tables, compiled from the supplied `migration.sql`. No customers,
staff, credentials, historical orders or security SQL are part of the snapshot.
`tools/build-h2-catalogue-seed.py` filters allowlisted catalogue statements and
compiles them in a disposable loopback PostgreSQL cluster. It never connects to
the website/store datasource. PostgreSQL tools are needed only to rebuild that
reviewed artifact; H2 application startup has no PostgreSQL dependency.

At first module startup, the backend validates the entire snapshot in an isolated
H2 mirror. It then imports transactionally and records the source SHA-256 checksum.
An existing identical row is preserved; a conflicting source ID fails the import
and rolls it back without overwriting user data. An identical subsequent launch
skips import, preserving later edits. A changed source checksum requires an
explicit migration; it is never silently reimported. Keep backups before updating.

Applied V1/V2 migrations are unchanged. Added migrations are:

- V3: catalogue revision/rules, import receipt, customer identity/lifecycle/conflict
  metadata, draft snapshots, development event journal and durable disabled outbox.
- V4: preflight every catalogue money value, then convert money columns to
  DECIMAL(18,2). Values requiring rounding or exceeding precision stop migration;
  no lossy value is silently rounded. The original adopted file remains retained.
- V5: UUID aliases and trusted backend profile-field timestamps.
- V6: expose existing category journal rows in module history using their original
  identities/timestamps; missing historical UUID metadata remains unknown.

General history payloads exclude contacts, credentials and free-text ban reasons.
Protected customer records and outbox snapshots contain the data needed for future
integration. Commands, business changes, receipts, event journal and outbox commit
atomically. The outbox stays **PENDING_DISABLED**; no worker transmits it. This H2
journal is read-only through the API and does not claim PostgreSQL privilege-based
append-only enforcement, signed manifests or off-host tamper resistance.

### Grid API additions

The development-only prefix `/api/local/dev/data-manager/grid` provides:

| Route | Methods / behaviour |
|---|---|
| `/context` | GET current branch, development actor and supported capabilities |
| `/catalogue/state`, `/catalogue/records` | GET allowlisted state; PUT revisioned command batch |
| `/catalogue/items`, `/catalogue/items/rules` | GET pricing/configuration model; PUT staged rules |
| `/catalogue/items/quote`, `/catalogue/items/drafts` | POST validated quote / immutable draft save |
| `/drafts/{uuid}`, `/drafts/{uuid}/cancel` | GET snapshot/status; POST revisioned cancellation |
| `/customers`, `/customers/{uuid}` | GET search/details; PUT validated profile command |
| `/customers/{uuid}/archive`, `/customers/{uuid}/ban` | PUT revisioned lifecycle command |
| `/customers/{uuid}/history`, `/customers/lookup`, `/customers/sync` | GET linked history, exact local lookup or disabled-sync status |
| `/customers/conflicts`, `/customers/conflicts/{uuid}` | GET unresolved conflicts; PUT revisioned link/separate resolution |
| `/admin/overview`, `/admin/audit/types`, `/admin/audit/events`, `/admin/audit/events/{uuid}` | GET real counts/types/history/details |
| `/admin/audit/settings` | GET fixed enabled policy; PUT always refused until owner authentication exists |

Existing shared request/response contracts are reused. Mutations require command
UUIDs and expected revisions; changed-payload retries fail. History pagination uses
server sequence cursors and backend filters. Authentication/production endpoints
are not enabled by these development routes.

### Module verification

`DevGridTest` exercises shared category edits, restart persistence, contacts,
archive/ban invariants, conflict resolution and aliases, configured pricing,
required choices, combo totals, immutable drafts, stale quotes, anonymous drafts,
restricted customers and journal/outbox rollback. `DevImportTest` checks conflicting
imports and refuses lossy monetary conversion while retaining the source file.
`tools/dev-h2-smoke.py` runs actual JavaFX → HTTP → H2 category/customer/catalogue,
customer-linked draft and Administration history workflows in an isolated database,
then restarts the backend and verifies saved category/customer/draft data.
Production/PostgreSQL authentication semantics are not claimed by these tests.


### Latest grid verification

Full root Maven verification passed using JDK 25, automated JavaFX tests, actual
PostgreSQL runtime privileges in a disposable test cluster, H2 fixtures, formatting,
static analysis and architecture checks. The temporary PostgreSQL cluster was
stopped and removed; no store/website PostgreSQL database was used. The pricing
adapter lives in the neutral backend pricing package to preserve package boundaries.
The live JavaFX smoke verified Customers, Catalogue edits, customer-linked draft
save, Administration history and locked owner controls, then reopened the backend
and confirmed persistent records. Native manual interaction remained unavailable
while macOS was locked; Windows runtime/ACL checks still require a Windows host.

The actual development grid was reopened successfully with 81 menu items, 10 menu
categories and 77 Kenridge menu-price rows. Its customer directory remained empty;
all customer/draft smoke records were confined to disposable test databases. The
only initial module-history event records the real catalogue bootstrap.
