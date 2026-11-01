# Configurable Sales delivery — 2026-10-30

The repository implementation is ready for owner review in an isolated preview. The installed application and database have not been upgraded. Existing uncommitted setup and catalogue work was retained.

## Stages and verification

| Stage | Delivered | Evidence |
|---|---|---|
| A — Baseline | Installed V1–V6 checksums documented; builder PostgreSQL V19 / H2 V13; reporting SQL retained as an unapplied reference | `readiness.md`; protected disposable V6→V19 and H2 V12→V13 upgrade checks retain identities, records and applied checksums |
| B — Persistence/security | Versioned documents, independent templates/origin mappings, immutable revisions/assets, one store publication pointer, optimistic revisions, durable command receipts, protected Administration APIs, audit and configuration event transaction | H2 and PostgreSQL authorization, stale/concurrent authors, duplicate requests, rollback-on-audit-failure, invalid JSON/assets, restart and direct-write denial tests |
| C — Runtime | Configurable pages/widgets, distinct lines per tap, line-only customisation, notes/quantity/authoritative variant families, guided combo selection, proportional scaling and small-control warnings | JavaFX checks for ten unique line IDs, line-only double tap, combo cancellation and cancellation while an authoritative quote is in flight; existing pricing/customisation suites |
| D — Builder | Free placement, grid snapping/guides, resize handles, keyboard movement, groups/layers/styles, page purpose, inspector controls, undo/redo/autosave, previews, history/rollback, templates, selective three-way update and safe image/design import/export | Template test updates two of five copies while preserving the other three, including independent workflow graphs; shared validation and native Windows build/preview |
| E — Supported actions | Authoritative draft create/read/list/edit, stable line IDs and notes, expected revisions, re-quotation, audit/outbox and durable receipts; declarative workflow graph editor and server approval | H2/PostgreSQL draft edit tests preserve identities and notes, reject stale prices/revisions and forged edit proofs; invalid/cyclic workflows rejected; missing operations excluded from runtime capabilities |
| F — Financial operations | Deliberately unavailable until real services exist | `financial-readiness.md` defines separate acceptance gates; no draft is represented as a completed sale, payment, refund or successful print |
| G — Configuration delivery | Authenticated Master endpoints and terminal transport, ordered durable publication log/inbox receipts, document/asset hashes, last-known-good cache, catch-up and safe-boundary activation | Duplicate/conflicting/out-of-order/wrong-store/bad-hash rejection, cache restart, deferred activation, required-asset validation; established terminal TLS/pairing checks retained |
| H — Emergency parity | Deliberately gated on genuine transaction/reconciliation services | No simulated offline financial success or replacement transaction replication |
| I — Review packaging | Isolated Windows source/build and Sales/Builder preview; owner guide, JSON schema, example, updated setup SQL exports | Native Windows acceptance run: 13 tests, zero failures; standalone H2 and PostgreSQL setup exports executed against disposable empty databases |

## Test results

- Full local Maven backend/JavaFX run: 141 tests discovered, zero failures/errors, six environment-specific skips.
- Full integration suite: 82 checks, zero failures/errors, one Windows-specific skip on macOS.
- Additional native Windows run: 13 checks, zero failures/errors/skips (`DevScreenTest`, `ScreenUpgradeTest`, `ScreenDocumentsTest`, `ScreenCacheTest`, `SalesRuntimeTest`).
- `git diff --check` and SQL-export freshness checks pass. Previously applied PostgreSQL migrations V1–V18 were not edited.
- Generated H2 SQL includes the empty-schema equivalent of Java V4; real populated upgrades still use its lossless preflight through Flyway. V13 trigger classes must be on the classpath for standalone H2 review.

The Windows preview uses software rendering: hardware rendering produced a blank window on this PC. Software rendering was visually checked. The direct UI Automation traversal smoke encountered Windows COM `E_FAIL`; it is not counted as a passing test. Native JavaFX interaction checks passed independently.

## Owner preview

