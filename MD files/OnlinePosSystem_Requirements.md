# OnlinePosSystem: Commercial Product Requirements

**Document type:** Product and implementation requirements  
**Status:** Target specification, not a claim that every feature is implemented  
**Updated:** 27 October 2026  
**Primary audience:** Codex and OnlinePosSystem developers

## 1. Product vision

OnlinePosSystem must be commercially sellable as independent, complementary products:

1. **Standalone Offline POS:** A JavaFX desktop POS with its own local Spring Boot backend and database. It must operate without a subscription to, account on, or connection with the OnlinePosSystem cloud product.
2. **Standalone Cloud Platform:** A separately deployed Spring Boot cloud application and PostgreSQL database that can serve a business without any desktop POS terminals.
3. **Optional POS + Cloud Integration:** An independently enabled component that securely pairs the two products and synchronizes supported configuration between them.

**Core rule:** The offline POS and the cloud must complement each other, not depend on each other. Purchasing, installing, upgrading, and operating either product must not require purchasing the other.

### 1.1 Commercial deployments

- Each commercial business purchasing the **cloud** receives its **own independent cloud deployment, PostgreSQL database, API endpoint, credentials, configuration, and backups**.
- There is **no shared, multi-tenant cloud database for unrelated businesses** in this architecture.
- One business can own any practical number of stores or branches within its own deployment.
- Each store using the desktop product operates through its **own local Master POS** and associated Slave terminals.
- The existing Pete's Pizza environment is one customer/business configuration, **not** a hardcoded prerequisite or default for every installation.
- POS-only businesses do not need any cloud deployment.

## 2. Architecture and independence requirements

### 2.1 Offline POS

- Retain the existing JavaFX desktop application, local Spring Boot backend, JDBC repositories, transaction management, and Master/Slave model.
- JavaFX communicates with its backend through existing service/API contracts, not direct database access from controllers.
- All local business operations use local persistence and must continue without an internet connection.
- Initial owner, business, store, and menu setup must be possible entirely locally. Downloading installation files or dependencies is separate from cloud product registration.
- Do not place mandatory cloud API calls in local authentication, menu maintenance, checkout, or order persistence paths.
- Never require a cloud registration, provisioning token, or subscription to use the purchased standalone POS.
- Existing capabilities that are unfinished remain identified as unfinished; do not substitute mock implementations.

### 2.2 Cloud Platform

- Retain and extend the existing Spring Boot cloud application, its website, authentication, and supported public/menu APIs.
- Support a configurable business profile, stores, shared catalogue, staff, and cloud-specific services without requiring any registered Master POS.
- The cloud must be able to serve a cloud-only business independently.
- Existing cloud customer-facing functionality must remain compatible during changes.

### 2.3 Optional integration boundary

- Isolate pairing and synchronization behind optional integration interfaces/adapters.
- The integration must be **disabled by default** for POS-only and cloud-only installations.
- Do not create a parallel POS application, duplicate cart, duplicate menu editor, or duplicate business-logic stack.
- Both components retain their own durable storage, local identity, and ability to work while disconnected.
- A business must be able to add the integration **after** using either product independently, without losing its existing records.

## 3. Database and persistence requirements

### 3.1 Desktop environments

| Environment | Database | Requirement |
| --- | --- | --- |
| Development | H2 | Persistent, file-based database, not an in-memory-only database |
| Production | PostgreSQL | Primary local store database, independent of the business cloud database |
| Production fallback | H2 | Planned fallback capability; **not considered implemented or safe until separately developed and tested** |

- Select the active backend database through the approved startup configuration or startup connection test, **not** a runtime database router.
- Prefer PostgreSQL in production. If a production fallback is eventually implemented, it must include safe transaction durability, reconciliation, and controlled recovery; never silently discard H2 changes on returning to PostgreSQL.
- Use separate H2 database files for development and any eventual production fallback.
- Data must persist across application and computer restarts.
- Keep compatible schemas and behavior across H2 and PostgreSQL, with database-specific SQL or migrations where required.
- Retain the existing desktop JDBC/transaction approach; do **not** introduce JPA or Hibernate solely for the Local Data Manager or menu features.
- Use parameterized SQL, proper transaction boundaries, rollback handling, and versioned non-destructive migrations.
- Do not connect the desktop product directly to the existing **cloud** production PostgreSQL database as its local operational database.

