# Item customization, extras, options and combos

Project: **OnlinePosSystem-POS-Desktop**.

## Using the editor

Open **Catalogue Management → Item setup & combos**, then select a priced product/variant by name. Create the underlying menu item and branch price through the existing catalogue forms when introducing a new standalone product or combo SKU.

- **Recipe:** Edit included quantities, removability and item-specific topping prices. Existing burger recipes, individual components, pizza ingredients and salad ingredients supply starting allowances. Overrides preserve the original source recipe.
- **Extras & Swaps:** Associate toppings with a category. Existing and new products inherit them live. Configure shared compatible swap groups; individual items may override settings or exclude extras. Size-specific pizza prices are resolved for the selected variant. Missing paid-extra prices are rejected rather than treated as free.
- **Options:** Inherit reusable catalogue modifier groups or override groups on an item, including pizzas and other product types. Define required/optional selection limits, defaults, surcharges and optional product references. A replacement group requires exactly one selection and identifies the included ingredient allowance it replaces; each choice identifies its replacement ingredient.
- **Combos:** Assign a fixed price and named component slots with quantities, permitted product variants, an included default and explicit upgrade surcharges. Components remain normal products and can be customized. Nested combos and optional related-product suggestions are excluded. Quantity within one slot repeats the same selection; use separate slots for independently chosen products.
- **Order builder:** Calculate an explicitly local staged preview, save settings, obtain an authoritative Master quote, and add that exact quote to the draft cart. Save draft requires the existing authenticated Catalogue session plus Sales & Orders permission. It creates a draft, never a completed sale or payment. Sales grid entry/PIN behaviour is unchanged.

Settings are staged until Save. Reload discards settings edits. On a connection failure, staged rules and an uncertain draft command remain available for identical retry. A definitive revision/validation rejection requires review and re-quoting; old prices are never silently accepted. Switching away warns about staged settings or an uncertain save. Product selectors and ingredient selectors display names, not database identifiers.

The fictional preview uses its own in-memory catalogue, permits experimentation with sample settings and quotes, and disables real draft persistence. It never reads store trust material or writes production data.

## Pricing examples

All money uses exact `BigDecimal` arithmetic with two decimal places.

- A recipe includes one cheese portion. Keeping it costs R0 extra; removing it gives no discount. Adding another portion charges its configured extra price.
- Cheese priced R5 swapped for compatible bacon priced R8 adds R3. Swapping for a compatible R5 or R2 topping adds R0. A portion cannot be removed and then reused as swap credit. Credits never transfer across products or combo slots.
- A meal priced R85 includes its component products. A configured Coke → Red Bull surcharge of R12 makes it R97, regardless of their standalone price difference. A further R8 burger topping makes it R105. Two meals cost R210. Component standalone prices are not added again.
- A Chicken/Beef replacement choice replaces the full included protein quantity (including a double patty) instead of adding both. Its configured surcharge applies separately; extra portions remain paid additions.

Only catalogue-authorised staff configure prices. Order-taking staff cannot submit manual prices or arbitrary surcharges. The Master resolves current configuration and recalculates every persisted draft.

## Storage and contracts

`V10__item_customization.sql` adds a branch-scoped protected rules document under the existing catalogue revision. It does not replace source identities, immutable imports, customers or historical draft snapshots. Category assignments, swap groups, item overrides and combo definitions use shared typed contracts in `ItemCustomization`.

The runtime role can read policy but cannot directly update it. `pos.apply_item_customization` verifies a live terminal-bound Catalogue session, current credentials, `module.catalogue`, `catalogue.manage` and expected catalogue revision under the branch stream lock. The backend validates all typed references, quantities, prices, limits and combo nesting before calling the write port. Rule changes, the command receipt, optional `CATALOGUE_CHANGED` audit and durable outbox commit together; disabling optional audit does not suppress business records.

Existing recipes/modifier records and size-specific prices remain authoritative inputs. Item overrides are explicit exceptions, not copied category lists. Imported special components are exposed as combo starting points. An imported special remains unavailable until explicitly configured through a combo rule; configured specials respect their active/archive flags, date range and allowed weekdays using the branch's South African business date.

Authenticated Catalogue endpoints:

| Method/path | Contract |
| --- | --- |
| GET `/api/local/catalogue/items` | Catalogue revision, priced products/variants, ingredient definitions, categories, current rules and edit capability |
| PUT `/api/local/catalogue/items/rules` | `Edit(commandId, expectedRevision, rules)`; requires `catalogue.manage` |
| POST `/api/local/catalogue/items/quote` | `QuoteRequest(priceId, expectedPriceRevision, quantity, selection)`; returns resolved allowances/selections, itemized charges and catalogue/price revisions |
| POST `/api/local/catalogue/items/drafts` | Existing `CreateDraft` contract; additionally requires `module.sales_orders`; delegates to the same draft business operation |

`DraftLine` adds optional `customization`. Existing four-argument construction and unconfigured command hashes are preserved. Selection carries expected catalogue revision, removals/additions by topping key, swaps, choice selections and combo components with expected price revisions and their own customization. Client totals are never authoritative.

Both the existing staff-authenticated draft endpoints and the Catalogue draft endpoint run the same validation. A missing customization payload cannot bypass required choices or order a configured combo at a legacy displayed price; configured choices must first be quoted and submitted explicitly. Saved line snapshots retain the full resolved quote, ingredients, removals, swaps, option choices, component products, quantities and prices. Positive customization charges also populate the existing `order_line_extra` table; they are already included in the line/order total and must not be added twice by consumers.

