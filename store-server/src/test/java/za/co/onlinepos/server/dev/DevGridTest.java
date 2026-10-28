package za.co.onlinepos.server.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.contracts.Customers;
import za.co.onlinepos.contracts.DevData;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.LocalCommands;
import za.co.onlinepos.server.StoreServerApplication;

class DevGridTest {
  @TempDir Path directory;

  ConfigurableApplicationContext start() {
    var context =
        new SpringApplicationBuilder(StoreServerApplication.class)
            .profiles("dev")
            .run(
                "--server.port=0",
                "--pos.dev.data-dir=" + directory.resolve("db"),
                "--pos.dev.legacy-dir=" + directory.resolve("absent"));
    var store = context.getBean(DevModuleStore.class);
    try {
      context
          .getBean(DevCatalogueImport.class)
          .apply(store.json.readTree(getClass().getResourceAsStream("/catalogue-h2-seed.json")));
    } catch (java.io.IOException failure) {
      context.close();
      throw new IllegalStateException(failure);
    }
    if (store.jdbc.queryForObject(
            "SELECT branch_id FROM dev_meta.local_scope WHERE singleton=1", Integer.class)
        == null)
      context.getBean(DevStoreService.class).adopt(UUID.randomUUID(), 1, "Test business");
    return context;
  }

  Customers.Edit customer(UUID id, String first, String phone) {
    return new Customers.Edit(
        UUID.randomUUID(), id, -1, Map.of("first_name", first, "phone1", phone));
  }

  ItemCustomization.Product simple(DevCatalogueService catalogue) {
    return catalogue.catalog().products().values().stream()
        .filter(p -> p.available() && p.combo() == null && p.groups().isEmpty())
        .findFirst()
        .orElseThrow();
  }

  @Test
  void catalogueImportSharedCategoriesAndRestartPersistence() {
    int id;
    try (var context = start()) {
      var store = context.getBean(DevModuleStore.class);
      var catalogue = context.getBean(DevCatalogueService.class);
      var categories = context.getBean(DevCategoryService.class);
      assertTrue(
          store.jdbc.queryForObject("SELECT count(*) FROM public.menu_item", Integer.class) > 30);
      assertEquals(
          0, store.jdbc.queryForObject("SELECT count(*) FROM public.customers", Integer.class));
      assertTrue(
          store.jdbc.queryForObject(
                  "SELECT count(*) FROM public.branch_menu_item_price WHERE branch_id=2",
                  Integer.class)
              > 0);
      var created =
          categories.save(
              null, new DevData.Save(UUID.randomUUID(), null, "H2 shared category", 91, true));
      id = created.id();
      assertTrue(
          java.util.stream.StreamSupport.stream(
                  catalogue.state().path("tables").path("menu_category").spliterator(), false)
              .anyMatch(r -> r.path("id").asInt() == created.id()));
      var row =
          store
              .json
              .createObjectNode()
              .put("id", id)
              .put("name", "H2 renamed category")
              .put("sort_order", 92)
              .put("active", true);
      catalogue.edit(
          new CatalogueManagement.Edit(
              UUID.randomUUID(),
              store.revision(),
              List.of(
                  new CatalogueManagement.Change(
                      "menu_category",
                      List.of((long) id),
                      store.json.convertValue(
                          row,
                          new com.fasterxml.jackson.core.type.TypeReference<
                              Map<String, Object>>() {}),
                      false)),
              false));
      assertEquals(
          "H2 renamed category",
          categories.list("H2 renamed", "all", 0, 50).records().getFirst().name());
      assertEquals(1, categories.list("H2 renamed", "all", 0, 50).records().getFirst().revision());
      assertEquals(
          "numeric",
          store.jdbc.queryForObject(
              "SELECT data_type FROM information_schema.columns WHERE table_name='branch_menu_item_price' AND column_name='price'",
              String.class));
    }
    try (var context = start()) {
      var store = context.getBean(DevModuleStore.class);
      assertEquals(
          "H2 renamed category",
          store.jdbc.queryForObject(
              "SELECT name FROM public.menu_category WHERE id=?", String.class, id));
      assertEquals(
          1,
          store.jdbc.queryForObject("SELECT count(*) FROM dev_meta.import_receipt", Integer.class));
    }
  }