### 3.2 Existing data and installations

- Existing business/store IDs, products referenced by orders, completed orders, payments, staff records, audit history, and synchronization records must be preserved.
- New installations start with an **empty business catalogue** and no Pete's Pizza-specific store or menu seed data.
- Existing Pete's Pizza installations must be migrated without automatically wiping order history or breaking foreign keys.
- The requested clearing of the **H2 development catalogue** must be an explicit, backed-up, one-time operation. Archive referenced products instead of deleting historical references.
- Never silently reset a business owner's menu on restart or rerun historical seed scripts that overwrite their changes.
- Do not perform destructive production database changes without explicit approval.

## 4. Business and multi-store management

The solution must support any practical number of independently configured stores under one business, without fixed names or IDs.

### 4.1 Business setup

- Create and maintain a business profile, including its display name and relevant settings.
- The offline POS-only path must support local setup with no cloud prerequisites.
- The cloud-only path must support setup with no desktop POS prerequisites.
- In integrated installations, pair the same real business across the two systems, with explicit reconciliation rather than assuming their local numeric IDs match.

### 4.2 Store management

Owners must be able to:

- Create, rename, edit, activate, archive, and configure stores.
- Maintain contact details, address, store codes, and store-specific operational settings.
- Assign staff, terminals, menus, product availability, and prices to stores.
- Configure a Master and approved Slave terminals for each store.
- Create stores without a hardcoded upper limit or application redeployment.

Requirements:

- Remove hardcoded Kenridge/Uitzicht names, branch IDs, buttons, filters, and two-store assumptions from general-purpose workflows.
- New installations must allow creating the first store from scratch.
- Existing store records and historical associations must remain valid during migration.
- Cashier transactions must always be associated with the correct store.
- Store-local operational databases must remain isolated; Store A's Master must not access or modify Store B's local transactional records by mistake.
- In POS-only deployments, each store remains independently operational. Cross-store communication must not secretly rely on cloud infrastructure.

## 5. Fully configurable, owner-built menu

**Business owners must be able to construct their entire catalogue through the existing JavaFX menu management UI, without developer-written SQL or code changes.** The cloud-only product must expose equivalent supported catalogue management through its own interface/API.

### 5.1 Hierarchical categories

- Support unlimited owner-created categories and nested subcategories within practical database/resource constraints.
- A category can contain both products and further subcategories.
- Provide create, rename, describe, reorder, move, activate/archive, and view operations.
- Allow identical category names under different parents when correctly scoped to the business.
- Reject circular nesting and invalid parent relationships.
- Allow owners to move products between categories without losing pricing/order history.
- Do not hardcode category names, hierarchy depth, or special IDs for pizza categories.
- Treat categorization separately from the product's type and its modifier/option groups.

**Existing SQL considerations:** The supplied SQL has separate `menu_category` and `pizza_category` tables, with fixed catalogue seed IDs. Inspect and migrate these carefully to a universal hierarchy or a compatible equivalent without losing product references. Do not blindly execute a sample schema over existing production tables.

### 5.2 Products

Owners must be able to:

- Create, edit, archive, reorder, categorize, and price products.
- Add descriptions and optional images where supported.
- Define branch/store availability and store-specific prices.
- Configure variants or sizes and reusable modifier/option groups.
- Configure required/optional selections and any allowed add-ons.
- Manage standard, configurable, and combo products through the existing product editor.

Preserve existing working pizza sizes, toppings, burger recipes, pricing, and customizations until compatible replacements or migrations are proven.

### 5.3 Dynamic combo builder

- Integrate combo creation into the **existing** product management/editor UI, not a separate ordering system.
- Define included products, mandatory and optional groups, minimum/maximum selections, quantities, product/variant eligibility, and additional upgrade charges.
- Support fixed-price and calculated-price combos, including store-specific pricing rules.
- Prevent recursive combo dependencies and invalid references.
- Backend services validate selections and calculate final charges using decimal-safe money calculations; never trust client-supplied totals.
- Existing cart, checkout, receipts, order history, and audit flows must eventually preserve combo component and price snapshots.
- Editing a combo later must not alter historical purchased combinations.

