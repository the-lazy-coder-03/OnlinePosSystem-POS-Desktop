# Sales screens

Administration → Sales Screen Builder requires a qualified owner or manager and builder permissions. The H2 development actor cannot author or publish. Set up the development store in Store Management first. For initial local H2 owner enrolment, stop its backend, build store-server, then run `python tools/h2-owner.py ABSOLUTE_DEVELOPMENT_DATA_DIRECTORY` in an interactive terminal. This does not enrol an installed PostgreSQL account or change installed-app authentication. No default PIN is provided.

## Topping page

Tap a product button once for each item to add. Two taps create two separate lines. Required choices and combo selection must finish before insertion; further product taps are blocked while that form is open.

Double-tap an existing order line (or select it and press Enter) to open its topping page inside Sales. Pizza and burger toppings come from each product's effective configuration. Items without toppings do nothing on double-tap. For a combo, choose the component first; changes stay inside that combo.

The page opens in Add mode. Each topping tap adds one portion per item on the selected line. If an included portion was removed, Add restores it without an extra charge first. Press Remove to arm one removal: the next valid topping tap removes a paid extra first, then a removable included portion, and switches back to Add. Disabled buttons cannot remove unavailable portions or protected recipe/required-choice ingredients. Press Remove again to cancel the armed mode.

The centre list shows included, added, removed and net swapped quantities for the chosen item or component. The displayed price is quoted for the complete order line, including all combo components. Taps stay provisional until OK obtains a fresh authoritative quote. Cancel discards them, including when a price request is still in flight. Failed or stale price checks retain the page and edits; Retry price checks again. Existing draft saving is separate. Use Customise line for quantity, notes, variants, modifiers and advanced swaps; existing topping selections are retained there.

## Photo-style layout

Sales now starts with a navy screen, a category strip, five columns of coloured products, a central subtotal and order list, and actions above a descending quantity keypad. Tap a line before using the keypad; Apply quantity changes that line only. Product prices appear in tooltips and in the order. Customer selection appears below the order.

For a saved layout, open it in the protected builder and choose **Apply photo layout to all Sales pages**. Review all pages, then choose Apply layout or Cancel. This does not publish or save the conversion. Choose Save draft to retain a new immutable revision, then Publish to activate it at a safe order boundary. History / rollback restores a previous published revision. Existing product bindings, permissions, workflows and owner colours remain. Overflow products, custom controls and groups remain reachable on continuation pages; grouped geometry stays together. Template-origin baselines follow split pages so selective updates remain available.

**New photo template** starts an independent, editable template using this store's catalogue. Save draft keeps the template; applying it elsewhere creates independent copies. No database migration or installed-store rollout is performed by choosing a design. Payment, refund and printing controls remain unavailable until their backend services exist.

## Create and publish

Start with the catalogue-based starter screen or open a saved screen. Add pages, name them and select a page purpose. Drag widgets to move them; use the lower-right handle to resize. Positions snap to the grid and show guides. Arrow keys move a selected widget; Ctrl+Z/Ctrl+Y undo/redo. Set a widget's label, variant or navigation target, action/workflow, colours, font, geometry, layer and group in its inspector, then Apply properties. New buttons default to Cashier visibility.

Order-list columns, totals, keypad, customer controls and image widgets can move independently. Image uploads accept validated PNG/JPEG files up to 512 KB and 4096 pixels per dimension. Preview scales proportionally and warns about small touch targets. A payment page can be designed, but payment controls remain unavailable until genuine payment services exist.

Drafts autosave after edits; autosave never publishes. Save draft creates an immutable revision. Publish explicitly changes the single store-wide publication. Tills download published revisions and activate only when their current draft and interactions have finished. History/rollback creates a new publication pointing to a historical revision; it does not delete history.

A failed save retains the document. Uncertain transport failures retain command identity for retry. A confirmed validation or concurrency rejection keeps local edits; Refresh versions updates the list without overwriting them. Open another saved revision only after reviewing the discard prompt, or use Save a copy to preserve a separate design. Unlock again renews the protected H2 Administration session without discarding changes.

## Templates, imports and workflows

Save as template creates an independent copy. Select a template and Apply template to copy its pages, widgets and workflows into the current screen. Changes to the template never propagate automatically. On a copied page, Update template shows pending property changes and conflicts; only checked changes apply to that page. Page-specific widgets and other copied pages remain unchanged. Workflow changes have a separate review checkbox. Applying one clones and rebinds the graph on the selected page only; other copied pages keep their existing graphs. Removed template graphs remain independent until their buttons are deliberately removed or reassigned.

Export contains declarative configuration and referenced image assets. It never includes staff credentials, live orders or PINs. Import validates its schema, assets, store and catalogue bindings. Foreign or missing product bindings require explicit selection of local variants; Cancel leaves the current design alone. Preview and publish validation run again after import.

The workflow editor connects Action, Prompt, Decision and End nodes. Unsupported financial/operational actions are labelled unavailable. The server checks graph safety and permissions before runtime execution. Interactive actions must end their path; prompts can cancel safely. No workflow accepts scripts, SQL, URLs or executable classes.

## Cashier draft ordering

Every product-button tap creates a distinct line. Required choices and guided combo steps finish before any line is added; Cancel adds nothing. Select a line with one tap, double-tap it to customise that existing line, or press Enter. Quantity, notes and available variants apply only to that selected line. The keypad changes the selected line's quantity.

Save draft persists a draft, not a completed sale. Open saved draft chooses an existing draft from a list and uses its expected revision for edits. Clear draft discards local unsaved changes; it does not cancel a completed sale. Financial checkout, discounts, voids/refunds, hold/resume state, printing and emergency transactions remain unavailable until their authoritative services exist. Offline configuration can be displayed from the last verified cache; quotations and writes require the Master.

## Isolated review

After building pos-desktop and its dependencies, run `scripts\run-sales-preview.cmd` on Windows or `./mvnw -pl pos-desktop javafx:run -Ddesktop.mainClass=za.co.onlinepos.desktop.SalesPreview` elsewhere. Sales and Builder preview tabs use fictional sample data. They cannot save orders, publish or connect to an installed store. Export can retain a preview design for later explicit import.
