package za.co.onlinepos.server.dev;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.server.pricing.CataloguePricingAdapter;

@Service
@Profile("dev")
public class DevCatalogueService {
  final DevCatalogueRepository repository;
  final DevModuleStore store;

  public DevCatalogueService(DevCatalogueRepository repository) {
    this.repository = repository;
    this.store = repository.store();
  }

  public JsonNode state() {
    return store.tx.execute(
        status -> {
          store.lock();
          return store.tree(
              Map.of(
                  "revision",
                  store.revision(),
                  "branchId",
                  store.branchId(),
                  "editable",
                  true,
                  "schema",
                  repository.schema(),
                  "tables",
                  repository.tables(true),
                  "warnings",
                  List.of("Development H2 — authentication and cloud delivery are not enabled.")));
        });
  }

  public ItemCustomization.Catalog catalog() {
    var tables = repository.tables(true);
    long revision = store.revision();
    var rules =
        store.decode(
            store.parse(
                store.jdbc.queryForObject(
                    "SELECT rules FROM dev_meta.catalogue_state WHERE singleton=1", String.class)),
            ItemCustomization.Rules.class);
    var prices = new ArrayList<Map<String, Object>>();
    for (String table : List.of("branch_menu_item_price", "branch_pizza_price", "special"))
      for (var price : tables.getOrDefault(table, List.of())) {
        String key = repository.key(price, table).toString();
        String name =
            table.equals("special")
                ? price.path("name").asText()
                : table.equals("branch_menu_item_price")
                    ? findName(tables, "menu_item", "id", price.path("menu_item_id").asText())
                    : findName(tables, "pizza", "pizza_id", price.path("pizza_id").asText())
                        + " · "
                        + findName(
                            tables,
                            "pizza_size",
                            "pizza_size_id",
                            price.path("pizza_size_id").asText());
        prices.add(
            Map.of(
                "price_id",
                DevModuleStore.identity(table, key),
                "amount",
                price.path(table.equals("special") ? "bundle_price" : "price").decimalValue(),
                "revision",
                revision,
                "available",
                true,
                "source_type",
                table,
                "description",
                name,
                "definition",
                store.encode(price)));
      }
    return CataloguePricingAdapter.build(tables, prices, rules, revision, true);
  }

  private String findName(Map<String, List<JsonNode>> tables, String table, String key, String id) {
    return tables.getOrDefault(table, List.of()).stream()
        .filter(r -> r.path(key).asText().equals(id))
        .map(r -> r.path("name").asText(r.path("cm").asText() + "cm"))
        .findFirst()
        .orElse("Unknown product");
  }

  public ItemCustomization.Quote quote(ItemCustomization.QuoteRequest request) {
    return store.tx.execute(
        status -> {
          store.lock();
          return ItemCustomization.quote(catalog(), request);
        });
  }

  public JsonNode rules(ItemCustomization.Edit edit) {
    if (edit.rules() == null || edit.expectedRevision() < 0)
      throw DevModuleStore.fail(400, "Valid item rules and revision are required");
    return store.command(
        edit.commandId(),
        "ITEM_RULES",
        edit,
        () -> {
          if (edit.expectedRevision() != store.revision())
            throw DevModuleStore.fail(409, "Catalogue changed; reload");
          var before = catalog();
          ItemCustomization.validate(
              new ItemCustomization.Catalog(
                  before.revision(),
                  before.products(),
                  before.extras(),
                  before.categories(),
                  edit.rules(),
                  true));
          store.jdbc.update(
              "UPDATE dev_meta.catalogue_state SET rules=? WHERE singleton=1",
              store.encode(edit.rules()));
          long revision = store.bump();
          store.event(
              edit.commandId(),
              "CATALOGUE_CHANGED",
              DevModuleStore.identity("branch", Integer.toString(store.branchId())),
              revision,
              Map.of("tables", List.of("item_customization")));
          store.queueRecord(edit.commandId(), edit);
          return store.tree(Map.of("revision", revision));
        });
  }