### 5.4 Existing JavaFX UI

- Improve the **current** Menu Management screen, rather than building a parallel manager.
- Show an expandable category/subcategory tree with Add Category, Add Subcategory, Rename, Move, Reorder, Archive, and Add Product actions.
- Make the existing cashier menu and customization panel resolve arbitrary category depth and owner-configured products dynamically.
- Show a usable empty-menu state with an **Add Category** action for new installations.

## 6. Staff authentication, authorization, and audit

- Implement the essential authentication foundation required by owner-only administration as part of the relevant management work.
- Reuse the existing staff PIN/authentication model, securing PINs through the established hashing approach.
- Support at least Owner, Manager, and Cashier roles with explicit, enforceable permissions.
- Only authorized owners may create/archive stores or change business-wide ownership settings.
- Managers may perform explicitly delegated management tasks within assigned stores.
- Cashiers may perform authorized operational tasks without business-wide administrative privileges.
- Enforce permissions **server-side**, not only by hiding JavaFX buttons.
- Provide one-time secure initial Owner setup in standalone POS and independent cloud setup flows. No hardcoded owner credentials or default PIN bypass.
- Authenticate eligible staff offline using previously provisioned/verifiable local credentials and permissions. Being offline must not grant extra privileges.
- Preserve actor, timestamp, entity, and before/after details for applicable auditable configuration changes using the existing audit infrastructure.
- Staff may be assigned to multiple stores. In integrated installations, cross-store assignments can synchronize; POS-only setups must not require cloud verification for permitted local actions.

## 7. Master and Slave terminal architecture

- Each store maintains its own Master POS and local backend/database.
- Slave terminals access the correct store's Master through the approved LAN/API relationship rather than independently writing into its database.
- Do not open the H2 TCP port merely to facilitate Slave access when the local backend API already provides the needed services.
- Preserve the approved emergency Slave-data/failover design as a separate stage; do not describe it as complete unless implemented and tested.
- Prevent a Slave in one store from joining another store without authorization.
- Catalogue updates must be made available to connected Slaves using the existing supported local distribution mechanism.
- If a Master fails, only claim failover behaviors that are actually implemented.

## 8. Optional POS and cloud pairing

- A business owner must be able to operate either product alone, then link them later.
- Pairing must require deliberate, authorized action and secure credentials/tokens.
- Validate both installations represent the **same business** and map their stores and identities correctly.
- Initial pairing must reconcile existing business, store, category, product, and staff records without blindly merging by numeric ID or overwriting either database.
- Establish globally unique synchronization identities, origin identifiers, and durable version/checkpoint metadata.
- The cloud must not demand a Master POS to function; the POS must not demand a cloud endpoint to start or sell.
- A business using the cloud has one business-specific cloud deployment, not a shared service for unrelated businesses.

## 9. Bidirectional configuration synchronization (optional integration)

**When integration is enabled**, management changes made on either side must eventually reach the other side, even after internet outages.

### 9.1 Offline-first edits

- Authorized owners may create, edit, and manage menus, categories, products, combos, staff, store details, and supported business settings while the Master is disconnected.
- Save changes immediately to the local Master database.
- Distinguish locally saved, pending-upload, accepted, and conflict/failed states.
- Local changes made without an optional cloud subscription remain valid local changes and should not queue against nonexistent cloud infrastructure.

### 9.2 Sync protocol

