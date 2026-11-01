# OnlinePosSystem configurable sales screen: implementation specification

Status: architecture and implementation plan, not deployed software. All decisions below come from the owner's Stage 1-5 answers and final architecture decisions. Scope: JavaFX desktop app, local Spring Boot backend, local PostgreSQL `pos` schema, Master/Slave sync.

## Visual reference screenshots

These are the original screenshots supplied for the desired cashier-facing sales screen. They are **visual references**, not a requirement to duplicate the interface pixel for pixel. The owner must be able to create this structure using the configurable editor.

### Reference image 1

![POS sales screen reference 1](images/IMG_7158.jpeg)

### Reference image 2

![POS sales screen reference 2](images/IMG_7159.jpeg)

### Interpretation of the reference images

- Buttons across the top are **navigation buttons**, not a fixed category bar. Navigation buttons can also appear in the product selection area or anywhere else on the canvas.
- The **middle section lists the current order**. Owners can move/resize and style this panel, configure columns and information displayed, while the transaction logic remains system-controlled.
- Product-selection buttons and other navigation controls may appear beside the order list.
- The right-hand area can contain functional buttons such as Void, Discount, Hold, Payment, Print and other approved operations.
- A standard movable/resizable numeric keypad may be placed in the layout.
- On an order line, single tap selects and double tap opens the customiser. Each product-button tap adds a **separate new order line**.
- The screenshots illustrate a starting template only. All widget positions, sizes, colours, grouping, page links and appearance are owner-configurable.

---

## 1. Non-negotiable user decisions

- A **fully free** drag/drop visual canvas with invisible snap grid, guides, arbitrary placement/resizing, mouse + touchscreen parity, styling (font, colour, border, image, icon). Proportional scaling of the layout to fit smaller resolutions; warn when controls become too small. Do not silently destroy layout structure.
- Unlimited sales **pages**, each with individually placed product/variant, navigation, category, specials and functional buttons. Navigation buttons can go **anywhere**, including the product region. The top buttons in the screenshots represent navigation, not mandatory category tabs.
- Owner manually places each product button. Different sizes/variants use separate buttons. Product button *single tap* adds one new order line; **every tap creates a separate line**, never merges automatically. If an item requires selections, show required-choice prompt before adding. Combos/specials use guided stepwise selection.
- Current-order list occupies the **central section of the example template**; its location, dimensions, displayed columns, font and colours are owner-configurable. Single tap on an order line selects/highlights it and enables contextual function buttons; **double tap on an existing order line only** opens the customiser; double-tapping a product button must not open it. The customiser can change all product-authorised modifiers, ingredients, variants, quantity and notes. Quantity changes are performed on existing order lines and by repeated product presses; the standard movable/resizable keypad is for contextual numeric input, not required for adding product quantities.
- One **shared published layout per store**, not different designs per terminal/role. Cashier, owner and manager view the same screen arrangement; permissions control action availability. Distinct pages within the shared layout may represent normal sales, payments and other approved contexts. Dedicated payment page uses the **same visual builder**.
- Functional buttons can be positioned/sized/styled and assigned approved actions. A visual workflow builder combines **allowlisted steps, conditions and prompts** (no arbitrary scripts, SQL, class names or URLs). Every newly added button defaults to **Cashier** visibility/access. Owner/manager can raise restriction, but never relax mandatory action-level authorization. Restricted actions request a qualified manager/owner PIN and record approver and reason where required.
- Item void requires a reason; cancel sale requires confirmation and a record. Fixed-amount and percentage discounts can target a line or eligible order subtotal. Owner may flag items as discount-exempt. Exempt items never receive a whole-order discount allocation. Hold/resume through an order list. If a configured function is unsupported/unavailable, **hide** it at runtime (builder must warn explicitly).
- Cash and card only for current configuration; no fabricated terminal integration. Automatically print from configurable printer/docket rules. Payment is on dedicated owner-designed page and must use true payment services when available.
- Store-local templates are **independent copies**, including arbitrary buttons, groups, panels, navigation and style. An explicit 'Apply template changes to selected pages' tool performs a diff/merge of selected template-origin elements while preserving unrelated page-specific content and requiring preview + conflict resolution. No live inheritance or cascading changes.
- Draft edits never affect active cashiers. Owner/manager can preview, autosave, undo/redo, view revision history, publish and rollback. Master distributes signed/verified published layout snapshots to all Slaves; terminals activate after finishing the active order. On reconnection automatically fetch latest layout, still defer until safe order boundary. Import/export full designs and templates between installations with safe reference mapping.
- During Master failure, Slave emergency database is intended to support **all normal operations including discounts, refunds, held orders and printing**. This is an acceptance target **only after** the authoritative sale/refund/payment, authorization, idempotency and reconciliation engines exist. No simulated refund or card payment in the meantime. Pending/unfinished orders move into a durable emergency reconciliation queue, preserving IDs, versions and provenance.
- Builder accessed through Administration with protected owner/manager authorization. Neither button styling nor configured role bypasses backend authorization.

