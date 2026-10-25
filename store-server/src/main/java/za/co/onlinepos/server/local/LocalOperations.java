package za.co.onlinepos.server.local;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import za.co.onlinepos.contracts.LocalCommands;
import za.co.onlinepos.domain.Money;

@Service
@Profile({"installed", "local-store"})
public class LocalOperations {
  private final JdbcTemplate jdbc;
  private final CommandJournal journal;
  private final LocalDatabase.Identity identity;

  public LocalOperations(
      JdbcTemplate jdbc, CommandJournal journal, LocalDatabase.Identity identity) {
    this.jdbc = jdbc;
    this.journal = journal;
    this.identity = identity;
  }

  public void branch(int branch) {
    if (branch != identity.branchId()) throw new LocalFailure(403, "Branch access refused");
  }

  public List<Map<String, Object>> menu() {
    return jdbc.queryForList(
        "SELECT p.price_id,p.item_id,c.source_type,c.source_id,c.description,p.variant_key,p.amount,p.available,p.revision,c.definition_revision FROM pos.branch_price p JOIN pos.catalog_item c ON c.item_id=p.item_id AND c.branch_id=p.branch_id WHERE p.branch_id=? ORDER BY c.description,p.variant_key",
        identity.branchId());
  }

  public JsonNode price(StaffService.Actor actor, UUID priceId, LocalCommands.PriceEdit edit) {
    actor.manager();
    CommandJournal.requireUuid(priceId);
    if (edit.expectedRevision() < 0 || edit.amount() == null)
      throw new LocalFailure(400, "Invalid price edit");
    if (edit.amount().precision() > 19
        || edit.amount().scale() > 128
        || edit.amount().scale() < -17
        || (long) edit.amount().precision() - edit.amount().scale() > 17)
      throw new LocalFailure(400, "Price exceeds supported precision");
    BigDecimal amount = new Money(edit.amount()).amount();
    if (amount.signum() < 0 || amount.precision() > 19)
      throw new LocalFailure(400, "Invalid price");
    return journal.execute(
        edit.commandId(),
        actor,
        "PRICE_CHANGED",
        Map.of("priceId", priceId, "edit", edit),
        () -> {
          if (jdbc.queryForObject(
                  "SELECT count(*) FROM pos.catalogue_version WHERE branch_id=?",
                  Integer.class,
                  identity.branchId())
              != 0)
            throw new LocalFailure(
                403, "Use Catalogue Management to edit imported catalogue prices");
          var prior =
              jdbc.queryForList(
                  "SELECT amount,available,revision FROM pos.branch_price WHERE branch_id=? AND price_id=?",
                  identity.branchId(),
                  priceId);
          if (prior.isEmpty())
            throw new LocalFailure(409, "Price is missing or its revision changed");
          var oldValues = prior.getFirst();
          int changed =
              jdbc.update(
                  "UPDATE pos.branch_price SET amount=?,available=?,revision=revision+1 WHERE branch_id=? AND price_id=? AND revision=?",
                  amount,
                  edit.available(),
                  identity.branchId(),
                  priceId,
                  edit.expectedRevision());
          if (changed != 1) throw new LocalFailure(409, "Price is missing or its revision changed");
          var result =
              Map.of(
                  "priceId",
                  priceId,
                  "amount",
                  amount,
                  "available",
                  edit.available(),
                  "revision",
                  edit.expectedRevision() + 1);
          journal.event(
              actor,
              "PRICE_CHANGED",
              priceId,
              edit.expectedRevision() + 1,
              edit.commandId(),
              result,
              Map.of("oldValues", oldValues, "newValues", result));
          return journal.tree(result);
        });
  }