- Use or extend the existing durable transactional outbox and idempotency facilities where they exist.
- Every uploadable change carries a unique operation ID, originating installation/store ID, actor, affected entity, base version, and timestamp.
- Queue events atomically with the underlying configuration changes.
- Retry pending changes with backoff; survive crashes, reboots, offline periods, and partial delivery.
- Apply operations idempotently in the business-specific cloud database.
- When connectivity returns: authenticate, upload queued configuration changes, receive acknowledgements, download the accepted remote changes, and distribute approved updates to relevant stores.
- Synchronize store-specific overrides only to relevant stores; keep device/printer-local settings local unless explicitly shareable.
- Do **not** blindly use last-write-wins, overwrite unacknowledged edits, or delete pending changes before confirmation.
- Detect concurrent updates and provide explicit authorized conflict resolution for non-mergeable cases.
- Allow safe pending provisioning of store records while offline, but do not claim a remote store or terminal is provisioned until the cloud acknowledges it.
- Show connection state, pending count, last successful sync, failed operations, and conflicts in JavaFX.

### 9.3 Sync scope boundaries

- **Configuration synchronization** is part of the optional integration requirement.
- **Continuous sales, payment, refund, and transaction synchronization is a separate planned stage.** Do not enable, advertise, or simulate it as complete before it is implemented and tested.
- Never represent unsynchronized local orders as cloud-synchronized.
- Preserve local transactional data during connectivity loss.

## 10. Local Data Manager (development utility)

- Integrate into the **existing JavaFX application** at **Settings > Developer Tools > Local Data Manager**.
- Display Developer Tools only in the appropriate development environment and enforce applicable permissions.
- Show actual persisted H2 records through the existing Spring Boot backend.
- Support read, search/filter, refresh, and validated create/edit/delete operations for permitted entities.
- Do not provide unrestricted SQL execution or expose sensitive authentication/payment internals.
- Reuse existing JDBC/service/transaction patterns and respect schema constraints.
- Show the selected database and connection state; never claim success before commit.
- Save edits permanently across restarts. Do not connect to cloud production PostgreSQL from the development manager.
- Unfinished POS modules may remain visible with clear unavailable states, but must not create fake saved transactions.

## 11. Checkout, payments, reports, and fallback scope

The current POS has **draft ordering**, while the following have been identified as not yet fully implemented:

- Production-ready checkout and payment handling.
- Full reporting.
- Continuous live cloud transaction synchronization.
- Production H2 failover and recovery.

For the current architecture and management work:

- Preserve and extend the existing draft order flow with correct business/store IDs.
- Prepare store-aware data ownership and integration seams for these future capabilities.
- Do not create placeholder checkout, fake reports, unverified payment recording, or pretend-live sync.
- Implement these functions in dedicated, separately reviewed stages with their own acceptance tests.
- Production H2 failover must not be a superficial automatic DataSource swap. It requires durable offline transaction storage, reconciliation, and safe recovery before being enabled for financial operations.

## 12. Data ownership and conflict principles

- **POS-only:** Local store database is authoritative for that store's operational data and locally managed configuration.
- **Cloud-only:** The business's cloud database is authoritative for its cloud-managed data.
- **Integrated:** Both can originate permitted configuration changes; the cloud holds the business-wide consolidated view after acknowledgment/reconciliation, while store Masters remain authoritative for their local operational transactions until dedicated transaction synchronization is implemented.
- Store-local printer, device, and operational settings must not be unexpectedly overwritten by business-wide updates.
- Resolve simultaneous edits by version/conflict rules rather than assuming one database always wins.
- Store IDs, entity IDs, and staff identity mappings must be collision-safe when installations are connected after independent operation.
- Never mingle the data of unrelated commercial businesses.

## 13. Security and reliability

- Authenticate and authorize management and provisioning operations on both sides.
- Protect staff PIN hashes, database credentials, pairing secrets, API tokens, and local database files.
- Use least-privilege service accounts and secure transport for optional cloud integration.
- Constrain all accesses to the correct business, store, and staff role.
- Record audit events without exposing secrets.
- Preserve order and financial history despite changes to the active catalogue.
- Prevent duplicate sync operations and conflicting terminal/store registrations.
- Use compatible schema migrations with rollback/backup planning and PostgreSQL integration tests.
- Prefer explicit errors and safe unavailable states over silent fallback or misleading success messages.

## 14. Implementation guidance for Codex

