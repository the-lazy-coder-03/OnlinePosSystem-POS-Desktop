package za.co.onlinepos.server.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import za.co.onlinepos.server.StoreServerApplication;

class DevStoreTest {
  @TempDir Path directory;

  org.springframework.context.ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(StoreServerApplication.class)
        .profiles("dev")
        .run(
            "--server.port=0",
            "--pos.dev.data-dir=" + directory.resolve("db"),
            "--pos.dev.legacy-dir=" + directory.resolve("absent"));
  }

  @Test
  void emptyInstallationHasNoRestaurantRecordsAndPersistsItsOwnStore() {
    UUID storeId;
    try (var context = start()) {
      var db = context.getBean(DevModuleStore.class);
      var service = context.getBean(DevStoreService.class);
      assertEquals(0, db.jdbc.queryForObject("SELECT count(*) FROM public.branch", Integer.class));
      assertEquals(
          0, db.jdbc.queryForObject("SELECT count(*) FROM public.menu_item", Integer.class));
      assertEquals(
          0, db.jdbc.queryForObject("SELECT count(*) FROM public.menu_category", Integer.class));
      var setup = new DevStoreService.Setup(UUID.randomUUID(), "Example business", "Durban", "DUR");
      var first = service.setup(setup);
      assertEquals(first, service.setup(setup));
      assertEquals("Durban", db.branchName());
      storeId = db.jdbc.queryForObject("SELECT store_uuid FROM public.branch", UUID.class);
      assertTrue(context.getBean(DevCatalogueService.class).catalog().products().isEmpty());
      assertThrows(
          DevCategoryService.Failure.class,
          () ->
              service.setup(
                  new DevStoreService.Setup(UUID.randomUUID(), "Other", "Other", "OTHER")));
    }
    try (var context = start()) {
      var db = context.getBean(DevModuleStore.class);
      assertEquals("Durban", db.branchName());
      assertEquals(
          storeId, db.jdbc.queryForObject("SELECT store_uuid FROM public.branch", UUID.class));
      assertEquals(
          0, db.jdbc.queryForObject("SELECT count(*) FROM public.menu_item", Integer.class));
      assertFalse(
          (Boolean)
              ((Map<?, ?>) context.getBean(DevStoreService.class).state())
                  .get("integrationEnabled"));
    }
  }

  @Test
  void explicitThirdStoreAdoptionDoesNotSelectByNameOrRenumberLegacyIds() {
    try (var context = start()) {
      var db = context.getBean(DevModuleStore.class);
      db.jdbc.update(
          "INSERT INTO public.branch(branch_id,name) VALUES(1,'Original'),(2,'Second'),(37,'Third store')");
      context.getBean(DevStoreService.class).adopt(UUID.randomUUID(), 37, "Example business");
      assertEquals(37, db.branchId());
      assertEquals("Third store", db.branchName());
      assertTrue(context.getBean(DevCatalogueService.class).catalog().products().isEmpty());
      assertEquals(3, db.jdbc.queryForObject("SELECT count(*) FROM public.branch", Integer.class));
    }
  }

  @Test
  void nestedCategoryAvailabilityAndCycleRollbackUseRealSourceRecords() {
    try (var context = start()) {
      var db = context.getBean(DevModuleStore.class);
      var stores = context.getBean(DevStoreService.class);
      stores.setup(new DevStoreService.Setup(UUID.randomUUID(), "Example", "Durban", "DUR"));
      var catalogue = context.getBean(DevCatalogueService.class);
      var categories = context.getBean(DevCategoryService.class);
      var parent =
          categories.save(
              null,
              new za.co.onlinepos.contracts.DevData.Save(
                  UUID.randomUUID(), null, "Parent", 0, true));
      var child =
          categories.save(
              null,
              new za.co.onlinepos.contracts.DevData.Save(
                  UUID.randomUUID(), null, "Child", 0, true));
      var childValues =
          new java.util.LinkedHashMap<String, Object>(
              Map.of(
                  "id",
                  child.id(),
                  "name",
                  child.name(),
                  "sort_order",
                  0,
                  "active",
                  true,
                  "parent_id",
                  parent.id()));
      catalogue.edit(
          new za.co.onlinepos.contracts.CatalogueManagement.Edit(
              UUID.randomUUID(),
              db.revision(),
              java.util.List.of(
                  new za.co.onlinepos.contracts.CatalogueManagement.Change(
                      "menu_category", java.util.List.of((long) child.id()), childValues, false)),
              false));
      var product =
          Map.<String, Object>of(
              "id",
              0,
              "category_id",
              child.id(),
              "name",
              "Owner item",
              "description",
              "",
              "active",
              true,
              "sort_order",
              0,
              "is_300ml",
              false,
              "is_2l",
              false);
      catalogue.edit(
          new za.co.onlinepos.contracts.CatalogueManagement.Edit(
              UUID.randomUUID(),
              db.revision(),
              java.util.List.of(
                  new za.co.onlinepos.contracts.CatalogueManagement.Change(
                      "menu_item", null, product, false)),
              false));
      int item = db.jdbc.queryForObject("SELECT id FROM public.menu_item", Integer.class);
      catalogue.edit(
          new za.co.onlinepos.contracts.CatalogueManagement.Edit(
              UUID.randomUUID(),
              db.revision(),
              java.util.List.of(
                  new za.co.onlinepos.contracts.CatalogueManagement.Change(
                      "branch_menu_item_price",
                      null,
                      Map.of(
                          "branch_id",
                          db.branchId(),
                          "menu_item_id",
                          item,
                          "price",
                          new java.math.BigDecimal("23.45")),
                      false)),
              false));
      var catalog = catalogue.catalog();
      var priced = catalog.products().values().iterator().next();
      assertTrue(
          za.co.onlinepos.contracts.ItemCustomization.withinCategory(
              catalog, "menu:" + parent.id(), priced.category()));
      assertTrue(priced.available());
      long before = db.revision();
      UUID command = UUID.randomUUID();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              catalogue.edit(
                  new za.co.onlinepos.contracts.CatalogueManagement.Edit(
                      command,
                      before,
                      java.util.List.of(
                          new za.co.onlinepos.contracts.CatalogueManagement.Change(
                              "menu_category",
                              java.util.List.of((long) parent.id()),
                              Map.of(
                                  "id",
                                  parent.id(),
                                  "name",
                                  parent.name(),
                                  "sort_order",
                                  0,
                                  "active",
                                  true,
                                  "parent_id",
                                  child.id()),
                              false)),
                      false)));
      assertEquals(before, db.revision());
      assertEquals(
          0,
          db.jdbc.queryForObject(
              "SELECT count(*) FROM dev_meta.commands WHERE command_id=?", Integer.class, command));
      categories.save(
          parent.id(),
          new za.co.onlinepos.contracts.DevData.Save(
              UUID.randomUUID(), parent.revision(), parent.name(), 0, false));
      var unavailable = catalogue.catalog().products().get(priced.priceId());
      assertFalse(unavailable.available());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              catalogue.quote(
                  new za.co.onlinepos.contracts.ItemCustomization.QuoteRequest(
                      unavailable.priceId(), unavailable.priceRevision(), 1, null)));
    }
  }
}
