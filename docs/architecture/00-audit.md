# Stage 0 source audit baseline

Approved source baseline: https://github.com/the-lazy-coder-03/OnlinePosSystem/tree/ad708b93e1026605f15e39dc02ebce790948ba0a

The audit inspected GitHub master, not the similarly named local directory whose remote is pos.anton.git.
Production database contents, applied migration checksums and deployed commit were not verified.
These are prerequisites for production cutover, not evidence supplied by source inspection.

## Existing cloud implementation

Paths in this section are relative to that pinned cloud revision:

- `Misc/pom.xml`: Spring Boot 3.5.16, Java 17, PostgreSQL/JPA, MVC, security, Thymeleaf and WebSockets.
- `src/main/java/org/example/onlinepossystem/bootstrap/MigrationSqlRunner.java`: custom checksum runner,
  migration lock and separate owner-credential migrate profile; no Flyway conversion is proposed for cloud.
- `SQL files/migration.sql`: catalogue, branch prices, customer/order schema and special snapshots.
- `SQL files/rls-v1.sql` through `rls-v9-driver-rates.sql`: RLS and subsequent driver/security changes.
- `src/main/resources/application.properties`: Hibernate validate at runtime.
- `.github/workflows/ci-cd.yml`: cloud master pushes trigger deployment; never push there as part of POS setup.

Seed branches: 1 Kenridge; 2 Uitzicht. Verify these during snapshot bootstrap.

The sources declare 54 persistent tables plus the runner-created `public.app_migration_state`.
Catalogue areas include menu categories/products, branch menu prices, modifier groups/options, burger
recipes and components, salads, pizza categories/sizes/default ingredients/base options, branch pizza
and extra prices, scheduled specials and special components/add-ons. Order data includes typed menu,
burger, pizza and special selection records with persisted price/name snapshots.

Customer authentication uses `customers`; legacy `/api/staff/login` returns 410. The new local staff
database must remain independent of website account passwords.

Delivery tables are `driver_profile`, `driver_shift`, `delivery_assignment`, `driver_dispatch_event`,
`delivery_payment` and `driver_settlement`, introduced in `SQL files/rls-v6-drivers.sql`.

## Compatibility requirements

- Preserve existing numeric cloud order IDs and foreign keys. Introduce immutable UUIDs additively.
- Reuse existing browser idempotency semantics: `/api/orders` requires an Idempotency-Key scoped to customer.
- Cloud status strings are Pending, Preparing, Completed and Rejected. Add acceptance state separately.
- `ordering/service/OrderResponseMapper.java` flattens some burger options for display. A complete typed
  sync snapshot must preserve more structure than the existing display response.
- `ordering/service/OrderTotalCalculator.java` uses stored prices and currently zero separate delivery
  fee/discount. Preserve these charging rules; no new tax or discretionary discount calculation.
- `catalog/service/CatalogOrderResolver.java`, `catalog/pizza/service/PizzaService.java` and
  `special/service/SpecialService.java` are pricing compatibility references.
- `modifier_option.additional_price` and `salad_ingredients.price` are global. Introduce branch-effective
  prices before enabling independent local branch price authority.
- `ordering/service/OrderRequestValidator.java` requires verified Google delivery addresses. Local
  telephone delivery needs a separate explicitly manual address path, without weakening website checks.
- `driver/DriverDispatchService.java` calculates cash owed from verified payment amounts. External card
  payments need an explicit ledger/projection so drivers are not charged cash for card-paid sales.
- No complete stock ledger, per-terminal print subsystem, durable sync outbox/inbox, UUID order identity
  or store-confirmation lifecycle exists in the audited code.
- Existing after-commit STOMP events are notification hints, not durable replay.

## Existing API groups

Catalogue: `/api/branches/{branchId}/menu-items`, `/pizzas`, pizza detail/quote, `/specials`, week/quote.
Orders: `/api/orders`, `/mine`, `/menu`, `/pending`, `/{id}/status`; `/api/admin/orders` and status/webhook.
The admin webhook republishes notifications; it does not import orders.
Admin catalogue: `/admin` form routes for product/definition/price edits, plus `/api/admin/specials`.
Drivers: `/api/driver` clock-in/out, claim, pickup, delivered and returning;
`/api/admin/dispatch` assignments, driver rates and shift cash-up.
Accounts: `/api/auth/*`, `/api/me`, customer/account admin and profile routes.
Realtime: `/ws`, branch admin topics and private customer/driver queues.

New `/api/store-sync/v1/...` APIs are proposals for Stage 9 and do not exist in this repository or cloud yet.

## Audit test evidence

The isolated cloud copy compiled and packaged; 49 selected unit tests passed. An earlier selection
included 16 database-backed tests which could not initialize without TEST_DATASOURCE_URL. Full cloud
database/RLS, browser, Windows and replication tests were not executed. No cloud source was changed.

The complete approved Stage 0 report remains in the originating chat. This file records its durable
compatibility baseline; the decisions and roadmap specify implementation precedence and stage gates.
