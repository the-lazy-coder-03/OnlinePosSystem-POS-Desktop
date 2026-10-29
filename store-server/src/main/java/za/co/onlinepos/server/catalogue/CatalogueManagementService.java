package za.co.onlinepos.server.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.contracts.CatalogueSnapshot;
import za.co.onlinepos.contracts.PosModule;
import za.co.onlinepos.server.local.CommandJournal;
import za.co.onlinepos.server.local.LocalDatabase;
import za.co.onlinepos.server.local.LocalFailure;
import za.co.onlinepos.server.local.ModuleAccess;
import za.co.onlinepos.server.local.ProductDraftFailure;
import za.co.onlinepos.server.local.StaffService;

/** Editable branch records are independent of the immutable Stage 4 import. */
@Service
@Profile({"installed", "local-store"})
public class CatalogueManagementService {
  private static final Map<String, String> PRICES =
      Map.of(
          "branch_menu_item_price",
          "price",
          "branch_pizza_price",
          "price",
          "branch_extra_price",
          "price",
          "branch_pizza_base_option_price",
          "price",
          "branch_burger_component_price",
          "price",
          "modifier_option",
          "additional_price",
          "salad_ingredients",
          "price",
          "special",
          "bundle_price",
          "special_addon",
          "price");
  private static final Set<String> ASSIGNMENTS =
      Set.of(
          "pizza_allowed_size",
          "pizza_default_ingredient",
          "menu_item_ingredient",
          "burger_recipe_component",
          "burger_recipe_assignment",
          "burger_item_default_component",
          "burger_item_component_override",
          "menu_item_modifier_group",
          "modifier_option",
          "salad_ingredients",
          "branch_menu_item_price",
          "branch_pizza_price",
          "branch_extra_price",
          "branch_pizza_base_option_price",
          "branch_burger_component_price");
  private static final Map<String, List<String>> CHILDREN =
      Map.of(
          "menu_item",
              List.of(
                  "burger_recipe_assignment",
                  "burger_item_default_component",
                  "burger_item_component_override",
                  "menu_item_modifier_group",
                  "salad_ingredients",
                  "menu_item_ingredient"),
          "pizza", List.of("pizza_allowed_size", "pizza_default_ingredient"),
          "burger_recipe", List.of("burger_recipe_component"),
          "modifier_group", List.of("modifier_option"));
  private final JdbcTemplate jdbc;
  private final CommandJournal journal;
  private final ModuleAccess modules;
  private final LocalDatabase.Identity identity;
  private final TransactionTemplate transactions;
  private final CatalogueImport importer;
  private final za.co.onlinepos.server.local.ItemCustomizationService customization;
  private final SnapshotFormat format = new SnapshotFormat(true);

  public CatalogueManagementService(
      JdbcTemplate jdbc,
      CommandJournal journal,
      ModuleAccess modules,
      LocalDatabase.Identity identity,
      TransactionTemplate transactions,
      CatalogueImport importer,
      za.co.onlinepos.server.local.ItemCustomizationService customization) {
    this.jdbc = jdbc;
    this.journal = journal;
    this.modules = modules;
    this.identity = identity;
    this.transactions = transactions;
    this.importer = importer;
    this.customization = customization;
  }

  private ModuleAccess.Session require(String token, String terminal, boolean edit) {
    var s = modules.authenticate(token, terminal, false);
    if (s.module() != PosModule.CATALOGUE
        || !s.permissions().contains("module.catalogue")
        || edit && !s.permissions().contains("catalogue.manage"))
      throw new LocalFailure(403, "Catalogue permission required");
    return s;
  }

  public JsonNode state(String token, String terminal) {
    return transactions.execute(
        status -> {
          journal.lockStream();
          var session = require(token, terminal, false);
          var tables = tables();
          var schema = (ObjectNode) format.schema();
          schema.remove("branch");
          new ArrayList<>(schema.properties())
              .forEach(
                  e -> {
                    if (e.getKey().startsWith("special")) schema.remove(e.getKey());
                  });
          var visible = new LinkedHashMap<>(tables);
          visible.keySet().removeIf(t -> t.equals("branch") || t.startsWith("special"));
          return ProductPlanner.identities(
              journal.tree(
                  Map.of(
                      "revision",
                      revision(),
                      "branchId",
                      identity.branchId(),
                      "editable",
                      session.permissions().contains("catalogue.manage"),
                      "schema",
                      schema,
                      "tables",
                      visible,
                      "warnings",
                      warnings(tables))),
              (table, key) ->
                  CatalogueImport.stable(identity.branchId(), "price", table, format.encode(key)));
        });
  }