PC: `matthews_pc`, observed address `192.168.1.17` (previously `.16`). Source/build is isolated at `C:\Users\matth\OnlinePOS-Sales-Preview-20261029`; scheduled task `OnlinePOS-Sales-Review` opens **OnlinePOS — Sales Preview**. Sales and Builder tabs use fictional in-memory data. Saving orders, authoring publication and connecting to an installed store are unavailable in this preview. The actual desktop shortcut and installed services/data were not replaced.

See `owner-guide.md` for normal authenticated authoring, templates, import/export and review commands. `sales-screen-v1.schema.json` describes the document format; Java validation additionally checks relationships, geometry, permissions/capabilities and graph safety.

## Remaining release gates

Owner layout review, physical touchscreen/keyboard/accessibility acceptance on the target hardware and a live second-Slave LAN disconnect/reconnect trial remain release checks. Software-renderer performance should be assessed on the target display. Do not interpret simulated cache tests as a completed two-device field rollout. No installed-store migration, deployment, payment integration, operational printing or emergency financial engine was performed.

## Photo-inspired Sales layout — 30 October 2026

Implemented the 1200 × 800 navy layout with five product columns, paginated category navigation, central subtotal/order/customer area, and descending quantity keypad. The protected builder previews conversion before staging it; Cancel preserves the original draft, Apply is undoable, and Save/Publish retain their existing permission, revision and safe-boundary rules. Existing IDs, bindings, owner styles, groups and workflows survive conversion. Overflow and specialised pages remain reachable. Template baselines follow split copies so selective updates still work. New photo template creates an independent editable design. The validated optional `photoRole` widget option identifies generated layout controls; backend and desktop must be updated together. No SQL migration changed.

Verification: 146 local tests (zero failures/errors, six environment skips), 82 PostgreSQL integration tests (zero failures/errors, one Windows-specific skip). Includes a 101-product conversion, complete navigation reachability, preserved custom styles/group membership, template updates after splitting, conversion Cancel/Apply/Undo, selected-line keypad editing, failed-save rollback/retry, stale revisions, and history rollback. JavaFX snapshots were checked at exactly 1024 × 768, 1200 × 800 and 1920 × 1080 with widget bounds assertions. Physical Windows/touch review was not repeated for this visual change.

Laptop review uses the existing H2 development catalogue. `scripts/run-dev-desktop.sh --no-build --sales` opens Sales directly after a successful build. Saved layouts require their owner/manager to apply the conversion and publish it; no installed-store configuration is rewritten automatically. Unsupported financial and printing actions remain gated.

## Tap-based topping page — 31 October 2026

Implemented an embedded, provisional topping page for order-list double-tap/Enter. Product button taps remain distinct insertions; required-choice forms block further taps until completed. Products without toppings do nothing on order-line double-tap. The item-details action still edits quantity, notes, variants, modifiers and compatible swaps while retaining existing topping selections.

The page uses effective product relationships and canonical quotation selections. Add restores removed included portions before adding paid extras. Remove arms one valid tap, removes paid extras first, and returns to Add; a second press cancels the mode. Non-removable and required replacement ingredients are protected. Combo component selection edits only that nested selection, with the complete combo quoted. Existing swaps remain unchanged unless their topping portion is explicitly removed.

All edits remain outside the order until OK receives a fresh authoritative quote. Out-of-order responses, failed requests and Cancel during confirmation cannot replace a line. Successful confirmation preserves the line ID, quantity, notes and all other lines. Price checks do not save drafts or write reusable recipes. Existing APIs and schemas are retained; no database migration was added.

Verification: 157 local tests, zero failures/errors, six environment skips. New coverage includes four pure draft tests and five JavaFX page tests, required-choice blocking, no-op items, combo components, free restoration, paid-extra-first removal, invalid disabled taps, Cancel/OK, retained drafts after preview and confirmation failures, and late/out-of-order quotations. Existing quantity/notes and cancellation regression checks pass. Screenshots at 1024 × 768, 1200 × 800 and 1920 × 1080 verify control bounds, touch target heights and keyboard/default/cancel button configuration. Physical Windows touch testing was not performed for this change. Installed-store software and data are unchanged.