## 2. Existing schema and implementation boundaries

The provided local fresh-database SQL ends at V18, with `pos.order_draft` explicitly marked as non-finalized. Existing local tables include `pos.store_identity`, `pos.catalog_item`, `pos.branch_price`, `pos.catalogue_record`, `pos.item_customization`, `pos.staff`, `pos.staff_role`, `pos.role_permission`, `pos.store_owner`, `pos.module_session`, `pos.audit_record`, `pos.outbox_event`, `pos.inbox_receipt`. A previously generated V19 reporting migration and V20 screen-builder draft are **proposals**, not proof of migration deployment; inspect actual Flyway history before assigning version numbers.

The earlier V20 draft has owner-only secured write ports and STORE/ROLE/TERMINAL assignments. **Do not apply as-is** if it has not already shipped: revise implementation to meet protected owner/manager editing and single store-wide layout. If V20 has shipped, preserve its checksum and introduce an additive follow-on migration, never overwrite V20. Report V19 facts/views are not an order finalization API.

## 3. Architecture: boundaries and services

```text
JavaFX Administration: ScreenBuilderEditor, Palette, Inspector, WorkflowEditor,
 TemplateLibrary, PageManager, ResolutionPreview, VersionHistory, PublishDialog
         | authenticated local API
Spring Boot Master: ScreenAuthoringService -> LayoutValidator -> LayoutRepository
          |                        -> PermissionService / ApprovalService
          |                        -> PublicationService -> ConfigurationOutbox
          v
Local PostgreSQL pos schema: draft revisions, immutable snapshots, templates,
 publication pointer, audit, outbox
          | published immutable snapshot, ordered stream
Slave: ConfigurationInbox -> validated persistent cache -> PendingActivation
          | (only at safe order boundary)
JavaFX Cashier: SalesScreenRenderer -> WidgetRegistry / ActionRegistry
          -> CatalogResolver / OrderService / PaymentService / PrintService
          -> approved WorkflowInterpreter (server-verified actions)
```

**Separation:** the builder edits only configuration documents; runtime renderer cannot modify configuration; action dispatcher never trusts role/action claims from JSON. The order list is a visual projection of the draft/active order. The backend is authoritative for price, eligibility, change permissions and actual business state. Store identity and UUID product IDs must be resolved from the local catalogue, never hardcoded menu names or branch 1/2 assumptions.

### Proposed Java packages (adapt to actual repository)

- `sales.screen.model`: ScreenDocumentV1, Page, Widget, Style, Geometry, Template, WorkflowGraph, NavigationTarget
- `sales.screen.validation`: JSON schema validation, reference checks, target viability, geometry checks, cycle detection, workflow verification
- `sales.screen.application`: author, preview, publish, rollback, import/export, template merge, version cache
- `sales.screen.runtime`: renderer, approved widget registry, action dispatcher, selected-order-line state, scaling/input adapters
- `sales.screen.sync`: store publication envelopes, durable outbox/inbox, activation gate, reconciliation events
- `sales.screen.ui.builder`: JavaFX editor controls, drag/drop, snapping, property inspector, change history, diff/merge preview
- `sales.screen.security`: module authorization, PIN approval and policy-checking boundary