  @Test
  void profilesLifecycleConflictsAliasesAndSafeAudit() {
    try (var context = start()) {
      var customers = context.getBean(DevCustomerService.class);
      var store = context.getBean(DevModuleStore.class);
      UUID first = UUID.randomUUID();
      var edit = customer(first, "First", "0821234567");
      var created = customers.save(edit);
      assertEquals(created, customers.save(edit));
      assertEquals("+27821234567", created.path("profile").path("phone1").asText());
      UUID proposed = UUID.randomUUID();
      assertThrows(
          DevCategoryService.Failure.class,
          () -> customers.save(customer(proposed, "Other", "+27821234567")));
      var conflict = customers.conflicts(0).path("items").get(0);
      customers.resolve(
          UUID.fromString(conflict.path("id").asText()),
          new Customers.Resolution(UUID.randomUUID(), 0, "LINK", first, Map.of()));
      assertEquals(first, customers.canonical(proposed));
      assertFalse(customers.conflicts(0).path("items").iterator().hasNext());
      assertThrows(
          DevCategoryService.Failure.class,
          () ->
              customers.status(first, new Customers.Status(UUID.randomUUID(), 0, true, ""), true));
      var banned =
          customers.status(
              first,
              new Customers.Status(UUID.randomUUID(), 0, true, "Protected private reason"),
              true);
      assertTrue(banned.path("banned").asBoolean());
      customers.status(first, new Customers.Status(UUID.randomUUID(), 1, true, ""), false);
      customers.status(first, new Customers.Status(UUID.randomUUID(), 2, false, ""), false);
      assertTrue(customers.read(first).path("banned").asBoolean());
      customers.status(first, new Customers.Status(UUID.randomUUID(), 3, false, ""), true);
      assertFalse(customers.read(first).path("banned").asBoolean());
      assertEquals("REMOTE_UNAVAILABLE", customers.lookup("0820000000").path("status").asText());
      assertFalse(customers.sync().path("enabled").asBoolean());
      String events =
          store
              .jdbc
              .queryForList("SELECT payload FROM dev_meta.module_events", String.class)
              .toString();
      assertFalse(events.contains("Protected private reason"));
      assertFalse(events.contains("+27821234567"));
      assertFalse(created.path("fieldTimes").isEmpty());
    }
  }

  @Test
  void draftsRepriceRejectStaleOrRestrictedCustomerAndRetainSnapshots() {
    try (var context = start()) {
      var catalogue = context.getBean(DevCatalogueService.class);
      var customers = context.getBean(DevCustomerService.class);
      var drafts = context.getBean(DevDraftService.class);
      var store = context.getBean(DevModuleStore.class);
      UUID customer = UUID.randomUUID();
      customers.save(customer(customer, "Draft customer", "0831234567"));
      var product = simple(catalogue);
      var selection = ItemCustomization.Selection.empty(catalogue.catalog().revision());
      var quote =
          catalogue.quote(
              new ItemCustomization.QuoteRequest(
                  product.priceId(), product.priceRevision(), 2, selection));
      assertEquals(quote.unitPrice().multiply(BigDecimal.valueOf(2)), quote.total());
      UUID order = UUID.randomUUID();
      var create =
          new LocalCommands.CreateDraft(
              UUID.randomUUID(),
              order,
              List.of(
                  new LocalCommands.DraftLine(
                      UUID.randomUUID(), product.priceId(), product.priceRevision(), 2, selection)),
              customer);
      var saved = drafts.create(create);
      assertEquals(saved, drafts.create(create));
      var before = drafts.get(order).deepCopy();
      store.tx.executeWithoutResult(
          s -> {
            store.lock();
            store.bump();
          });
      assertThrows(
          ItemCustomization.Stale.class,
          () ->
              drafts.create(
                  new LocalCommands.CreateDraft(
                      UUID.randomUUID(), UUID.randomUUID(), create.lines(), customer)));
      assertEquals(before, drafts.get(order));
      customers.status(customer, new Customers.Status(UUID.randomUUID(), 0, true, "Banned"), true);
      var current = simple(catalogue);
      var lines =
          List.of(
              new LocalCommands.DraftLine(
                  UUID.randomUUID(),
                  current.priceId(),
                  current.priceRevision(),
                  1,
                  ItemCustomization.Selection.empty(store.revision())));
      assertThrows(
          DevCategoryService.Failure.class,
          () ->
              drafts.create(
                  new LocalCommands.CreateDraft(
                      UUID.randomUUID(), UUID.randomUUID(), lines, customer)));
      drafts.cancel(order, new LocalCommands.CancelDraft(UUID.randomUUID(), 0));
      assertEquals(
          "CANCELLED", customers.history(customer, 0).path("items").get(0).path("state").asText());
      customers.status(customer, new Customers.Status(UUID.randomUUID(), 1, false, ""), true);
      customers.status(customer, new Customers.Status(UUID.randomUUID(), 2, true, ""), false);
      assertThrows(
          DevCategoryService.Failure.class,
          () ->
              drafts.create(
                  new LocalCommands.CreateDraft(
                      UUID.randomUUID(), UUID.randomUUID(), lines, customer)));
      drafts.create(new LocalCommands.CreateDraft(UUID.randomUUID(), UUID.randomUUID(), lines));
    }
  }