  private long revision() {
    var values =
        jdbc.queryForList(
            "SELECT revision FROM pos.catalogue_version WHERE branch_id=?",
            Long.class,
            identity.branchId());
    if (values.isEmpty()) throw new LocalFailure(409, "Import the store catalogue before editing");
    return values.getFirst();
  }

  private Map<String, List<Map<String, Object>>> tables() {
    revision();
    var result = new LinkedHashMap<String, List<Map<String, Object>>>();
    format.schema().fieldNames().forEachRemaining(t -> result.put(t, new ArrayList<>()));
    jdbc.query(
        "SELECT source_type,data::text FROM pos.catalogue_record WHERE branch_id=? ORDER BY source_type,source_id",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs -> {
              var data = journal.parse(rs.getString(2));
              result.get(rs.getString(1)).add(values(data));
            },
        identity.branchId());
    return result;
  }

  private Map<String, Object> values(JsonNode data) {
    var result = new LinkedHashMap<String, Object>();
    data.properties().forEach(e -> result.put(e.getKey(), e.getValue()));
    return result;
  }

  private boolean editable(String table) {
    return table != null
        && format.schema().has(table)
        && !table.equals("branch")
        && !table.startsWith("special");
  }

  public JsonNode recipeForPrice(
      String token, String terminal, java.util.UUID price, String family) {
    var state = state(token, terminal);
    var rows =
        jdbc.queryForList(
            "SELECT c.source_type,c.definition::text FROM pos.catalog_item c JOIN pos.branch_price p USING(branch_id,item_id) WHERE c.branch_id=? AND p.price_id=?",
            identity.branchId(),
            price);
    if (rows.size() != 1) throw new LocalFailure(404, "Product price not found");
    var row = rows.getFirst();
    String type = (String) row.get("source_type");
    if (!List.of("branch_pizza_price", "branch_menu_item_price").contains(type))
      throw new LocalFailure(400, "This item has no product recipe");
    var definition = journal.parse((String) row.get("definition"));
    return za.co.onlinepos.server.recipes.RecipePlanner.view(
        state,
        new za.co.onlinepos.contracts.Recipes.Product(
            type.equals("branch_pizza_price") ? "pizza" : "menu_item",
            definition
                .path(type.equals("branch_pizza_price") ? "pizza_id" : "menu_item_id")
                .asLong()),
        family);
  }

  public JsonNode product(String token, String terminal, String kind, long id) {
    return transactions.execute(
        status -> {
          journal.lockStream();
          var state = state(token, terminal);
          return ProductPlanner.view(
              state, customization.catalog(state.path("editable").asBoolean()), kind, id);
        });
  }

  public JsonNode saveProduct(
      String token, String terminal, za.co.onlinepos.contracts.ProductEdits.Save save) {
    if (save == null)
      throw new za.co.onlinepos.server.local.ProductDraftFailure("Invalid product draft");
    try {
      return edit(
          token,
          terminal,
          new CatalogueManagement.Edit(
              save.commandId(), save.expectedRevision(), List.of(), false, null, save, false));
    } catch (IllegalArgumentException failure) {
      throw new ProductDraftFailure(failure.getMessage());
    } catch (LocalFailure failure) {
      if (failure.status() != 400) throw failure;
      throw new ProductDraftFailure(failure.getMessage());
    }
  }