## 4. Versioned document contract

Stable identifiers: UUID for screens, pages, widgets, template copies, actions/workflows; stable catalog item/price/variant IDs for product bindings. No lookup by label or position. Store a schemaVersion and renderer compatibility range.

```json
{
  "schemaVersion": 1,
  "screenId": "<uuid>",
  "storeId": "<uuid>",
  "canvas": {"width": 1920, "height": 1080, "snapSize": 8},
  "pages": [{
    "id": "<uuid>", "name": "Sales", "purpose": "SALES",
    "widgets": [
      {"id":"<uuid>", "type":"NAV_BUTTON", "rect":{"x":20,"y":20,"w":200,"h":64},
       "style":{"background":"#2563EB","fontSize":18},
       "binding":{"targetPageId":"<uuid>"}},
      {"id":"<uuid>","type":"ORDER_LIST", "rect":{"x":720,"y":100,"w":620,"h":850},
       "options":{"columns":["quantity","description","unitPrice","lineTotal"]}},
      {"id":"<uuid>","type":"PRODUCT_BUTTON", "rect":{"x":20,"y":140,"w":170,"h":90},
       "binding":{"catalogItemId":"<uuid>","priceVariantKey":"baseline"}}
    ]
  }],
  "workflows": [],
  "templateOrigins": []
}
```

Values in angle brackets are placeholders, not valid UUIDs. Use an allowlisted `NAV_BUTTON`, `PRODUCT_BUTTON`, `FUNCTION_BUTTON`, `ORDER_LIST`, `KEYPAD`, `TEXT`, `IMAGE`, `CUSTOMER`, `TOTALS`, `PAYMENT_PANEL`, `GROUP` etc. Registry versions support forward compatibility. Any imported asset must be sanitized, content-addressed, size-limited and locally cached. Reject dangerous URIs, external executables, expressions and unsupported widget types. Cap pages, widgets, workflow nodes, asset size and total document bytes; make limits configurable in application code and versioned schema.

### Templates
Templates hold a versioned subtree of widgets/pages including template-origin IDs. Instantiating makes independent widget IDs and preserves an origin mapping. Explicit update computes (old-origin vs new-template vs page-edits) three-way merge; conflicts require owner selection, never replace unrelated widgets silently. Preview selected target pages and publish new immutable revisions. No implicit propagation.

### Workflows
Allowlisted node types: `ACTION`, `PROMPT`, `DECISION`, `END`; declarative operands limited to approved order, role and capability context, never arbitrary code. Validate acyclic graph or bounded traversal, type-checked data flow, required checkout/approval gates, deterministic error paths, max steps/time, no nested execution recursion or self-navigation loops. Each action goes through backend authorization and writes idempotent commands. The visual workflow cannot create capabilities that the backend lacks.

## 5. PostgreSQL migration strategy

1. Inspect actual repo and Flyway metadata (`flyway_schema_history`) on a disposable clone. Record applied migration version + checksum. Compare to uploaded V1-V18 and previously drafted V19/V20 without assuming deployed state. Never run the monolithic fresh-setup script on an existing store.
2. Allocate the **next unused migration version**; if drafted V20 is unapplied, replace it in the implementation branch before first deployment with corrected schema. If V20 has been applied anywhere, create V21+ additive amendments. Keep previous migration files/checksums immutable.
3. Add secured `screen_definition` / `screen_revision` (immutable), `store_screen_publication` (one active publication per store), `screen_template` / `template_revision`, optional `screen_asset`, `screen_config_change` and indexed append-only publication history. Prefer store UUID plus local branch key, with FK/uniqueness enforcing exactly one logical active store layout. Do not retain unnecessary terminal/role-specific layout assignment options as official policy; migrate safely if legacy bindings exist.
4. Use narrowly scoped functions/endpoints with verified admin session and `module.sales_screen_builder` plus `sales_screen.edit` / `.publish` / `.manage_templates` permissions for manager or owner; owner protection, PIN approval and existing app authorization are still authoritative. Bootstrap initial permissions deliberately. Do not alter existing protected role semantics via broad defaults.
5. Separate draft edits from published pointer. Save **immutable revisions**, require optimistic expected revision/hash; publish atomic pointer + durable outbox event + mandatory audit record in one database transaction. Rollback changes pointer to a prior valid revision, never deletes history.
6. Add template-origin mapping, edit conflict metadata and export manifests. Database validates JSON top-level shape; Java service validates complete JSON schema, safe actions, catalogue references and rendering compatibility before persistence AND before publication. Serve only published immutable docs to cashiers.
7. Ensure `pos_runtime` is read-only on direct configuration tables and writes via authenticated stored functions/service boundary. Enforce schema + tenant/store scope; reject client-supplied owner flags or privileged actor IDs. Test RLS/grants if present.
8. Do not alter `pos.order_draft`, `pos.payment_metadata` or reporting fact tables as a shortcut for accepted sales. Future actual transaction engine changes must be separate domain migrations with explicit ledger contract, money arithmetic, idempotency, refunds and sync reconciliation.