  @Test
  void failedJournalRollsBackDraftReceiptAndOutbox() {
    try (var context = start()) {
      var store = context.getBean(DevModuleStore.class);
      var catalogue = context.getBean(DevCatalogueService.class);
      var drafts = context.getBean(DevDraftService.class);
      var product = simple(catalogue);
      long outbox = store.jdbc.queryForObject("SELECT count(*) FROM dev_meta.outbox", Long.class);
      store.jdbc.execute(
          "ALTER TABLE dev_meta.module_events ADD CONSTRAINT reject_draft_test CHECK(action<>'DRAFT_CREATED')");
      UUID command = UUID.randomUUID();
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              drafts.create(
                  new LocalCommands.CreateDraft(
                      command,
                      UUID.randomUUID(),
                      List.of(
                          new LocalCommands.DraftLine(
                              UUID.randomUUID(),
                              product.priceId(),
                              product.priceRevision(),
                              1,
                              ItemCustomization.Selection.empty(store.revision()))))));
      assertEquals(
          0, store.jdbc.queryForObject("SELECT count(*) FROM dev_meta.drafts", Integer.class));
      assertEquals(
          0,
          store.jdbc.queryForObject(
              "SELECT count(*) FROM dev_meta.commands WHERE command_id=?", Integer.class, command));
      assertEquals(
          outbox, store.jdbc.queryForObject("SELECT count(*) FROM dev_meta.outbox", Long.class));
    }
  }

  @Test
  void realCatalogueRulesPersistExtrasRequiredOptionsAndFixedCombos() {
    try (var context = start()) {
      var catalogue = context.getBean(DevCatalogueService.class);
      var store = context.getBean(DevModuleStore.class);
      var plain =
          catalogue.catalog().products().values().stream()
              .filter(p -> p.available() && p.combo() == null && p.groups().isEmpty())
              .limit(3)
              .toList();
      var first = plain.get(0);
      var second = plain.get(1);
      var referenced =
          catalogue.catalog().products().values().stream()
              .flatMap(p -> p.groups().stream())
              .flatMap(g -> g.choices().stream())
              .map(ItemCustomization.Choice::productPriceId)
              .filter(java.util.Objects::nonNull)
              .collect(java.util.stream.Collectors.toSet());
      var base =
          catalogue.catalog().products().values().stream()
              .filter(
                  p ->
                      p.available()
                          && p.combo() == null
                          && p.groups().isEmpty()
                          && !p.priceId().equals(first.priceId())
                          && !p.priceId().equals(second.priceId())
                          && !referenced.contains(p.priceId()))
              .findFirst()
              .orElseThrow();
      var topping =
          catalogue.catalog().extras().values().stream()
              .filter(e -> e.available() && e.price() != null && e.key().startsWith("burger:"))
              .findFirst()
              .orElseThrow();
      var group =
          new ItemCustomization.Group(
              "protein-choice",
              "Protein",
              1,
              1,
              null,
              List.of(
                  new ItemCustomization.Choice(
                      "chicken", "Chicken", new BigDecimal("2.00"), null, null, false, true),
                  new ItemCustomization.Choice(
                      "beef", "Beef", BigDecimal.ZERO, null, null, false, true)));
      var item =
          new ItemCustomization.ItemRule(
              List.of(),
              Map.of(
                  topping.key(),
                  new ItemCustomization.ExtraRule(new BigDecimal("5.00"), 1, true, "")),
              List.of(group),
              true);
      var combo =
          new ItemCustomization.Combo(
              new BigDecimal("99.00"),
              List.of(
                  new ItemCustomization.Slot(
                      "drink",
                      "Drink",
                      1,
                      second.priceId(),
                      Map.of(
                          second.priceId(),
                          BigDecimal.ZERO,
                          first.priceId(),
                          new BigDecimal("12.00")))));
      var rules =
          new ItemCustomization.Rules(
              Map.of(first.category(), List.of(topping.key())),
              Map.of(),
              Map.of(first.priceId(), item),
              Map.of(base.priceId(), combo));
      catalogue.rules(new ItemCustomization.Edit(UUID.randomUUID(), store.revision(), rules));
      long revision = store.revision();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              catalogue.quote(
                  new ItemCustomization.QuoteRequest(
                      first.priceId(), revision, 1, ItemCustomization.Selection.empty(revision))));
      var selection =
          new ItemCustomization.Selection(
              revision,
              Map.of(),
              Map.of(topping.key(), 2),
              List.of(),
              Map.of("protein-choice", List.of("chicken")),
              List.of());
      var quote =
          catalogue.quote(
              new ItemCustomization.QuoteRequest(first.priceId(), revision, 2, selection));
      assertEquals(
          0,
          quote
              .total()
              .compareTo(
                  first.price().add(new BigDecimal("12.00")).multiply(BigDecimal.valueOf(2))));
      var comboQuote =
          catalogue.quote(
              new ItemCustomization.QuoteRequest(
                  base.priceId(), revision, 1, ItemCustomization.Selection.empty(revision)));
      assertEquals(0, comboQuote.total().compareTo(new BigDecimal("99.00")));
      var format = new za.co.onlinepos.server.catalogue.SnapshotFormat();
      assertEquals(format.tree(rules), format.tree(catalogue.catalog().rules()));
    }
  }

  @Test
  void calculatedMultiSelectionDraftSnapshotSurvivesCatalogueChangesAndRestart() {
    UUID order = UUID.randomUUID();
    String saved;
    try (var context = start()) {
      var catalogue = context.getBean(DevCatalogueService.class);
      var store = context.getBean(DevModuleStore.class);
      var drafts = context.getBean(DevDraftService.class);
      var catalog = catalogue.catalog();
      var referenced =
          catalog.products().values().stream()
              .flatMap(p -> p.groups().stream())
              .flatMap(g -> g.choices().stream())
              .map(ItemCustomization.Choice::productPriceId)
              .collect(java.util.stream.Collectors.toSet());
      var candidates =
          catalog.products().values().stream()
              .filter(
                  p ->
                      p.available()
                          && p.combo() == null
                          && p.groups().isEmpty()
                          && p.extras().stream().noneMatch(e -> e.included() > 0)
                          && !referenced.contains(p.priceId()))
              .limit(3)
              .toList();
      assertEquals(3, candidates.size());
      var base = candidates.get(0);
      var a = candidates.get(1);
      var b = candidates.get(2);
      var combo =
          new ItemCustomization.Combo(
              BigDecimal.ZERO,
              List.of(
                  new ItemCustomization.Slot(
                      "choices",
                      "Choose products",
                      1,
                      null,
                      Map.of(
                          a.priceId(),
                          new BigDecimal("99.00"),
                          b.priceId(),
                          new BigDecimal("50.00")),
                      1,
                      2,
                      Map.of(a.priceId(), 2, b.priceId(), 1))),
              ItemCustomization.PricingMode.CALCULATED);
      catalogue.rules(
          new ItemCustomization.Edit(
              UUID.randomUUID(),
              store.revision(),
              new ItemCustomization.Rules(
                  Map.of(), Map.of(), Map.of(), Map.of(base.priceId(), combo))));
      long revision = store.revision();
      var selection =
          new ItemCustomization.Selection(
              revision,
              Map.of(),
              Map.of(),
              List.of(),
              Map.of(),
              List.of(
                  new ItemCustomization.Component("choices", a.priceId(), revision, null),
                  new ItemCustomization.Component("choices", b.priceId(), revision, null)));
      var quote =
          catalogue.quote(
              new ItemCustomization.QuoteRequest(base.priceId(), revision, 2, selection));
      assertEquals(
          0,
          quote
              .total()
              .compareTo(
                  a.price()
                      .multiply(new BigDecimal("2"))
                      .add(b.price())
                      .multiply(new BigDecimal("2"))));
      assertEquals(ItemCustomization.PricingMode.CALCULATED, quote.pricingMode());
      var command =
          new LocalCommands.CreateDraft(
              UUID.randomUUID(),
              order,
              List.of(
                  new LocalCommands.DraftLine(
                      UUID.randomUUID(), base.priceId(), revision, 2, quote.selection())));
      assertEquals(drafts.create(command), drafts.create(command));
      saved = drafts.get(order).toString();
      context
          .getBean(DevCategoryService.class)
          .save(null, new DevData.Save(UUID.randomUUID(), null, "Revision change", 999, true));
      assertEquals(saved, drafts.get(order).toString());
      assertThrows(
          ItemCustomization.Stale.class,
          () ->
              drafts.create(
                  new LocalCommands.CreateDraft(
                      UUID.randomUUID(), UUID.randomUUID(), command.lines())));
    }
    try (var context = start()) {
      assertEquals(saved, context.getBean(DevDraftService.class).get(order).toString());
    }
  }
}