  public JsonNode edit(String token, String terminal, CatalogueManagement.Edit edit) {
    var session = require(token, terminal, true);
    if (edit == null
        || edit.changes() == null
        || (edit.changes().isEmpty() && edit.recipe() == null && edit.product() == null)
        || edit.changes().size() > 200
        || edit.expectedRevision() < 0) throw new LocalFailure(400, "Invalid catalogue edit");
    var actor =
        new StaffService.Actor(
            session.staffId(),
            jdbc.queryForObject(
                "SELECT role FROM pos.staff WHERE branch_id=? AND staff_id=?",
                String.class,
                identity.branchId(),
                session.staffId()),
            terminal,
            session.credentialVersion());
    return journal.execute(
        edit.commandId(),
        actor,
        "CATALOGUE_EDIT",
        edit,
        () -> {
          require(token, terminal, true);
          if (revision() != edit.expectedRevision())
            throw new LocalFailure(409, "Catalogue changed; reload");
          var before = tables();
          var after = tables();
          var changed = new HashSet<String>();
          var auditChanges = new java.util.ArrayList<Map<String, Object>>();
          var affected = new java.util.TreeSet<String>();
          var prepared =
              edit.product() == null
                  ? null
                  : ProductPlanner.prepare(
                      state(token, terminal),
                      edit.product(),
                      table ->
                          jdbc.queryForObject(
                              "SELECT nextval('pos.catalogue_local_id')", Long.class));
          var changes = new ArrayList<CatalogueManagement.Change>(edit.changes());
          if (prepared != null) changes.addAll(prepared.changes());
          else if (edit.recipe() != null)
            changes.addAll(
                edit.sharedRecipe()
                    ? za.co.onlinepos.server.recipes.RecipePlanner.sharedChanges(
                        state(token, terminal), edit.recipe())
                    : za.co.onlinepos.server.recipes.RecipePlanner.changes(
                        state(token, terminal), edit.recipe()));
          for (var change : changes) {
            if (change == null || !editable(change.table()) || change.values() == null)
              throw new LocalFailure(400, "Unsupported catalogue record");
            var table = change.table();
            var spec = format.schema().get(table);
            JsonNode original = null;
            if (change.originalKey() != null) {
              if (change.originalKey().size() != spec.get("keys").size())
                throw new LocalFailure(400, "Invalid record key");
              String key = format.encode(change.originalKey());
              original =
                  after.get(table).stream()
                      .map(journal::tree)
                      .filter(r -> format.key(table, r).equals(key))
                      .findFirst()
                      .orElseThrow(() -> new LocalFailure(409, "Record changed; reload"));
              if (!changed.add(table + key)) throw new LocalFailure(400, "Repeated record edit");
              affected.addAll(affectedRecipes(before, table, original));
              final JsonNode old = original;
              after.get(table).removeIf(r -> journal.tree(r).equals(old));
            }
            if (change.remove()) {
              if (original == null || !ASSIGNMENTS.contains(table))
                throw new LocalFailure(400, "Deactivate this record instead");
              auditChanges.add(
                  Map.of("table", table, "oldValues", original, "newValues", Map.of()));
              continue;
            }
            var row = (ObjectNode) journal.tree(change.values());
            if (row.path("archived").asBoolean(false)) row.put("active", false);
            if (row.hasNonNull("branch_id") && row.path("branch_id").asInt() != identity.branchId())
              throw new LocalFailure(403, "Foreign branch record refused");
            if (spec.get("columns").has("branch_id")) row.put("branch_id", identity.branchId());
            if (original == null && generatedKey(table) && prepared == null) {
              var column = spec.get("keys").get(0).asText();
              // Clients never choose a new master identity or recycle a removed identity.
              row.put(
                  column,
                  jdbc.queryForObject("SELECT nextval('pos.catalogue_local_id')", Long.class));
            }
            if (original != null && !format.key(table, row).equals(format.key(table, original)))
              throw new LocalFailure(400, "Record identity cannot change");
            affected.addAll(affectedRecipes(before, table, row));
            after.get(table).add(values(row));
            auditChanges.add(
                Map.of(
                    "table",
                    table,
                    "oldValues",
                    original == null ? Map.of() : original,
                    "newValues",
                    row));
          }
          if (!affected.isEmpty() && !edit.confirmSharedRecipe())
            throw new LocalFailure(
                409, "Confirm shared recipe changes for: " + String.join(", ", affected));
          validate(after);
          validateNewReferences(before, after);
          var records = new ArrayList<Map<String, Object>>();
          after.forEach(
              (table, rows) ->
                  rows.forEach(
                      r ->
                          records.add(
                              Map.of(
                                  "source_type",
                                  table,
                                  "source_id",
                                  format.key(table, journal.tree(r)),
                                  "data",
                                  r))));
          var projection = projection(after);
          long next =
              jdbc.queryForObject(
                  "SELECT pos.apply_catalogue_change(?,?,?,?::jsonb,?::jsonb)",
                  Long.class,
                  CommandJournal.digest(token),
                  terminal,
                  edit.expectedRevision(),
                  journal.encode(records),
                  journal.encode(projection));
          if (edit.recipe() != null || prepared != null && prepared.recipe() != null) {
            var existing =
                jdbc.queryForList(
                    "SELECT rules::text FROM pos.item_customization WHERE branch_id=?",
                    String.class,
                    identity.branchId());
            if (!existing.isEmpty()) {
              var old = journal.parse(existing.getFirst());
              var cleaned =
                  za.co.onlinepos.server.recipes.RecipePlanner.cleanRules(
                      old,
                      journal.tree(Map.of("tables", before)),
                      state(token, terminal),
                      changes,
                      (table, key) ->
                          CatalogueImport.stable(
                              identity.branchId(), "price", table, format.encode(key)));
              if (!old.equals(cleaned))
                next =
                    jdbc.queryForObject(
                        "SELECT pos.apply_item_customization(?,?,?,?::jsonb)",
                        Long.class,
                        CommandJournal.digest(token),
                        terminal,
                        next,
                        journal.encode(cleaned));
            }
          }
          if (prepared != null) {
            var nextRules =
                ProductPlanner.rules(
                    customization.catalog(true).rules(),
                    prepared,
                    (table, key) ->
                        CatalogueImport.stable(
                            identity.branchId(), "price", table, format.encode(key)));
            var nextValue = journal.tree(nextRules);
            if (prepared.recipe() != null)
              nextValue =
                  za.co.onlinepos.server.recipes.RecipePlanner.cleanRules(
                      nextValue,
                      journal.tree(Map.of("tables", before)),
                      state(token, terminal),
                      changes,
                      (table, key) ->
                          CatalogueImport.stable(
                              identity.branchId(), "price", table, format.encode(key)));
            next =
                jdbc.queryForObject(
                    "SELECT pos.apply_item_customization(?,?,?,?::jsonb)",
                    Long.class,
                    CommandJournal.digest(token),
                    terminal,
                    next,
                    journal.encode(nextValue));
            za.co.onlinepos.contracts.ItemCustomization.validate(customization.catalog(true));
          }
          var result =
              journal.tree(
                  Map.of(
                      "revision",
                      next,
                      "affectedItems",
                      affected,
                      "warnings",
                      warnings(after),
                      "product",
                      prepared == null ? Map.of() : prepared.product()));
          var safe =
              Map.of(
                  "revision",
                  next,
                  "tables",
                  changes.stream()
                      .map(CatalogueManagement.Change::table)
                      .distinct()
                      .sorted()
                      .toList(),
                  "recordsChanged",
                  changes.size());
          journal.event(
              actor,
              "CATALOGUE_CHANGED",
              identity.clusterId(),
              next,
              edit.commandId(),
              safe,
              Map.of("changes", auditChanges, "revision", next));
          return result;
        });
  }