## 6. Master -> Slave publication sync

- Master is sole authority for layout editing/publishing. A publication event includes `(eventId, storeUuid, configurationSequence, screenId, revision, schemaVersion, documentHash, assetManifest, publishedAt)`. Snapshot payload transmitted with hash, validated and bound to the store; use authenticated transport and integrity protection; avoid suggesting bare hashes authenticate the source.
- Append event transactionally with publication; replicate via outbox, never rely on best-effort push. Idempotent Slave inbox keyed by event UUID and store/sequence; handle duplicates, out of order events, gaps, retries, reboots, stale version and unsupported schema.
- Slaves retain **last-known-good** published layout and required asset cache; download/validate new snapshot fully before marking ready. Current cashiers continue using frozen layout revision until order ends, is safely held or cashier returns to idle. Atomic swap for new orders only. An active held order retains compatible workflow/price snapshots or is explicitly migrated with validation.
- Recovery: Slave requests missing sequence range or signed snapshot; reconcile source identity and never take older sequence. Defer layout activation at order boundary. Publication is local-LAN Master/Slave sync; do not assume cloud cashup sync is the only path.
- Import/export: bundle canonical JSON, version schema, local assets and a mapping manifest; on import show missing product/action/variant references and require manual rebind, preview and publication validation. Never transport staff tokens, secrets or live orders.

## 7. Emergency operational boundaries

Target feature: while Master is down, Slave may perform all **implemented** sale/refund/discount/hold/printing actions using durable emergency data, roles and product snapshots. This cannot be shipped merely by adding screens. Implement only after transaction authority, local serialisation, immutable refund/void references, payment capture/confirmation semantics and real reconciliation protocol are built. In particular, do not claim external card authorization or refunds if offline gateway/terminal support is absent; block clearly and queue for review where legally/operationally valid, never record success speculatively.

Emergency write journal: immutable eventId, store+terminal identity, local sequence, parent order ID, stable line IDs, actor/approver, snapshot/catalog version, financial amount, action reason, method, event time, device receipt, sync state. Persist before acknowledging. Emergency queue contains pending/unfinished orders and replayable authorized events, with explicit conflict, partial-apply and quarantine states. On Master recovery use idempotent replay/acceptance with exactly-once *effects* (dedupe keys), preserve audit history, never overwrite Master records or double-book revenue. Reconcile refunds against original sale identity; flag cannot-verify cases. Provide manager review for conflicts, not silent last-write-wins. End-to-end financial tests and backup/restore required before enabling full emergency feature set.

## 8. Staged delivery and acceptance gates

**Stage A | Repository audit & migration baseline**: Map JavaFX entrypoints, Spring services, schema, Flyway history and running Master/Slave boundaries. Produce capability matrix: implemented / missing / blocked. Acceptance: no preexisting migrations changed, missing financial functions explicitly identified.

**Stage B | Configuration domain & secure persistence**: new additive migration, versioned document schema, safe validation, owner/manager protected APIs, append-only revision & publish audit, atomic outbox. Acceptance: rollback works, unauthorized edit/publish rejected, invalid documents cannot publish, DB tests pass.

