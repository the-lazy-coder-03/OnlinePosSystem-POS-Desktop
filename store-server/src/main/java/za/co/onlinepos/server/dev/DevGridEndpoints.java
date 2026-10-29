package za.co.onlinepos.server.dev;

import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.contracts.Customers;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.LocalCommands;

@RestController
@Profile("dev")
@RequestMapping("/api/local/dev/data-manager/grid")
public class DevGridEndpoints {
  final DevModuleStore store;
  final DevCatalogueService catalogue;
  final DevCustomerService customers;
  final DevDraftService drafts;
  final DevAdministrationService admin;

  public DevGridEndpoints(
      DevModuleStore store,
      DevCatalogueService catalogue,
      DevCustomerService customers,
      DevDraftService drafts,
      DevAdministrationService admin) {
    this.store = store;
    this.catalogue = catalogue;
    this.customers = customers;
    this.drafts = drafts;
    this.admin = admin;
  }

  private <T> T read(java.util.function.Supplier<T> operation) {
    return store.tx.execute(
        status -> {
          store.lock();
          return operation.get();
        });
  }

  @GetMapping("/context")
  public Object context() {
    return Map.of(
        "branchId",
        store.branchId(),
        "username",
        "Development actor",
        "authenticationDeferred",
        true,
        "database",
        "H2",
        "permissions",
        java.util.stream.Stream.concat(
                Customers.PERMISSIONS.stream(),
                java.util.stream.Stream.of(
                    "catalogue.manage", "module.catalogue", "module.sales_orders", "audit.view"))
            .toList());
  }

  @GetMapping("/catalogue/recipes/price/{price}")
  public Object recipeForPrice(
      @PathVariable("price") java.util.UUID price,
      @RequestParam(name = "family", required = false) String family) {
    return read(
        () -> {
          var state = catalogue.state();
          for (String table : java.util.List.of("branch_pizza_price", "branch_menu_item_price"))
            for (var row : state.path("tables").path(table)) {
              var key = new java.util.ArrayList<Long>();
              key.add(row.path("branch_id").asLong());
              key.add(
                  row.path(table.equals("branch_pizza_price") ? "pizza_id" : "menu_item_id")
                      .asLong());
              if (table.equals("branch_pizza_price")) key.add(row.path("pizza_size_id").asLong());
              if (DevModuleStore.identity(table, key.toString()).equals(price))
                return za.co.onlinepos.server.recipes.RecipePlanner.view(
                    state,
                    new za.co.onlinepos.contracts.Recipes.Product(
                        table.equals("branch_pizza_price") ? "pizza" : "menu_item", key.get(1)),
                    family);
            }
          throw new IllegalArgumentException("This item has no product recipe");
        });
  }

  @GetMapping("/catalogue/recipes/{kind}/{id}")
  public Object recipe(
      @PathVariable("kind") String kind,
      @PathVariable("id") long id,
      @RequestParam(name = "family", required = false) String family) {
    return read(
        () ->
            za.co.onlinepos.server.recipes.RecipePlanner.view(
                catalogue.state(),
                new za.co.onlinepos.contracts.Recipes.Product(kind, id),
                family));
  }

  @PutMapping("/catalogue/recipes")
  public Object saveRecipe(@RequestBody za.co.onlinepos.contracts.Recipes.Save save) {
    return catalogue.edit(
        new CatalogueManagement.Edit(
            save.commandId(),
            save.expectedRevision(),
            java.util.List.of(),
            save.confirmSharedRecipe(),
            save));
  }

  @GetMapping("/catalogue/products/{kind}/{id}")
  public Object product(
      @org.springframework.web.bind.annotation.PathVariable("kind") String kind,
      @org.springframework.web.bind.annotation.PathVariable("id") long id) {
    return catalogue.product(kind, id);
  }

  @PutMapping("/catalogue/products")
  public Object productSave(@RequestBody za.co.onlinepos.contracts.ProductEdits.Save save) {
    return catalogue.saveProduct(save);
  }

  @PutMapping("/catalogue/recipes/shared")
  public Object sharedRecipe(@RequestBody za.co.onlinepos.contracts.Recipes.Save save) {
    return catalogue.edit(
        new CatalogueManagement.Edit(
            save.commandId(),
            save.expectedRevision(),
            java.util.List.of(),
            true,
            save,
            null,
            true));
  }

