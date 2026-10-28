package za.co.onlinepos.server.local;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.ItemCustomization.Catalog;
import za.co.onlinepos.contracts.ItemCustomization.Edit;
import za.co.onlinepos.contracts.ItemCustomization.Extra;
import za.co.onlinepos.contracts.ItemCustomization.Quote;
import za.co.onlinepos.contracts.ItemCustomization.QuoteRequest;
import za.co.onlinepos.contracts.ItemCustomization.Rules;
import za.co.onlinepos.contracts.PosModule;

/**
 * Adapts existing recipes, ingredient prices, modifiers and imported specials to one pricing model.
 */
@Service
@Profile({"installed", "local-store"})
public class ItemCustomizationService {
  private final JdbcTemplate jdbc;
  private final CommandJournal journal;
  private final ModuleAccess modules;
  private final LocalDatabase.Identity identity;
  private final TransactionTemplate transactions;

  public ItemCustomizationService(
      JdbcTemplate jdbc,
      CommandJournal journal,
      ModuleAccess modules,
      LocalDatabase.Identity identity,
      TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.journal = journal;
    this.modules = modules;
    this.identity = identity;
    this.transactions = transactions;
  }

  private ModuleAccess.Session require(String token, String terminal, boolean edit) {
    var s = modules.authenticate(token, terminal, false);
    if (edit
        ? s.module() != PosModule.CATALOGUE || !s.permissions().contains("catalogue.manage")
        : s.module() != PosModule.CATALOGUE && s.module() != PosModule.SALES_ORDERS)
      throw new LocalFailure(403, "Catalogue permission required");
    return s;
  }