  public JsonNode edit(CatalogueManagement.Edit edit) {
    return store.command(
        edit.commandId(),
        "CATALOGUE_EDIT",
        edit,
        () -> {
          if (edit.expectedRevision() != store.revision())
            throw DevModuleStore.fail(409, "Catalogue changed; reload");
          if (edit.changes() == null || edit.changes().isEmpty() || edit.changes().size() > 100)
            throw DevModuleStore.fail(400, "Invalid catalogue batch");
          for (var change : edit.changes()) {
            String table = change.table();
            repository.metadata(table);
            if (change.values() == null)
              throw DevModuleStore.fail(400, "Catalogue values required");
            if (change.originalKey() != null
                && repository.keys(table).contains("branch_id")
                && change.originalKey().get(repository.keys(table).indexOf("branch_id"))
                    != (long) store.branchId())
              throw DevModuleStore.fail(403, "Current branch required");
            if (List.of("burger_recipe", "burger_recipe_component").contains(table)
                && !edit.confirmSharedRecipe()) {
              Object recipe = change.values().get("recipe_id");
              if (recipe != null
                  && store.jdbc.queryForObject(
                          "SELECT count(*) FROM public.burger_recipe_assignment WHERE recipe_id=?",
                          Integer.class,
                          recipe)
                      > 1) throw DevModuleStore.fail(409, "Confirm shared recipe changes");
            }

            if (table.equals("branch")) throw DevModuleStore.fail(400, "Branch identity is fixed");
            var data = new LinkedHashMap<>(change.values());
            if (Boolean.TRUE.equals(data.get("archived"))) data.put("active", false);
            var keys = repository.keys(table);
            if (change.originalKey() != null && change.originalKey().size() != keys.size())
              throw DevModuleStore.fail(400, "Invalid record identity");
            if (change.remove()) {
              if (change.originalKey() == null)
                throw DevModuleStore.fail(400, "Record identity required");
              if (repository.columns(table).contains("active")) {
                store.jdbc.update(
                    "UPDATE public." + table + " SET active=FALSE WHERE " + repository.where(table),
                    change.originalKey().toArray());
              } else
                store.jdbc.update(
                    "DELETE FROM public." + table + " WHERE " + repository.where(table),
                    change.originalKey().toArray());
            } else {
              if (change.originalKey() == null && keys.size() == 1) {
                String key = keys.getFirst();
                long next =
                    store.jdbc.queryForObject(
                        "SELECT COALESCE(MAX(" + key + "),0)+1 FROM public." + table, Long.class);
                data.put(key, next);
              }
              if (change.originalKey() != null)
                for (int i = 0; i < keys.size(); i++) {
                  if (!new BigDecimal(data.get(keys.get(i)).toString())
                      .equals(new BigDecimal(change.originalKey().get(i))))
                    throw DevModuleStore.fail(400, "Record keys cannot change");
                }
              if (data.containsKey("branch_id")
                  && Integer.parseInt(data.get("branch_id").toString()) != store.branchId())
                throw DevModuleStore.fail(400, "Current branch required");
              if (repository.columns(table).contains("archived") && !data.containsKey("archived")) {
                data.put(
                    "archived",
                    change.originalKey() == null
                        ? false
                        : store.jdbc.queryForObject(
                            "SELECT archived FROM public."
                                + table
                                + " WHERE "
                                + repository.where(table),
                            Boolean.class,
                            change.originalKey().toArray()));
              }
              if (repository.columns(table).contains("parent_id") && !data.containsKey("parent_id"))
                data.put(
                    "parent_id",
                    change.originalKey() == null
                        ? null
                        : store.jdbc.queryForObject(
                            "SELECT parent_id FROM public."
                                + table
                                + " WHERE "
                                + repository.where(table),
                            Integer.class,
                            change.originalKey().toArray()));
              if (Boolean.TRUE.equals(data.get("archived"))) data.put("active", false);
              repository.validate(table, data);
              JsonNode row = store.tree(data);
              if (table.equals("menu_category")) {
                String name = data.get("name").toString().strip();
                long duplicates =
                    store.jdbc.queryForObject(
                        "SELECT count(*) FROM public.menu_category WHERE LOWER(TRIM(name))=LOWER(?) AND id<>?",
                        Long.class,
                        name,
                        data.get("id"));
                if (duplicates > 0) throw DevModuleStore.fail(409, "Category name already exists");
                data.put("name", name);
                row = store.tree(data);
              }
              if (change.originalKey() == null) repository.insert(table, row);
              else repository.update(table, row, change.originalKey());
            }
            if (table.equals("menu_category")) {
              store.jdbc.update(
                  "UPDATE public.menu_category SET revision=revision+1 WHERE id=?",
                  change.originalKey() == null ? data.get("id") : change.originalKey().getFirst());
              store.jdbc.update(
                  "UPDATE dev_meta.category_ids SET next_id=(SELECT COALESCE(MAX(id),0)+1 FROM public.menu_category) WHERE singleton=1");
            }
          }
          repository.validateAll();
          long revision = store.bump();
          store.event(
              edit.commandId(),
              "CATALOGUE_CHANGED",
              DevModuleStore.identity("branch", Integer.toString(store.branchId())),
              revision,
              Map.of(
                  "tables",
                  edit.changes().stream()
                      .map(CatalogueManagement.Change::table)
                      .distinct()
                      .toList(),
                  "recordsChanged",
                  edit.changes().size()));
          store.queueRecord(edit.commandId(), edit);
          return store.tree(Map.of("revision", revision));
        });
  }
}