HTTP 400 indicates invalid selections/configuration, 401/403 authentication/permission failure, 409 stale catalogue/price/retry identity and 503 unavailable persistence. No website/cloud connector or checkout endpoint is introduced.

## Validation and rollout

Use JDK 25 and `./mvnw verify`; enable JavaFX checks with `POS_TEST_FX=true`. PostgreSQL tests use the disposable runtime-role cluster created by `tools/postgres-test.py`; never point them at a real store. The final delivery report records executed checks and platform skips.

Deployment must back up the store and apply V10 through the established schema-owner migration path. Development verification does not migrate an installed store automatically. Retain the additive table, source records, identities, draft snapshots, receipts, queues and audit history on application rollback. Do not reverse the migration or clear history. An older application must not resume order-taking for configured products unless a compatibility build continues enforcing these rules; otherwise disable those products through authorised configuration before rollback.

Checkout, payment acceptance, receipt printing, stock movements, website synchronization and changes to Sales/PIN entry remain separate increments.

## Verified delivery — 26 October 2026

JDK 25 full Maven reactor build, formatting and Checkstyle/static analysis passed with real PostgreSQL 16 runtime-role integration tests and JavaFX enabled. **147 tests: 145 passed, zero failures/errors, two Windows-only skips.** Coverage includes allowance consumption, upgrades/downgrades, size-specific extra prices, choice defaults/limits, single and double protein replacement, explicit option snapshots, mixed-product combos, legacy draft bypass rejection, retry idempotency, stale revisions, concurrent saves, rollback and saved snapshot preservation. JavaFX verifies staging/retry, exact quote confirmation, draft creation and isolated preview restrictions; the rendered screen was visually reviewed.

Windows DPAPI and redirected administrator-input checks were skipped on macOS; real Windows operational acceptance was unavailable. Secret scanning found no leaks and the whitespace check passed. Verification used only a dedicated disposable loopback PostgreSQL cluster, which was removed afterwards. Existing installed-store databases were not migrated or seeded. Checkout, website sync and the Sales/PIN entry flow remain outside this increment.


## Dynamic categories and Sales drafts

The existing grid's Sales & Orders tile opens the shared order builder in H2 development.
Installed terminals retain the public Sales landing page; **Start draft ordering** requires
an existing Sales module PIN session. Catalogue configuration still requires a Catalogue
session and `catalogue.manage`; a Sales session cannot change configuration.

Categories use the existing IDs and product assignments. Edit name, sort order, active
and archived fields in Catalogue Management; H2 Local Data Manager edits the same menu
category records and revisions. Clear Archived to restore, then enable Active if the
category should return to ordering. Archive automatically disables the category and never
deletes products or history. Category browsing sorts by sort order, then ID.
Inactive/archived categories block new quotes and draft saves, including direct API calls.
The website import format remains unchanged; archive metadata belongs to the local editor.

In **Item setup & combos**, select a priced product/variant and choose Standard or Combo.
Before conversion, remove parent included recipe allowances and parent option groups;
the backend rejects incompatible definitions rather than silently ignoring them.
Recipes and options on component products remain available. Imported specials retain
their existing product type and combo definitions.

Combo groups specify a name, minimum/maximum selected entries, a permitted product/variant
list, per-entry quantity, an optional default and fixed-mode upgrade surcharges. Minimum
zero makes a group optional. Each variant may occur once per group; use its quantity for
multiple portions. Nested combos remain unsupported. Unavailable choices are omitted from
ordering. Availability and customization definitions apply to the current branch.

Pricing modes:

- **FIXED** (including legacy definitions): base plus configured upgrades and customization.
  A R95 meal with a R12 drink upgrade and R5 extras costs R112.
- **CALCULATED**: selected current branch prices multiplied by entry quantities, plus
  component customization, without fixed-mode upgrade fees. Two R18 drinks and a R35
  drink cost R71 per combo, or R142 for two combos.

Shared JSON contracts extend `Combo` with `pricingMode` and `enabled`, and `Slot` with
`minimum`, `maximum` and `quantities` (variant UUID to quantity). Missing fields retain
legacy fixed pricing, enabled availability and exactly one required selection. Existing
constructors remain available. Quotes snapshot `pricingMode`, resolved component names,
quantities and prices. `Selection.components` accepts multiple distinct variants for one
slot. An entry with a null price UUID explicitly selects no entries for an optional group;
this is retained in resolved selections so retry/requote cannot reinstate its default.

Existing catalogue item/rules/quote/draft API paths are reused. Saves still carry command
UUIDs and expected revisions; stale quotes require review. H2 migration V7 adds archive
flags and constraints; PostgreSQL V11 constrains archive state in existing catalogue JSON.
Applied migrations, bootstrap snapshots and historical drafts are retained. Roll back the
application while retaining additive schema/history; never clear audit or draft data.

Launch: `./scripts/run-dev-desktop.sh` (JDK 25). Sample preview remains separate and cannot
persist drafts. Checkout, payment handling, receipts, cloud delivery and recovery remain
outside this increment. Native Windows acceptance requires a Windows host.


Validation for this increment: JDK 25 module tests, PostgreSQL integration tests using
runtime privileges, JavaFX interaction checks, and the real HTTP/H2 workflow including
backend restart passed. Formatting/static checks and packaging passed. Native Windows
checks remain unavailable on this macOS host; the captured UI review is automated rather
than a manual cashier acceptance session. Restoring archived categories requires the
updated application; older application versions retain their disabled state.