  private <T> T decode(JsonNode node, Class<T> type) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(node, type);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new LocalFailure(400, "Invalid item configuration");
    }
  }

  public Catalog state(String token, String terminal) {
    return transactions.execute(
        s -> {
          journal.lockStream();
          var actor = require(token, terminal, false);
          return catalog(
              actor.module() == PosModule.CATALOGUE
                  && actor.permissions().contains("catalogue.manage"));
        });
  }

  public Quote quote(String token, String terminal, QuoteRequest request) {
    return transactions.execute(
        s -> {
          journal.lockStream();
          require(token, terminal, false);
          return price(catalog(false), request);
        });
  }

  public Quote price(Catalog catalog, QuoteRequest request) {
    try {
      return ItemCustomization.quote(catalog, request);
    } catch (ItemCustomization.Stale stale) {
      throw new LocalFailure(409, stale.getMessage());
    } catch (IllegalArgumentException e) {
      throw new LocalFailure(400, e.getMessage());
    }
  }

  public JsonNode save(String token, String terminal, Edit edit) {
    var session = require(token, terminal, true);
    if (edit == null || edit.rules() == null || edit.expectedRevision() < 0)
      throw new LocalFailure(400, "Invalid customization edit");
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
        "ITEM_RULES",
        edit,
        () -> {
          require(token, terminal, true);
          var before = catalog(true);
          if (before.revision() != edit.expectedRevision())
            throw new LocalFailure(409, "Catalogue changed; reload and review");
          var next =
              new Catalog(
                  before.revision(),
                  before.products(),
                  before.extras(),
                  before.categories(),
                  edit.rules(),
                  true);
          try {
            ItemCustomization.validate(next);
          } catch (IllegalArgumentException e) {
            throw new LocalFailure(400, e.getMessage());
          }
          long revision =
              jdbc.queryForObject(
                  "SELECT pos.apply_item_customization(?,?,?,?::jsonb)",
                  Long.class,
                  CommandJournal.digest(token),
                  terminal,
                  edit.expectedRevision(),
                  journal.encode(edit.rules()));
          journal.event(
              actor,
              "CATALOGUE_CHANGED",
              identity.clusterId(),
              revision,
              edit.commandId(),
              Map.of(
                  "revision",
                  revision,
                  "tables",
                  List.of("item_customization"),
                  "recordsChanged",
                  1),
              Map.of(
                  "revision",
                  revision,
                  "changes",
                  List.of(
                      Map.of(
                          "table",
                          "item_customization",
                          "oldValues",
                          before.rules(),
                          "newValues",
                          edit.rules()))));
          return journal.tree(Map.of("revision", revision));
        });
  }

  public JsonNode createDraft(
      String token,
      String terminal,
      za.co.onlinepos.contracts.LocalCommands.CreateDraft draft,
      za.co.onlinepos.server.local.LocalOperations operations) {
    return transactions.execute(
        status -> {
          journal.lockStream();
          var s = require(token, terminal, false);
          if (!s.permissions().contains("module.sales_orders"))
            throw new LocalFailure(403, "Sales draft permission required");
          var actor =
              new StaffService.Actor(
                  s.staffId(),
                  jdbc.queryForObject(
                      "SELECT role FROM pos.staff WHERE branch_id=? AND staff_id=?",
                      String.class,
                      identity.branchId(),
                      s.staffId()),
                  terminal,
                  s.credentialVersion());
          return operations.createDraft(actor, draft);
        });
  }

  private static List<JsonNode> rows(Map<String, List<JsonNode>> tables, String table) {
    return tables.getOrDefault(table, List.of());
  }

  private static String key(JsonNode r, String field) {
    return r.path(field).asText();
  }

  private static JsonNode find(
      Map<String, List<JsonNode>> t, String table, String field, String value) {
    return rows(t, table).stream()
        .filter(r -> key(r, field).equals(value))
        .findFirst()
        .orElse(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode());
  }

  private static BigDecimal amount(JsonNode row, String field) {
    return row.hasNonNull(field) ? ItemCustomization.money(row.path(field).decimalValue()) : null;
  }

  private static boolean active(JsonNode row) {
    return !row.isEmpty() && row.path("active").asBoolean(true);
  }

  public Catalog catalog(boolean editable) {
    var versions =
        jdbc.queryForList(
            "SELECT revision FROM pos.catalogue_version WHERE branch_id=?",
            Long.class,
            identity.branchId());
    long revision = versions.isEmpty() ? 0 : versions.getFirst();
    var policies =
        jdbc.queryForList(
            "SELECT rules::text FROM pos.item_customization WHERE branch_id=?",
            String.class,
            identity.branchId());
    var rules =
        policies.isEmpty()
            ? Rules.empty()
            : decode(journal.parse(policies.getFirst()), Rules.class);
    var tables = new HashMap<String, List<JsonNode>>();
    jdbc.query(
        "SELECT source_type,data::text FROM pos.catalogue_record WHERE branch_id=? ORDER BY source_type,source_id",
        (org.springframework.jdbc.core.RowCallbackHandler)
            r ->
                tables
                    .computeIfAbsent(r.getString(1), k -> new ArrayList<>())
                    .add(journal.parse(r.getString(2))),
        identity.branchId());
    var rawPrices =
        jdbc.queryForList(
            "SELECT p.price_id,p.amount,p.revision,p.available,c.source_type,c.description,c.definition::text FROM pos.branch_price p JOIN pos.catalog_item c USING(branch_id,item_id) WHERE p.branch_id=? ORDER BY c.description,p.price_id",
            identity.branchId());
    if (!jdbc.queryForObject(
        "SELECT active AND NOT archived FROM pos.store_identity", Boolean.class))
      rawPrices.forEach(row -> row.put("available", false));
    return za.co.onlinepos.server.pricing.CataloguePricingAdapter.build(
        tables, rawPrices, rules, revision, editable);
  }

  private static void add(
      Map<String, Extra> target, Map<String, Extra> all, String key, int qty, boolean removable) {
    var e = all.get(key);
    if (e != null)
      target.put(
          key,
          new Extra(e.key(), e.name(), e.price(), e.available(), qty, removable, "", "Recipe"));
  }
}