  private boolean generatedKey(String table) {
    var spec = format.schema().get(table);
    return spec.get("keys").size() == 1
        && !spec.get("references").has(spec.get("keys").get(0).asText());
  }

  private CatalogueSnapshot snapshot(Map<String, List<Map<String, Object>>> tables) {
    var s =
        new CatalogueSnapshot(
            1,
            identity.clusterId(),
            identity.branchId(),
            SnapshotFormat.SOURCE,
            "2026-10-25T00:00:00Z",
            SnapshotFormat.POLICY,
            tables,
            Map.of(),
            Map.of());
    return new CatalogueSnapshot(
        s.protocolVersion(),
        s.snapshotId(),
        s.branchId(),
        s.sourceRevision(),
        s.capturedAt(),
        s.pricingPolicy(),
        tables,
        format.counts(s),
        format.hashes(s));
  }

  private void validate(Map<String, List<Map<String, Object>>> tables) {
    try {
      format.validate(snapshot(tables));
      for (var entry : tables.entrySet())
        for (var row : entry.getValue()) {
          for (var name : List.of("name", "ingredient_name", "component_type"))
            if (row.containsKey(name)) {
              var text = journal.tree(row).path(name);
              if (!text.isTextual() || text.asText().isBlank() || text.asText().length() > 128)
                throw new IllegalArgumentException("A name is required (maximum 128 characters)");
            }
          if (entry.getKey().equals("pizza_size") && journal.tree(row).path("cm").asInt() < 1)
            throw new IllegalArgumentException("Size must be positive");
          if (entry.getKey().equals("branch_pizza_price")) {
            var r = journal.tree(row);
            if (nodes(tables, "pizza_allowed_size").stream()
                .noneMatch(
                    a ->
                        a.get("pizza_id").equals(r.get("pizza_id"))
                            && a.get("pizza_size_id").equals(r.get("pizza_size_id"))))
              throw new IllegalArgumentException("Price requires an allowed pizza size");
          }
          if (entry.getKey().equals("modifier_group")) {
            var r = journal.tree(row);
            if (r.path("required").asBoolean() && r.path("min_select").asInt() < 1)
              throw new IllegalArgumentException("Required groups need a minimum selection");
          }
        }
    } catch (IllegalArgumentException | ArithmeticException e) {
      throw new LocalFailure(
          400, "Invalid catalogue fields, references, prices or selection limits");
    }
  }