  public JsonNode createDraft(StaffService.Actor actor, LocalCommands.CreateDraft draft) {
    CommandJournal.requireUuid(draft.orderId());
    if (draft.lines() == null || draft.lines().isEmpty() || draft.lines().size() > 100)
      throw new LocalFailure(400, "Draft requires 1 to 100 lines");
    var lineIds = new HashSet<UUID>();
    for (var line : draft.lines()) {
      if (line == null) throw new LocalFailure(400, "Invalid draft line");
      CommandJournal.requireUuid(line.lineId());
      CommandJournal.requireUuid(line.priceId());
      if (!lineIds.add(line.lineId())
          || line.quantity() < 1
          || line.quantity() > 999
          || line.expectedPriceRevision() < 0) throw new LocalFailure(400, "Invalid draft line");
    }
    return journal.execute(
        draft.commandId(),
        actor,
        "DRAFT_CREATED",
        draft,
        () -> {
          if (jdbc.queryForObject(
                  "SELECT count(*) FROM pos.order_draft WHERE sync_id=?",
                  Integer.class,
                  draft.orderId())
              > 0) throw new LocalFailure(409, "Order identity already exists");
          BigDecimal total = BigDecimal.ZERO.setScale(2);
          var snapshots = new java.util.ArrayList<Map<String, Object>>();
          for (var line : draft.lines()) {
            var rows =
                jdbc.queryForList(
                    "SELECT p.amount,p.revision,c.description,c.definition::text FROM pos.branch_price p JOIN pos.catalog_item c ON c.branch_id=p.branch_id AND c.item_id=p.item_id WHERE p.branch_id=? AND p.price_id=? AND p.revision=? AND p.available",
                    identity.branchId(),
                    line.priceId(),
                    line.expectedPriceRevision());
            if (rows.isEmpty())
              throw new LocalFailure(409, "Price unavailable or changed; refresh the draft");
            var row = rows.getFirst();
            BigDecimal amount = (BigDecimal) row.get("amount");
            total = total.add(amount.multiply(BigDecimal.valueOf(line.quantity())));
            snapshots.add(
                Map.of(
                    "lineId",
                    line.lineId(),
                    "priceId",
                    line.priceId(),
                    "quantity",
                    line.quantity(),
                    "unitPrice",
                    amount,
                    "description",
                    row.get("description"),
                    "priceRevision",
                    line.expectedPriceRevision(),
                    "definition",
                    journal.parse((String) row.get("definition"))));
          }
          if (total.precision() > 19)
            throw new LocalFailure(400, "Draft total exceeds supported amount");
          String reference = "DRAFT-" + identity.branchId() + "-" + draft.orderId();
          jdbc.update(
              "INSERT INTO pos.order_draft(sync_id,branch_id,state,provisional_reference,total) VALUES (?,?,'DRAFT',?,?)",
              draft.orderId(),
              identity.branchId(),
              reference,
              total);
          for (var snapshot : snapshots)
            jdbc.update(
                "INSERT INTO pos.order_line(line_id,branch_id,order_id,price_id,description,quantity,unit_price,price_revision,snapshot) VALUES (?,?,?,?,?,?,?,?,?::jsonb)",
                snapshot.get("lineId"),
                identity.branchId(),
                draft.orderId(),
                snapshot.get("priceId"),
                snapshot.get("description"),
                snapshot.get("quantity"),
                snapshot.get("unitPrice"),
                snapshot.get("priceRevision"),
                journal.encode(snapshot));
          var result =
              Map.of(
                  "orderId",
                  draft.orderId(),
                  "state",
                  "DRAFT",
                  "revision",
                  0,
                  "total",
                  total,
                  "reference",
                  reference,
                  "lines",
                  snapshots);
          journal.event(actor, "DRAFT_CREATED", draft.orderId(), 0, draft.commandId(), result);
          return journal.tree(result);
        });
  }

  public JsonNode draft(UUID id) {
    var rows =
        jdbc.queryForList(
            "SELECT sync_id,state,revision,total,provisional_reference FROM pos.order_draft WHERE branch_id=? AND sync_id=?",
            identity.branchId(),
            id);
    if (rows.isEmpty()) throw new LocalFailure(404, "Draft not found");
    var result = rows.getFirst();
    result.put(
        "lines",
        jdbc.query(
            "SELECT snapshot::text FROM pos.order_line WHERE branch_id=? AND order_id=? ORDER BY line_id",
            (rs, n) -> journal.parse(rs.getString(1)),
            identity.branchId(),
            id));
    return journal.tree(result);
  }

  public JsonNode cancel(StaffService.Actor actor, UUID id, LocalCommands.CancelDraft edit) {
    if (edit.expectedRevision() < 0) throw new LocalFailure(400, "Invalid revision");
    return journal.execute(
        edit.commandId(),
        actor,
        "DRAFT_CANCELLED",
        Map.of("orderId", id, "edit", edit),
        () -> {
          int changed =
              jdbc.update(
                  "UPDATE pos.order_draft SET state='CANCELLED',revision=revision+1 WHERE branch_id=? AND sync_id=? AND revision=? AND state='DRAFT'",
                  identity.branchId(),
                  id,
                  edit.expectedRevision());
          if (changed != 1) throw new LocalFailure(409, "Draft state or revision changed");
          var result =
              Map.of("orderId", id, "state", "CANCELLED", "revision", edit.expectedRevision() + 1);
          journal.event(
              actor, "DRAFT_CANCELLED", id, edit.expectedRevision() + 1, edit.commandId(), result);
          return journal.tree(result);
        });
  }

  public Map<String, Object> printer(StaffService.Actor actor) {
    return Map.of("terminal", actor.terminal(), "printingImplemented", false, "configured", false);
  }
}
