package za.co.onlinepos.server.dev;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.LocalCommands;

@Service
@Profile("dev")
public class DevDraftService {
  final DevModuleStore store;
  final DevCatalogueService catalogue;
  final DevCustomerService customers;

  public DevDraftService(
      DevModuleStore store, DevCatalogueService catalogue, DevCustomerService customers) {
    this.store = store;
    this.catalogue = catalogue;
    this.customers = customers;
  }

  public JsonNode create(LocalCommands.CreateDraft command) {
    return write(command, null);
  }

  public JsonNode edit(UUID id, LocalCommands.EditDraft command) {
    if (command.expectedRevision() < 0)
      throw DevModuleStore.fail(400, "Expected revision required");
    return write(
        new LocalCommands.CreateDraft(
            command.commandId(), id, command.lines(), command.customerId()),
        command.expectedRevision());
  }

  private JsonNode payload(LocalCommands.CreateDraft command) {
    var value = store.tree(command);
    for (var line : value.path("lines"))
      if (line.path("notes").asText().isBlank())
        ((com.fasterxml.jackson.databind.node.ObjectNode) line).remove("notes");
    return value;
  }

  private JsonNode write(LocalCommands.CreateDraft command, Long expected) {
    return store.command(
        command.commandId(),
        expected == null ? "DRAFT_CREATE" : "DRAFT_EDIT",
        expected == null ? payload(command) : List.of(payload(command), expected),
        () -> {
          if (command.orderId() == null
              || command.lines() == null
              || command.lines().isEmpty()
              || command.lines().size() > 100)
            throw DevModuleStore.fail(400, "A draft needs 1–100 lines");
          if (expected == null) {
            if (store.jdbc.queryForObject(
                    "SELECT count(*) FROM dev_meta.drafts WHERE order_id=?",
                    Integer.class,
                    command.orderId())
                != 0) throw DevModuleStore.fail(409, "Draft identity already exists");
          } else {
            var old = get(command.orderId());
            if (!"DRAFT".equals(old.path("state").asText())
                || old.path("revision").asLong() != expected)
              throw DevModuleStore.fail(409, "Draft changed or cannot be edited");
          }
          JsonNode customer = store.json.nullNode();
          UUID customerId = null;
          if (command.customerId() != null) {
            customerId = customers.canonical(command.customerId());
            customer = customers.read(customerId);
            if (customer.path("banned").asBoolean() || customer.path("archived").asBoolean())
              throw DevModuleStore.fail(
                  409, "Banned or archived customer cannot be linked to a new draft");
          }
          var catalog = catalogue.catalog();
          var ids = new HashSet<UUID>();
          var quotes = new ArrayList<ItemCustomization.Quote>();
          BigDecimal total = BigDecimal.ZERO.setScale(2);
          for (var line : command.lines()) {
            if (line.lineId() == null || !ids.add(line.lineId()))
              throw DevModuleStore.fail(400, "Unique line identities required");
            var product = catalog.products().get(line.priceId());
            if (product == null) throw DevModuleStore.fail(400, "Unknown product");
            if (line.customization() == null) {
              var configured = ItemCustomization.effective(catalog, product);
              if (configured.combo() != null
                  || configured.groups().stream()
                      .anyMatch(
                          g ->
                              g.min() > 0
                                  || g.choices().stream()
                                      .anyMatch(
                                          c -> c.defaultSelected() && c.surcharge().signum() > 0)))
                throw DevModuleStore.fail(400, "Review required options before saving");
            }
            var quote =
                ItemCustomization.quote(
                    catalog,
                    new ItemCustomization.QuoteRequest(
                        line.priceId(),
                        line.expectedPriceRevision(),
                        line.quantity(),
                        line.customization()));
            quotes.add(quote);
            total = total.add(quote.total());
          }
          String reference = "DEV-" + store.branchId() + "-" + command.orderId();
          var snapshot =
              store.tree(
                  Map.of(
                      "orderId",
                      command.orderId(),
                      "reference",
                      reference,
                      "lines",
                      quotes,
                      "lineIds",
                      command.lines().stream().map(LocalCommands.DraftLine::lineId).toList(),
                      "lineNotes",
                      command.lines().stream().map(LocalCommands.DraftLine::notes).toList(),
                      "customer",
                      customer,
                      "total",
                      total));
          if (expected == null) {
            store.jdbc.update(
                "INSERT INTO dev_meta.drafts(order_id,reference,customer_id,snapshot) VALUES(?,?,?,?)",
                command.orderId(),
                reference,
                customerId,
                store.encode(snapshot));
          } else
            store.jdbc.update(
                "UPDATE dev_meta.drafts SET customer_id=?,snapshot=?,revision=revision+1 WHERE order_id=?",
                customerId,
                store.encode(snapshot),
                command.orderId());
          store.event(
              command.commandId(),
              expected == null ? "DRAFT_CREATED" : "DRAFT_EDITED",
              command.orderId(),
              expected == null ? 0 : expected + 1,
              Map.of("branchId", store.branchId(), "lineCount", quotes.size(), "total", total));
          store.queueRecord(command.commandId(), snapshot);
          return store.tree(
              Map.of(
                  "orderId",
                  command.orderId(),
                  "reference",
                  reference,
                  "revision",
                  expected == null ? 0 : expected + 1,
                  "state",
                  "DRAFT",
                  "total",
                  total));
        });
  }

  public JsonNode list() {
    return store.tree(
        store.jdbc.query(
            "SELECT order_id FROM dev_meta.drafts WHERE status='DRAFT' ORDER BY created_at DESC LIMIT 100",
            (rs, n) -> get(rs.getObject(1, UUID.class))));
  }

  public JsonNode get(UUID id) {
    var records =
        store.jdbc.queryForList(
            "SELECT snapshot,status,revision FROM dev_meta.drafts WHERE order_id=?", id);
    if (records.isEmpty()) throw DevModuleStore.fail(404, "Draft not found");
    var result =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            store.parse(records.getFirst().get("snapshot").toString());
    result
        .put("state", records.getFirst().get("status").toString())
        .put("revision", ((Number) records.getFirst().get("revision")).longValue());
    return result;
  }

  public JsonNode cancel(UUID id, LocalCommands.CancelDraft command) {
    return store.command(
        command.commandId(),
        "DRAFT_CANCEL",
        List.of(id, command),
        () -> {
          int rows =
              store.jdbc.update(
                  "UPDATE dev_meta.drafts SET status='CANCELLED',revision=revision+1 WHERE order_id=? AND revision=? AND status='DRAFT'",
                  id,
                  command.expectedRevision());
          if (rows != 1) throw DevModuleStore.fail(409, "Draft changed or cannot be cancelled");
          store.event(
              command.commandId(),
              "DRAFT_CANCELLED",
              id,
              command.expectedRevision() + 1,
              Map.of("branchId", store.branchId()));
          return get(id);
        });
  }
}