  private List<JsonNode> nodes(Map<String, List<Map<String, Object>>> tables, String table) {
    return tables.get(table).stream().map(journal::tree).toList();
  }

  private void validateNewReferences(
      Map<String, List<Map<String, Object>>> before, Map<String, List<Map<String, Object>>> after) {
    after.forEach(
        (table, rows) -> {
          var old = nodes(before, table);
          for (var row : rows) {
            var r = journal.tree(row);
            var previous =
                old.stream()
                    .filter(o -> format.key(table, o).equals(format.key(table, r)))
                    .findFirst()
                    .orElse(null);
            format
                .schema()
                .get(table)
                .get("references")
                .properties()
                .forEach(
                    ref -> {
                      var value = r.get(ref.getKey());
                      if (value == null
                          || value.isNull()
                          || previous != null && value.equals(previous.get(ref.getKey()))) return;
                      var target = ref.getValue().get(0).asText();
                      var column = ref.getValue().get(1).asText();
                      var match =
                          nodes(after, target).stream()
                              .filter(n -> n.get(column).equals(value))
                              .findFirst()
                              .orElseThrow();
                      if (match.has("active") && !match.get("active").asBoolean())
                        throw new LocalFailure(400, "Inactive records cannot be newly assigned");
                    });
          }
        });
  }

  public List<String> affectedRecipes(
      Map<String, List<Map<String, Object>>> tables, String table, JsonNode row) {
    if (!table.equals("burger_recipe") && !table.equals("burger_recipe_component"))
      return List.of();
    var recipe = row.get("recipe_id");
    return nodes(tables, "burger_recipe_assignment").stream()
        .filter(a -> a.get("recipe_id").equals(recipe))
        .map(
            a ->
                nodes(tables, "menu_item").stream()
                    .filter(i -> i.get("id").equals(a.get("burger_id")))
                    .findFirst()
                    .orElseThrow()
                    .path("name")
                    .asText())
        .sorted()
        .toList();
  }

  private List<String> warnings(Map<String, List<Map<String, Object>>> tables) {
    var result = new java.util.TreeSet<String>();
    for (String table :
        List.of(
            "pizza_default_ingredient",
            "burger_recipe_component",
            "burger_item_default_component")) {
      boolean pizza = table.equals("pizza_default_ingredient");
      String componentTable = pizza ? "ingredient" : "burger_component";
      String componentKey = pizza ? "ingredient_id" : "component_id";
      String parentTable =
          pizza ? "pizza" : table.equals("burger_recipe_component") ? "burger_recipe" : "menu_item";
      String parentKey =
          pizza ? "pizza_id" : parentTable.equals("burger_recipe") ? "recipe_id" : "id";
      String parentColumn = parentTable.equals("menu_item") ? "burger_id" : parentKey;
      for (var row : nodes(tables, table)) {
        var component =
            nodes(tables, componentTable).stream()
                .filter(r -> r.get(componentKey).equals(row.get(componentKey)))
                .findFirst()
                .orElseThrow();
        if (component.path("active").asBoolean()) continue;
        var parent =
            nodes(tables, parentTable).stream()
                .filter(r -> r.get(parentKey).equals(row.get(parentColumn)))
                .findFirst()
                .orElseThrow();
        result.add(
            "Review "
                + parent.path("name").asText()
                + ": "
                + component.path("name").asText()
                + " is inactive."
                + (parentTable.equals("burger_recipe")
                    ? " Assigned items: " + String.join(", ", affectedRecipes(tables, table, row))
                    : ""));
      }
    }
    return List.copyOf(result);
  }

