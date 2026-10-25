# Stage 5 — Catalogue Management: items and ingredients

## Implemented behavior

Configured terminals open the existing home grid. Select Catalogue Management and enter a staff PIN. This module now opens a sidebar with **Items**, **Ingredients**, and **Categories & Options**, defaulting to Items. The header retains store/terminal identity, staff identity, connection status and Home / Lock. Existing five-minute inactivity and session-revocation checks clear the forms and return to the grid. Dialog input also counts as activity.

Every section has a record-type selector, searchable list, active-status filter, named related-record filter where applicable, 50-row client-side pages, and a details form. Reference selectors display names. Existing identity fields are immutable; new master identities are allocated by the server. Changing sections, filters, selected records or reloading asks before discarding unsaved edits. Home / Lock clears immediately.

- **Items:** menu items, pizzas, menu prices, allowed pizza sizes, prices per size, pizza default ingredients with quantity/removability, reusable burger recipes/components/assignments, individual burger components and item modifier-group assignments.
- **Ingredients:** pizza ingredients, burger components, item-specific salad ingredients, burger extra prices and pizza extra prices by ingredient price category and size.
- **Categories & Options:** menu/pizza categories, pizza sizes, ingredient price categories, base options/prices, modifier groups/options with selection limits and additional charges.

Create the category/ingredient/option first, then create the item and assign its configuration using the selector. For pizza pricing, add the allowed size before its price. Shared burger recipe edits show the affected items and require explicit confirmation. Individual components remain distinct from shared recipe components; the existing source relationships are preserved.

Master records are deactivated rather than deleted. Relationship/price rows and item-specific salad ingredients can be explicitly removed with confirmation. Removed prices become unavailable; historical prices/items and draft snapshots remain retained. Inactive records cannot be newly assigned. Existing ingredient references remain and appear in review notices with the affected item/recipe names. The Review notices button shows the complete list.

## Enable editing

Module entry and editing are separate permissions:

- `module.catalogue` allows browsing this module.
- `catalogue.manage` allows changes within a Catalogue-scoped session.

**No role automatically receives `catalogue.manage`, including MANAGER.** Existing module-entry defaults remain unchanged. In Administration → Roles & Permissions, the designated owner can explicitly add **Manage catalogue items and ingredients** to a role or create a catalogue editor role containing both permissions, then assign that role through Staff. The existing safeguard prevents non-owner RBAC administrators from delegating permissions they do not hold. Protected local-console SetRole/AssignRoles provisioning remains available for recovery and initial grants; retain existing permissions when replacing a role's permission set.

Staff with browsing access see details without Add/Save/Remove controls. Catalogue APIs independently enforce the same restrictions. Owner identity grants permanent Administration access only; it does not bypass catalogue permissions.

## Data and API

V5 adds branch-scoped `catalogue_version`, `catalogue_record` and a server identity sequence. Existing stores initialize working records from their Stage 4 import; imports performed after V5 initialize through a trigger. The bootstrap snapshot, SHA256, snapshot ID and cutover timestamp are never rewritten. Existing sellable item and price UUID derivation remains unchanged, including canonical numeric source keys. New master IDs are generated in a separate local integer range and are not recycled after removal.

The approved 31-table source model remains the compatibility contract. Editable families exclude branch identity and all specials. The working layer keeps the original pizza, burger and salad structures; it introduces no stock quantities, purchasing fields or cloud schema changes.

All endpoints require enrolled terminal authentication plus a live, same-terminal Catalogue module token:

| Method | Endpoint | Behavior |
| --- | --- | --- |
| GET | `/api/local/catalogue/state` | Working tables, editable schema, branch revision, editing eligibility and review notices |
| PUT | `/api/local/catalogue/records` | Atomic edit batch, requiring `catalogue.manage` |

`CatalogueManagement.Edit` contains `commandId`, `expectedRevision`, `changes` and `confirmSharedRecipe`. Each change contains an allowlisted `table`, nullable `originalKey` for creation, complete `values`, and `remove`. Existing keys cannot change. New master keys are assigned by the server. Composite references are supplied by the form. A batch may contain up to 200 changes, allowing dependent setup changes in one transaction. Every save checks the global branch catalogue revision; unrelated concurrent edits conservatively conflict and require reload.

The existing branch catalogue endpoint continues returning the original `snapshot`/`sha256` and now additionally exposes `effectiveTables` and `effectiveRevision`. Effective prices and definitions update in the same command transaction as working records, audit, outbox and receipt. Existing order lines retain their captured definitions and amounts. Special offers remain unavailable as operational draft lines.

Writes use a restricted SECURITY DEFINER port with a live terminal/module/permission proof, fixed search path and no PUBLIC execute permission. Runtime cannot directly modify working records. The write port independently protects immutable source families and master retention. The application validates complete schema, references, active new assignments, decimal prices, allowed sizes, required names, positive quantities and modifier limits before writing.

Command IDs provide replay safety; an unchanged save after a lost response retries the same command. Changed command content or stale revisions return conflict. Authentication/permission changes lock the client. Failed connections never display a successful-save result. Audit entries identify the actor, changed table families, count and revision; no credentials are part of catalogue requests.

## Verification and remaining work

Local PostgreSQL tests cover all editable families, creation and generated identities, exact import preservation, stable existing UUIDs, direct HTTP/module/terminal boundaries, explicit owner permission delegation, immediate revocation, expiry, invalid prices/references/limits, inactive assignments, shared-recipe confirmation, stale/concurrent changes, safe replay, outbox rollback, removed prices and immutable draft history. An upgrade test initializes working records from V4 without changing the imported source. The audited full Kenridge seed is edited as an additional compatibility check.

JavaFX tests cover navigation, editable and read-only forms, unsaved-change cancellation, named references, shared-recipe confirmation, unchanged-save retry, compact layout, configured home→Catalogue navigation and inactivity clearing. Tests capture local previews under `pos-desktop/target/catalogue-*.png`.

Windows package validation extends the installed TLS smoke probe to explicitly provision a catalogue editor role, create an ingredient, edit an item, replay the create command, reject a read-only write and cross-module request, and verify persistence after service restart. Local validation passed: 79 tests, zero failures/errors, with only two Windows-only tests skipped on macOS. All ten JavaFX interaction tests (including four catalogue tests) ran locally. Formatting, static analysis, architecture checks, isolated Windows probe compilation and source secret scanning passed. CI evidence will be recorded after the final Windows run.

Stocktaking, purchasing, stock deduction, accepted sales, shared sales-screen editing, specials editing/publishing and cloud synchronization are outside this increment. Windows hosted-runner package checks do not establish physical touchscreen usability, live-store cutover readiness or manual Windows 10 acceptance.