1. Inspect the current desktop and cloud repositories, SQL schema, migration history, APIs, JavaFX controllers/FXML, authentication, and synchronization/outbox contracts.
2. Document the gap between the **existing implemented state** and these **target requirements**. Do not assume unfinished features exist.
3. Identify hardcoded Pete's Pizza categories and two-branch assumptions, plus cloud dependencies blocking standalone POS use.
4. Preserve working code and use incremental, versioned, non-destructive migrations.
5. Extend the current UI, backend services, repositories, and API contracts; do not produce parallel business logic.
6. Implement the smallest complete and testable increments; never mark a stage complete with mock-only behavior.
7. Keep optional integration disabled when the customer has not purchased or configured it.
8. Keep H2 development, local PostgreSQL production, and cloud PostgreSQL separate and clearly named.
9. Do not deploy to live customer systems or execute destructive production migrations without approval.
10. Report modified files, migration details, test results, incomplete capabilities, and any unresolved architectural choices.

### Suggested delivery stages

1. **Standalone foundations:** Local business and owner setup, independent H2 development and PostgreSQL production configuration.
2. **Generic multi-store catalogue:** Dynamic categories/subcategories, products/combos, store management, local staff permissions, and existing draft-order integration.
3. **Independent cloud management:** Business-specific cloud deployment configuration, cloud-only store/catalogue/staff management without POS dependency.
4. **Optional pairing:** Explicit secure pairing and record/store reconciliation, with zero impact when disabled.
5. **Optional configuration synchronization:** Durable bidirectional offline-first config sync, recovery, conflict handling, and UI status.
6. **Separate operational features:** Production checkout/payments, reporting, continuous transaction sync, and production H2/Slave failover in later approved stages.

Stages may be split further if needed, but deliver each stage end to end with real persistence and tests.

## 15. Acceptance criteria

### 15.1 POS-only customer

- Install and start the POS with no cloud product, cloud login, or internet access required for normal operation.
- Locally create a business, Owner, first store, staff, and an empty menu.
- Create arbitrary nested categories and products; configure options, combos, availability, and pricing.
- View and edit the menu in the existing JavaFX screens.
- Restart and verify all data persists locally.
- Continue using all **implemented** local features with no cloud present.

### 15.2 Cloud-only customer

- Deploy the cloud and its PostgreSQL database independently.
- Set up business, stores, users, and supported cloud catalogue functionality without registering a Master POS.
- Verify existing customer-facing APIs and web features remain available.

### 15.3 Integrated customer

- Pair independently created POS and cloud configurations securely.
- Reconcile existing records without data loss or duplicate store records.
- Modify a catalogue item in the POS while disconnected; save immediately.
- Restore connectivity; verify the queued change is durably acknowledged and appears in the cloud.
- Modify supported shared configuration in the cloud; verify the correct store Masters can retrieve/apply it.
- Verify concurrent conflicting edits are detected rather than silently overwritten.
- Disable or disconnect the integration and confirm the POS and cloud continue independently.

### 15.4 Multi-store and migration

- Create a third and fourth store without changing code or seeding fixed IDs.
- Maintain store-specific prices and availability for a shared product.
- Preserve existing Kenridge/Uitzicht identities and order history on upgrades.
- Verify a newly installed business has no forced Pete's Pizza catalogue or branches.
- Validate H2 development behavior and PostgreSQL production compatibility separately.

## 16. Explicit non-goals for the current architecture increment

- Converting independent business cloud deployments into one multi-tenant shared cloud.
- Replacing JavaFX with another desktop UI.
- Rewriting the existing desktop JDBC persistence layer as JPA/Hibernate.
- Building a second cart, checkout, menu editor, or POS application.
- Claiming checkout, payment processing, reporting, live transaction sync, or production H2 fallback works before those stages are implemented.
- Mandating cloud provisioning, cloud availability, or an OnlinePosSystem-hosted licensing service for POS-only usage.
- Building a complete subscription, billing, or license-enforcement platform now. Licensing interfaces may be designed for later use.

---

**Governing requirement:** OnlinePosSystem is **two independently sellable and independently operable products**, a local POS and a business-specific cloud platform, with a **third optional integration component**. Independence, offline-first local operation, dynamic owner configuration, multi-store support, durable data, and safe synchronization must govern every implementation decision.