  private List<Map<String, Object>> projection(Map<String, List<Map<String, Object>>> tables) {
    var snapshot = snapshot(tables);
    var result = new ArrayList<Map<String, Object>>();
    PRICES.forEach(
        (table, amount) -> {
          for (var row : nodes(tables, table)) {
            String key = format.key(table, row);
            var definition = (ObjectNode) row.deepCopy();
            definition.set("configuration", configuration(tables, table, row));
            result.add(
                Map.of(
                    "item_id",
                    CatalogueImport.stable(identity.branchId(), "item", table, key),
                    "price_id",
                    CatalogueImport.stable(identity.branchId(), "price", table, key),
                    "source_type",
                    table,
                    "source_id",
                    key,
                    "description",
                    importer.description(snapshot, table, row),
                    "definition",
                    definition,
                    "amount",
                    SnapshotFormat.money(row.get(amount)),
                    "available",
                    importer.available(snapshot, table, row) && activeParents(tables, table, row)));
          }
        });
    return result;
  }

  private boolean activeParents(
      Map<String, List<Map<String, Object>>> tables, String table, JsonNode row) {
    String parentTable;
    String parentColumn;
    String valueColumn;
    if (table.equals("branch_menu_item_price") || table.equals("salad_ingredients")) {
      parentTable = "menu_item";
      parentColumn = "id";
      valueColumn = table.equals("salad_ingredients") ? "salad_id" : "menu_item_id";
    } else if (table.equals("branch_pizza_price")) {
      parentTable = "pizza";
      parentColumn = "pizza_id";
      valueColumn = "pizza_id";
    } else if (table.equals("modifier_option") && row.hasNonNull("menu_item_id")) {
      parentTable = "menu_item";
      parentColumn = "id";
      valueColumn = "menu_item_id";
    } else return true;
    var parent =
        nodes(tables, parentTable).stream()
            .filter(r -> r.get(parentColumn).equals(row.get(valueColumn)))
            .findFirst()
            .orElseThrow();
    String categoryTable = parentTable.equals("pizza") ? "pizza_category" : "menu_category";
    String categoryColumn = parentTable.equals("pizza") ? "pizza_category_id" : "category_id";
    String categoryKey = parentTable.equals("pizza") ? "pizza_category_id" : "id";
    return parent.path("active").asBoolean()
        && nodes(tables, categoryTable).stream()
            .filter(r -> r.get(categoryKey).equals(parent.get(categoryColumn)))
            .findFirst()
            .orElseThrow()
            .path("active")
            .asBoolean();
  }

  private ObjectNode configuration(
      Map<String, List<Map<String, Object>>> tables, String table, JsonNode row) {
    var result = (ObjectNode) journal.tree(Map.of());
    var queue = new java.util.ArrayDeque<Map.Entry<String, JsonNode>>();
    queue.add(Map.entry(table, row));
    var seen = new HashSet<String>();
    while (!queue.isEmpty()) {
      var entry = queue.removeFirst();
      if (!seen.add(entry.getKey() + format.key(entry.getKey(), entry.getValue()))) continue;
      if (!result.has(entry.getKey())) result.putArray(entry.getKey());
      ((com.fasterxml.jackson.databind.node.ArrayNode) result.get(entry.getKey()))
          .add(entry.getValue());
      var spec = format.schema().get(entry.getKey());
      spec.get("references")
          .properties()
          .forEach(
              ref -> {
                var value = entry.getValue().get(ref.getKey());
                if (value != null && !value.isNull()) {
                  String target = ref.getValue().get(0).asText(),
                      column = ref.getValue().get(1).asText();
                  nodes(tables, target).stream()
                      .filter(r -> r.get(column).equals(value))
                      .forEach(r -> queue.add(Map.entry(target, r)));
                }
              });
      for (var child : CHILDREN.getOrDefault(entry.getKey(), List.of())) {
        format
            .schema()
            .get(child)
            .get("references")
            .properties()
            .forEach(
                ref -> {
                  if (ref.getValue().get(0).asText().equals(entry.getKey())) {
                    var value = entry.getValue().get(ref.getValue().get(1).asText());
                    nodes(tables, child).stream()
                        .filter(r -> r.get(ref.getKey()).equals(value))
                        .forEach(r -> queue.add(Map.entry(child, r)));
                  }
                });
      }
    }
    return result;
  }
}
