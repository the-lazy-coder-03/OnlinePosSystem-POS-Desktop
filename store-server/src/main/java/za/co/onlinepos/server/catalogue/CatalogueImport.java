package za.co.onlinepos.server.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import za.co.onlinepos.contracts.CatalogueSnapshot;
import za.co.onlinepos.server.local.CommandJournal;
import za.co.onlinepos.server.local.LocalDatabase;
import za.co.onlinepos.server.local.LocalFailure;
import za.co.onlinepos.server.local.StaffService;

@Service
@Profile({"installed", "local-store"})
public class CatalogueImport {
  private final JdbcTemplate jdbc;
  private final CommandJournal journal;
  private final LocalDatabase.Identity identity;
  private final SnapshotFormat format = new SnapshotFormat();

  public CatalogueImport(
      JdbcTemplate jdbc, CommandJournal journal, LocalDatabase.Identity identity) {
    this.jdbc = jdbc;
    this.journal = journal;
    this.identity = identity;
  }

  public JsonNode apply(StaffService.Actor actor, int branch, CatalogueSnapshot.Import command) {
    actor.manager();
    if (command == null) throw new LocalFailure(400, "Missing import command");
    if (branch != identity.branchId()
        || command.snapshot() == null
        || command.snapshot().branchId() != branch)
      throw new LocalFailure(403, "Snapshot branch refused");
    format.validate(command.snapshot());
    var snapshot = command.snapshot();
    String hash = format.fingerprint(snapshot);
    if (!hash.equals(command.expectedSha256()))
      throw new LocalFailure(400, "Approved snapshot hash mismatch");
    return journal.execute(
        command.commandId(),
        actor,
        "CATALOGUE_BOOTSTRAPPED",
        command,
        () -> {
          var existing =
              jdbc.queryForList(
                  "SELECT sha256 FROM pos.catalogue_bootstrap WHERE branch_id=?", branch);
          if (!existing.isEmpty()) {
            if (!hash.equals(existing.getFirst().get("sha256")))
              throw new LocalFailure(409, "Branch already bootstrapped");
            return result(snapshot, hash);
          }
          var items = new ArrayList<Map<String, Object>>();
          var prices = new ArrayList<Map<String, Object>>();
          for (var spec :
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
                      "price")
                  .entrySet()) {
            for (var row : format.rows(snapshot, spec.getKey())) {
              String key = format.key(spec.getKey(), row);
              UUID item = stable(branch, "item", spec.getKey(), key);
              UUID price = stable(branch, "price", spec.getKey(), key);
              items.add(
                  Map.of(
                      "item_id",
                      item,
                      "source_type",
                      spec.getKey(),
                      "source_id",
                      key,
                      "description",
                      description(snapshot, spec.getKey(), row),
                      "definition",
                      row));
              prices.add(
                  Map.of(
                      "price_id",
                      price,
                      "item_id",
                      item,
                      "amount",
                      SnapshotFormat.money(row.get(spec.getValue())),
                      "available",
                      available(snapshot, spec.getKey(), row)));
            }
          }
          if (prices.isEmpty())
            throw new LocalFailure(400, "Empty price catalogue cannot complete bootstrap");
          jdbc.queryForList(
              "SELECT pos.bootstrap_catalogue(?,?,?,?::jsonb,?::jsonb,?::jsonb)",
              branch,
              snapshot.snapshotId(),
              hash,
              format.encode(snapshot),
              format.encode(items),
              format.encode(prices));
          // Compare the persisted source and every effective decimal baseline before cutover
          // commits.
          String saved =
              jdbc.queryForObject(
                  "SELECT snapshot::text FROM pos.catalogue_bootstrap WHERE branch_id=?",
                  String.class,
                  branch);
          if (!format.tree(snapshot).equals(journal.parse(saved)))
            throw new IllegalStateException("Snapshot readback mismatch");
          for (var price : prices) {
            var savedPrice =
                jdbc.queryForMap(
                    "SELECT amount,available FROM pos.branch_price WHERE price_id=?",
                    price.get("price_id"));
            if (((java.math.BigDecimal) price.get("amount"))
                        .compareTo((java.math.BigDecimal) savedPrice.get("amount"))
                    != 0
                || !price.get("available").equals(savedPrice.get("available")))
              throw new IllegalStateException("Price readback mismatch");
          }
          JsonNode result = result(snapshot, hash);
          journal.event(
              actor,
              "CATALOGUE_BOOTSTRAPPED",
              snapshot.snapshotId(),
              0,
              command.commandId(),
              result);
          return result;
        });
  }

  private JsonNode result(CatalogueSnapshot snapshot, String hash) {
    return journal.tree(
        Map.of(
            "snapshotId",
            snapshot.snapshotId(),
            "branchId",
            snapshot.branchId(),
            "sha256",
            hash,
            "counts",
            snapshot.counts(),
            "priceAuthority",
            "LOCAL",
            "acceptingSales",
            false));
  }

  public JsonNode catalogue(int branch) {
    if (branch != identity.branchId()) throw new LocalFailure(403, "Branch access refused");
    var saved =
        jdbc.queryForList(
            "SELECT snapshot::text,sha256,cutover_at FROM pos.catalogue_bootstrap WHERE branch_id=?",
            branch);
    if (saved.isEmpty()) throw new LocalFailure(404, "Catalogue not bootstrapped");
    var row = saved.getFirst();
    return journal.tree(
        Map.of(
            "snapshot",
            journal.parse((String) row.get("snapshot")),
            "sha256",
            row.get("sha256"),
            "cutoverAt",
            row.get("cutover_at").toString(),
            "priceAuthority",
            "LOCAL",
            "effectivePrices",
            jdbc.queryForList(
                "SELECT p.price_id,c.source_type,c.source_id,p.amount,p.available,p.revision FROM pos.branch_price p JOIN pos.catalog_item c USING(branch_id,item_id) WHERE p.branch_id=? ORDER BY c.source_type,c.source_id",
                branch)));
  }

  public static UUID stable(int branch, String kind, String table, String key) {
    return UUID.nameUUIDFromBytes(
        ("onlinepos:catalogue:v1:" + branch + ":" + kind + ":" + table + ":" + key)
            .getBytes(StandardCharsets.UTF_8));
  }

  private JsonNode find(CatalogueSnapshot s, String table, String column, JsonNode value) {
    return format.rows(s, table).stream()
        .filter(r -> r.get(column).equals(value))
        .findFirst()
        .orElseThrow();
  }

  private String description(CatalogueSnapshot s, String table, JsonNode row) {
    return switch (table) {
      case "branch_menu_item_price" ->
          find(s, "menu_item", "id", row.get("menu_item_id")).get("name").asText();
      case "branch_pizza_price" ->
          find(s, "pizza", "pizza_id", row.get("pizza_id")).get("name").asText()
              + " / "
              + find(s, "pizza_size", "pizza_size_id", row.get("pizza_size_id")).get("cm").asText()
              + "cm";
      case "salad_ingredients" -> row.get("ingredient_name").asText();
      case "special_addon" -> row.get("label").asText();
      default -> row.has("name") ? row.get("name").asText() : table + " " + format.key(table, row);
    };
  }

  private boolean active(JsonNode row) {
    return !row.has("active") || row.get("active").asBoolean();
  }

  private boolean available(CatalogueSnapshot s, String table, JsonNode row) {
    return switch (table) {
      case "branch_menu_item_price" -> active(find(s, "menu_item", "id", row.get("menu_item_id")));
      case "branch_pizza_price" ->
          active(find(s, "pizza", "pizza_id", row.get("pizza_id")))
              && active(find(s, "pizza_size", "pizza_size_id", row.get("pizza_size_id")))
              && format.rows(s, "pizza_allowed_size").stream()
                  .anyMatch(
                      r ->
                          r.get("pizza_id").equals(row.get("pizza_id"))
                              && r.get("pizza_size_id").equals(row.get("pizza_size_id")));
      case "branch_extra_price" ->
          active(find(s, "price_category", "price_category_id", row.get("price_category_id")))
              && active(find(s, "pizza_size", "pizza_size_id", row.get("pizza_size_id")));
      case "branch_pizza_base_option_price" ->
          active(
                  find(
                      s,
                      "pizza_base_option",
                      "pizza_base_option_id",
                      row.get("pizza_base_option_id")))
              && active(find(s, "pizza_size", "pizza_size_id", row.get("pizza_size_id")));
      case "branch_burger_component_price" ->
          active(find(s, "burger_component", "component_id", row.get("component_id")));
      // Specials require date/selection resolution in Stage 5; never expose them as ordinary draft
      // lines.
      case "special", "special_addon" -> false;
      default -> active(row);
    };
  }
}
