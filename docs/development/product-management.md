# Product-focused Menu Management

## UX audit

CataloguePanel previously grouped products with price rows, ingredient assignments and shared burger recipe joins in a table selector. Owners needed to understand these relationships to configure a product. ItemWorkbench opened a second product selector, exposed order building inside configuration and saved customization independently of recipes. RecipeEditor offered bulk selection, but editing an assigned burger could change its shared recipe. New products needed saving before recipe configuration.

The replacement default view is a category tree, searchable source products and one editor. Sizes belong to their source product. Global sections manage reusable records. Sales ordering remains in its existing module.

## Save contract

`GET /api/local/catalogue/products/{kind}/{id}` returns a coherent source/recipe/customization snapshot and product token. `PUT /api/local/catalogue/products` accepts ProductEdits.Save. Development equivalents use `/api/local/dev/data-manager/grid/catalogue`.

All sections stage changes locally. One command UUID, expected catalogue revision and product token guard the complete save. Negative IDs identify draft records; the server allocates IDs and resolves relationships inside the existing JDBC transaction. New ingredients commit with the product. Existing reusable records require explicit global editing.

Existing receipts, locks, audit events and outbox remain authoritative. An uncertain response is retried with the same unchanged command. Failure rolls back all sections. Dependency checking is conservative: any catalogue revision change rejects stale drafts rather than silently merging them.

## Burger inheritance

Shared components and individual additions remain authoritative. Sparse burger_item_component_override rows contain exclusions and nullable removability/order overrides. Null fields inherit. Removing a shared ingredient creates a local exclusion; additions create individual links. Restore Inherited clears the override and redundant individual addition. Shared edits propagate unless overridden. Editing, protein validation and pricing use the same resolver.

Edit Shared Recipe is separate, requires a clean product draft and lists affected products for confirmation. Historical draft and order snapshots remain unchanged.

H2 V12 adds the override table with foreign keys. PostgreSQL V18 adds working-record shape protection and defers relationship reference guards to transaction commit, so bulk insertion order cannot reject a valid new product. It uses the existing secured catalogue write port. Existing migrations and immutable imports are unchanged.

## Verification

Tests cover atomic creation with new ingredients, retries, conflicts, rollback on invalid settings/injected audit failures, persistence after restart and burger overrides across shared edits. PostgreSQL tests exercise restricted runtime writes and HTTP authentication. Native Windows acceptance remains a release requirement; no production deployment or cloud changes are included.
