# Codex implementation task: complete owner-configurable JavaFX POS sales-screen builder

You are working in the **OnlinePosSystem-POS-Desktop** repository. Read the attached `SALES_SCREEN_BUILDER_SPEC.md` in full, and treat its owner decisions and acceptance criteria as binding. First inspect actual project files and applied Flyway migration versions. Do not assume an earlier generated V19/V20 SQL draft is applied. Do not edit already-applied migrations or run a fresh-database setup against an operating database.

## Business outcome
Build a *working*, owner-configurable, multi-page point-of-sale screen runtime and administration editor. The owner manually drags and styles product, navigation, function, order-list, keypad and payment widgets anywhere on a snap-aligned free canvas. Any reusable template creates independent copies; an explicit three-way merge applies selected later updates to selected pages. Store-wide published layout only, automatically delivered by the Master to all Slaves and adopted only after active orders are complete. Support mouse/keyboard and touch with proportional scaling and small-control warnings.

## Required interaction contract
- Product button click adds **one new distinct order line**, never auto-merges, and asks mandatory selections before commit. Size/variant are distinct manually placed product buttons. Guided combos/specials. Each tap produces a separate line.
- Centre-positioned **current-order list in the starter template**, but owner can move, resize and style it and select displayed columns. Single tap line selects/highlights and activates applicable functions. **Double-tap an existing order line only** opens full product customiser. Double-tapping the product button is not a customisation shortcut.
- Support walk-in, collection, delivery and table-service when backend supports relevant workflows; hold/resume from an order list.
- Functions available from an allowlist; every new button defaults to Cashier access. Owner/manager may *increase* restrictions but never bypass backend capability/action permissions. Restricted actions prompt for approved manager/owner PIN and log approver. Void requires a reason; cancel sale requires confirmation and record. Fixed/currency and percentage discounts apply to eligible lines only, honoring owner-set discount exemptions on individual products and proper rounding.
- Dedicated payment page is owner-designed using same editor. **Cash/card only**, and only actual implemented tender providers may process transactions. Print automatically according to approved printer rules. Unsupported functions hidden from cashier view and described with a warning in the builder.
- Owner creates multi-step visual workflows from approved action, decision and prompt nodes only; validate graph and each node's authorization server-side. No arbitrary scripts, SQL, executable expressions or remote commands.
- Separate protected Admin builder; drafts, undo/redo, autosave, preview, publication, immutable version history and rollback. Store-wide publish, no terminal/role custom layout variants.

## Technical constraints
- Read `pos.store_identity`, `pos.catalog_item`, `pos.branch_price`, `pos.catalogue_record`, `pos.item_customization`, current RBAC and audit/outbox structures as implemented. Use real stable identifiers, not hardcoded category names or branch numbers. Renderer reads versioned JSON and instantiates allowlisted JavaFX widgets; backend owns authorization, price and transaction state.
- Design a safe **next-unused additive Flyway migration** for screen configuration, immutable revisions, one store publication pointer, template versions/origin merge metadata, optional assets, permissions and mandatory auditing. Previous `V20__owner_sales_screen_builder.sql` is only a draft and conflicts with final requirements (owner-only write authority and terminal/role assignments). If V20 has been applied, do not edit it; add an amendment in V21+. If not applied, it may be revised before first deployment. The project SQL setup V1-V18 does not implement finalized transactions.
- Expose protected create/save/preview/publish/rollback/import/export endpoints; validate whole document both before save and before publish, enforce store scope and optimistic concurrency. Ensure runtime cannot directly write config tables.
- Master emits durable, sequenced publication outbox events. Slaves validate source/store/hash/schema/assets and persist a last-known-good snapshot through an idempotent inbox. Activate only at safe order boundary, automatically catch up after reconnection. Keep layout changes separate from customer/order transaction replication.
- The owner **wants** emergency Slave parity, including refunds/discounts/hold/print. **Do not claim or implement mock finalization**. Audit genuine readiness of order, payment, refund and emergency reconciliation services. Until complete, hide/guard financial actions without backend support. Provide a separately gated design for immutable emergency journal, unique financial transaction identity, durable queue, manager conflict resolution and replay without double application. Never mark unconfirmed offline card capture successful.
- Keep all existing tested Master/Slave behavior and cloud interactions operational. No checkout bypass through JavaFX, JSON, or direct SQL.

## Do the work in ordered implementation stages
1. **Repository inventory**: map modules, current Flyway history, real sale/payment state, role/PIN and sync; write readiness matrix and reconcile prior migration drafts with actual applied history.
2. **Persistence/security**: implement additive SQL, repository/API, validation, optimistic revisions, audits and atomic publishing; provide migration tests.
3. **Renderer**: approved widget and action registries, layout scaling, page navigation anywhere, product binding, line selection/double-tap, independent lines, required-choice/guided combo flows, backend capability checks.
4. **Visual editor**: palette, drag/resize, snap guides, z-order, groups, inspector, media assets, keyboard support, undo/redo/autosave, preview, versions, import/export, template cloning/selective merge/conflict UI.
5. **Action/workflow editor**: default Cashier permissions, protected PIN approvals, reason/confirmation prompts, discounts with exemptions, safe graph interpreter; real backend commands only.
6. **Dedicated payment UI integration**: owner-designed payment page; integrate only actual supported cash/card APIs, hold/resume and real print routing; clearly mark missing backend dependencies.
7. **Publishing + Slave sync**: authenticated durable ordered distribution, hash/asset checks, last-known-good, offline/resume, order-boundary activation.
8. **Emergency operations separately gated**: assess and implement only with real authoritative transaction/refund/payment/ledger semantics; durable event journal, idempotent reconciliation, crash recovery; do not falsify completion.
9. **Quality gates**: unit/integration/JavaFX UI/SQL migration/security/offline/failover tests, docs and safe deployment playbooks.

## Required test checklist
- New screen owner/manager protected access; Cashier cannot edit/publish. Every button starts at Cashier level, but a JSON edit cannot grant forbidden commands.
- Unlimited logical screens, navigation anywhere, groups, saved independent templates and selective merge preserving unrelated page edits.
- Each tap creates new order line; double tap customizer applies only to existing order line; checkout selections and combo flows atomic.
- Discount-exempt product unaffected by item or whole-order discounts; void reason mandatory; PIN approval tied to qualified actor/terminal/session and logged.
- Unsupported action hidden at runtime; visual designer shows reason; renderer handles archived product, missing assets, tiny resolutions and wrong-store IDs.
- Draft edits do not affect running store; publish/revert immutable; two concurrent authors conflict safely; Slave gets snapshot, defers activation until order ends and catches up after outage.
- No `order_draft` or V19 reporting fact treated as completed sale; no mock card payment or refund success; no duplicated money effects on emergency replay.

## Delivery protocol
Implement actual repository changes and tests, not mockups alone. At each stage report code changes, tests executed, unresolved risks and unimplemented transaction dependencies. If blocked, deliver the safely completed earlier stages and an explicit blocker list, rather than inventing completed functionality. Ask no repeated questions: all owner preferences are in the implementation plan. Preserve project conventions, JavaFX + Spring Boot + PostgreSQL integrations, and secure migration order.
