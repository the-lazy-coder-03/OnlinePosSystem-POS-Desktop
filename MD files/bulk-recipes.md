# Product-first bulk recipes

Open **Catalogue Management → Items**, select an existing pizza or menu item, then choose **Ingredients / Recipe**. The same editor is available in **Item setup & combos → Recipe**. Save a new product before opening its recipe. Combo recipes are edited on their component products.

1. Search the compatible ingredient catalogue and check the ingredients to include.
2. Use **Select Visible** / **Clear Visible** to change only the filtered rows. Hidden selections remain staged. **Selected first** changes ordering only.
3. Edit supported included quantities, removability and display order inline. Existing links keep their settings unless explicitly changed. Burger quantities remain governed by existing protein/recipe rules.
4. Click **Save Recipe** once. The success message appears only after the Master confirms persistence.

Unavailable ingredients already linked to the product remain visible, checked and labelled. They may be kept or removed; unavailable ingredients cannot be newly assigned. Removing a recipe link does not delete its ingredient catalogue record. Required/nonremovable customer settings do not prevent an authorized recipe editor from changing the recipe, except for existing required-protein safeguards.

Pizzas reuse their ingredient links. Burgers reuse shared recipe components and item additions, labelled by origin. Shared changes require confirmation listing the affected burgers. Adding a component to an assigned burger adds it to the shared recipe; deselecting a component removes its shared link and any corresponding individual link on the selected product. Individual links on other burgers remain intact. Legacy configurations without an explicit protein are retained; this increment does not infer or fabricate proteins.

Other menu items use generic links to the existing ingredient catalogue. No names or category IDs determine a food type. An unconfigured menu item can choose the ingredient or burger-component family. Existing salad-specific links remain visible and preserved or explicitly removed; they are not converted into a second global ingredient catalogue.

**New Ingredient** uses the existing catalogue creation operation. Choose its existing price category or create a price category in the dialog; burger components retain their existing component types. Confirmed ingredient/category creation persists independently of the staged recipe. Cancel Recipe does not undo those newly created catalogue records. After creation the new ingredient is selected in the staged recipe.

**Copy Recipe** stages replacement of a compatible recipe, previews additions/removals and copies supported properties. Historical identities and destination prices are not copied or replaced. Product-specific salad links cannot be copied to another product. Save Recipe performs the final write; shared destinations also require shared-impact confirmation.

Cancel, reload, product changes and navigation warn before discarding staged edits. Connection errors retain edits and retry command identity. Revision conflicts require reload and review. Other workbench settings must be saved/discarded before opening database recipe editing; staged recipe changes prevent editing other workbench tabs until resolved.

## Backend contracts

Installed endpoints require the existing Catalogue module session and terminal trust; mutations require `catalogue.manage`. H2 development retains its explicit development actor, loopback restriction and development bearer credential.

- `GET /api/local/catalogue/recipes/{kind}/{id}?family=ingredient|burger`, where kind is `pizza` or `menu_item`.
- `GET /api/local/catalogue/recipes/price/{priceUuid}` resolves a priced variant to its source product.
- `PUT /api/local/catalogue/recipes` accepts `commandId`, `expectedRevision`, `product: {kind,id}`, `family`, complete `selected` ingredient keys, explicit `properties`, and `confirmSharedRecipe`.
- Development equivalents are under `/api/local/dev/data-manager/grid/catalogue/recipes`.

Keys retain existing `ingredient:ID`, `burger:ID` and `salad:ID` identities. Properties support only columns already present on the relevant relationship: `default_qty`, `is_removable`, `sort_order`. New links default to one included portion, removable and appended order. Burger relationship rows do not gain invented quantities. Duplicate, foreign, incompatible, unavailable-new and unsupported-property selections are rejected.

The server computes link differences inside the existing branch/catalogue lock. Catalogue revision, receipts, audit/development history, prepared outbox and recipe mutations share the transaction. Identical command retries return their recorded result; reused commands with changed payloads fail. Existing record-edit contracts retain their earlier constructors and omit the optional recipe field when absent.

H2 V11 adds only `menu_item_ingredient`, with foreign keys to existing menu items and ingredients. PostgreSQL V17 retains its existing catalogue-record representation, adding generic relationship shape/reference protection. Applied migrations are unchanged. Rollback retains additive schema and history; no reset or live migration is required for application rollback.

## Pricing and limits

The existing customization engine loads persisted recipes. Included quantities are free; removals retain current no-discount behavior. Extras use existing branch/size prices and item overrides. Missing extra prices remain unavailable for charging rather than becoming invented prices. Recipe membership changes clear obsolete included/removable overrides while preserving prices, swaps and unrelated options. Property-only edits preserve existing size-specific overrides.

Draft saves still revalidate revisions and selections. Saved draft snapshots are immutable; subsequent recipe edits do not change historical orders. Kitchen tickets, receipts, checkout and inventory deductions are not implemented by this increment. Measured units, costs and other features from the earlier ingredient-management prompt are excluded.

The editor loads the compatible catalogue into memory and the bulk API accepts up to 2,000 selected ingredients per recipe. Historical unknown properties remain stored. Windows/native acceptance must be performed on the target installation; automated JavaFX and isolated H2/PostgreSQL checks do not replace it.