**Stage C | Generic JavaFX renderer**: arbitrary pages, current-order widget, owner-placed navigation/product buttons, selected line state, required-choice prompts, distinct lines per tap, line-only double-tap customizer, scaling, minimum-size warnings, registry of capabilities. Acceptance: works with retail/nonrestaurant catalogues, no hardcoded menu IDs; no draft becomes sale.

**Stage D | Visual builder**: palettes, free drag, snap guides, resize, z-order, groups, properties, undo/redo, autosaved draft, multi-resolution preview, templates, template selective merge with conflict UI, import/export, revision preview and rollback. Acceptance: owner creates complete screenshot-like screen without editing code; unrelated page content survives template updates.

**Stage E | Actions, security and workflow interpreter**: built-in action catalogue; default Cashier-level buttons, elevated authorization PIN for protected actions, void reason, cancel confirmation, exempt discount allocations; configurable function visibility; sequence/condition/prompt editor with allowlisted runtime. Acceptance: cannot bypass auth by editing JSON; actions missing backend are hidden at runtime and explained in builder.

**Stage F | Payment page and order operations integration**: visually build dedicated payment page, bind cash/card tenders to *existing authoritative services*, hold/resume, printer routing, order types (walk-in, collection, delivery, table service). Acceptance: each control either truly works and is tested, or remains disabled/unpublished/hidden with explicit readiness reason. Do not create fake settlement logic.

**Stage G | Master/Slave sync and safe activation**: ordered publication outbox/inbox, last-known-good, asset cache, delayed activation, automatic catch-up. Acceptance: order in progress retains old revision; offline/restarted Slave catches up without lost data or mixed revisions.

**Stage H | Emergency parity (separately gated)**: only after finalized transactions/refunds/card constraints are implemented, enable durable emergency journal, full approved operations, conflict handling, replay/dedupe. Acceptance: crash/reconnect/refund/idempotency/reconciliation tests; zero duplicate monetary effects; no unconfirmed card charge marked as success.

**Stage I | Production readiness**: test JavaFX touch/mouse, 1920x1080 and smaller resolutions, accessibility, import/export, backups, audit and security, migration on fresh/upgraded clones, load, offline/failover; publish operator/owner manuals and safe rollout/rollback playbooks. Treat device and workflow limits as explicit acceptance blockers.

## 9. Mandatory test cases

- Ten consecutive taps on a product create ten **distinct line IDs**; discount/void affects only selected line. Different variants are separate buttons.
- Single order-row tap highlights and enables functions; double tap edits that line only; double tap product does not invoke editor. Required options prompt before add; combos guided step-by-step and can cancel without partial cart mutation.
- Three page navigations including a button in the product region. Unbounded pages supported at product level, with memory/rendering pagination as needed.
- Copy template to five pages, edit original and apply only to two. Page-specific widgets and nonselected pages unchanged. Conflicting origin edits require review.
- Button defaults to Cashier; elevated PIN path checks role/credential version and records approval. Forged layout JSON cannot unlock privileged action. Void without reason rejected. Discount-exempt product never receives item or subtotal discount.
- Payment widget can be placed on visually designed dedicated page but cannot settle without implemented payment provider/service. No draft/report record treated as finalized revenue.
- New layout published while active cart open: store-wide distribution succeeds but visual swap waits; frozen cart remains unchanged.
- Master fails before/after publication and during an in-flight order: Slave stays on last valid config, emergency journal never loses or duplicates events; on restore reconciliation is deterministic.
- On tiny screens layout remains proportional and warns of subminimum touch targets; no unexpected clipping without visible warning.
- Try malicious workflow payload, oversized image, wrong-store product binding, missing SKU, stale expected revision, archive product, unsupported widget, cyclic workflow and invalid page target; all fail safely.

## 10. Expected deliverables

Source changes with tests, additive migration(s), JSON schema and representative configs, working JavaFX builder/runtime, migration report, actual capability matrix, Master/Slave publication path, audit events, packaging/docs and owner guide. Every completed stage should report files changed, tests run, risks and functionality blocked. No fictitious implemented payments, finalized sales or emergency refunds.