  @GetMapping("/catalogue/state")
  public Object catalogue() {
    return catalogue.state();
  }

  @PutMapping("/catalogue/records")
  public Object catalogueEdit(@RequestBody CatalogueManagement.Edit command) {
    return catalogue.edit(command);
  }

  @GetMapping("/catalogue/items")
  public Object items() {
    return store.tx.execute(
        status -> {
          store.lock();
          return catalogue.catalog();
        });
  }

  @PutMapping("/catalogue/items/rules")
  public Object rules(@RequestBody ItemCustomization.Edit command) {
    return catalogue.rules(command);
  }

  @PostMapping("/catalogue/items/quote")
  public Object quote(@RequestBody ItemCustomization.QuoteRequest command) {
    return catalogue.quote(command);
  }

  @PostMapping("/catalogue/items/drafts")
  public Object draft(@RequestBody LocalCommands.CreateDraft command) {
    return drafts.create(command);
  }

  @GetMapping("/drafts/{id}")
  public Object draft(@PathVariable("id") UUID id) {
    return read(() -> drafts.get(id));
  }

  @PostMapping("/drafts/{id}/cancel")
  public Object cancel(
      @PathVariable("id") UUID id, @RequestBody LocalCommands.CancelDraft command) {
    return drafts.cancel(id, command);
  }

  @GetMapping("/customers/context")
  public Object customerContext() {
    return Map.of(
        "branchId",
        store.branchId(),
        "branchName",
        store.branchName(),
        "permissions",
        Customers.PERMISSIONS,
        "syncEnabled",
        false);
  }

  @GetMapping("/customers")
  public Object customers(
      @RequestParam(name = "query", defaultValue = "") String query,
      @RequestParam(name = "archived", defaultValue = "false") boolean archived,
      @RequestParam(name = "cursor", defaultValue = "") String cursor) {
    return read(() -> customers.search(query, archived, cursor));
  }

  @GetMapping("/customers/{id}")
  public Object customer(@PathVariable("id") UUID id) {
    return read(() -> customers.read(id));
  }

  @PutMapping("/customers/{id}")
  public Object customer(@PathVariable("id") UUID id, @RequestBody Customers.Edit edit) {
    if (!id.equals(edit.customerId())) throw DevModuleStore.fail(400, "Customer identity mismatch");
    return customers.save(edit);
  }

  @PutMapping("/customers/{id}/archive")
  public Object archive(@PathVariable("id") UUID id, @RequestBody Customers.Status edit) {
    return customers.status(id, edit, false);
  }

  @PutMapping("/customers/{id}/ban")
  public Object ban(@PathVariable("id") UUID id, @RequestBody Customers.Status edit) {
    return customers.status(id, edit, true);
  }

  @GetMapping("/customers/{id}/history")
  public Object history(
      @PathVariable("id") UUID id, @RequestParam(name = "page", defaultValue = "0") int page) {
    return read(() -> customers.history(id, page));
  }

  @GetMapping("/customers/lookup")
  public Object lookup(@RequestParam("phone") String phone) {
    return read(() -> customers.lookup(phone));
  }

  @GetMapping("/customers/sync")
  public Object sync() {
    return read(customers::sync);
  }

  @GetMapping("/customers/conflicts")
  public Object conflicts(@RequestParam(name = "page", defaultValue = "0") int page) {
    return read(() -> customers.conflicts(page));
  }

  @PutMapping("/customers/conflicts/{id}")
  public Object resolve(@PathVariable("id") UUID id, @RequestBody Customers.Resolution edit) {
    return customers.resolve(id, edit);
  }

  @GetMapping("/admin/overview")
  public Object overview() {
    return read(admin::overview);
  }

  @GetMapping("/admin/audit/types")
  public Object types() {
    return admin.types();
  }

  @GetMapping("/admin/audit/settings")
  public Object settings() {
    return admin.settings();
  }

  @PutMapping("/admin/audit/settings")
  public Object forbidden() {
    throw DevModuleStore.fail(
        403, "Owner authentication is deferred; settings changes are unavailable");
  }

  @GetMapping("/admin/audit/events")
  public Object events(@RequestParam Map<String, String> filters) {
    return read(() -> admin.history(filters));
  }

  @GetMapping("/admin/audit/events/{id}")
  public Object event(@PathVariable("id") UUID id) {
    return read(() -> admin.detail(id));
  }
}
