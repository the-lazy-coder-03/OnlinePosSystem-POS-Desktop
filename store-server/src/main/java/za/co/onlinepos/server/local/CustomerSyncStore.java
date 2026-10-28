package za.co.onlinepos.server.local;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.CustomerProfile;
import za.co.onlinepos.contracts.CustomerSync;

/**
 * Durable port for a future authenticated connector. Intentionally not a bean, controller or
 * scheduler.
 */
public final class CustomerSyncStore implements CustomerSync.Repository {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final CommandJournal journal;
  private final LocalDatabase.Identity identity;
  private final String proof, terminal;

  public CustomerSyncStore(
      JdbcTemplate jdbc,
      TransactionTemplate transactions,
      CommandJournal journal,
      LocalDatabase.Identity identity,
      String resolverToken,
      String terminal) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.journal = journal;
    this.identity = identity;
    this.proof = CommandJournal.digest(resolverToken);
    this.terminal = terminal;
  }

  @Override
  public String checkpoint() {
    var values =
        jdbc.queryForList(
            "SELECT result->>'checkpoint' FROM pos.inbox_receipt WHERE branch_id=? AND result->>'kind'='CUSTOMER' ORDER BY received_at DESC,event_id DESC LIMIT 1",
            String.class,
            identity.branchId());
    return values.isEmpty() ? "" : values.getFirst();
  }

  @Override
  public String receivedDigest(UUID id) {
    var rows =
        jdbc.queryForList(
            "SELECT payload_hash FROM pos.inbox_receipt WHERE branch_id=? AND event_id=?",
            String.class,
            identity.branchId(),
            id);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private CustomerSync.Snapshot snapshot(Map<String, Object> row) {
    var fields = new LinkedHashMap<String, String>();
    var profile = journal.parse(row.get("profile").toString());
    for (String key : CustomerProfile.FIELDS.keySet())
      fields.put(key, profile.path(key).asText(""));
    var times = new HashMap<String, Instant>();
    var stamps = journal.parse(row.get("field_times").toString());
    for (String key :
        List.of(
            "first_name", "last_name", "phone1", "phone2", "email", "address", "preferred_store"))
      times.put(key, Instant.parse(stamps.path(key).asText(Instant.EPOCH.toString())));
    return new CustomerSync.Snapshot(
        (UUID) row.get("global_id"),
        (Long) row.get("cloud_id"),
        ((Number) row.get("preferred_branch")).intValue(),
        fields,
        times,
        (Boolean) row.get("banned"),
        (String) row.get("ban_reason"),
        row.get("ban_changed_at") == null
            ? Instant.EPOCH
            : ((java.sql.Timestamp) row.get("ban_changed_at")).toInstant());
  }

  @Override
  public void apply(CustomerSync.Change change) {
    transactions.executeWithoutResult(
        status -> {
          journal.lockStream();
          String known = receivedDigest(change.eventId());
          if (known != null) {
            if (!known.equals(change.digest()))
              throw new LocalFailure(409, "Remote event identity conflict");
            return;
          }
          var remote = change.snapshot();
          var candidates =
              jdbc.queryForList(
                  "SELECT * FROM pos.local_customer WHERE branch_id=? AND canonical_customer_id IS NULL AND (global_id=? OR customer_id IN (SELECT customer_id FROM pos.customer_alias WHERE branch_id=? AND alias_id=?) OR profile->>'phone1'=? OR normalized_email=?)",
                  identity.branchId(),
                  remote.globalId(),
                  identity.branchId(),
                  remote.globalId(),
                  remote.fields().get("phone1"),
                  remote.fields().get("email"));
          var snapshots = new ArrayList<CustomerSync.Snapshot>();
          var byGlobal = new HashMap<UUID, Map<String, Object>>();
          for (var row : candidates) {
            try {
              var item = snapshot(row);
              snapshots.add(item);
              byGlobal.put(item.globalId(), row);
            } catch (IllegalArgumentException invalidLegacy) {
              conflict(change, candidates, "LEGACY_CONTACT_REVIEW");
              return;
            }
          }
          var match = CustomerSync.match(snapshots, remote);
          var same =
              candidates.stream()
                  .filter(
                      row ->
                          remote.globalId().equals(row.get("global_id"))
                              || jdbc.queryForObject(
                                      "SELECT count(*) FROM pos.customer_alias WHERE branch_id=? AND alias_id=? AND customer_id=?",
                                      Integer.class,
                                      identity.branchId(),
                                      remote.globalId(),
                                      row.get("customer_id"))
                                  > 0)
                  .findFirst();
          if (same.isPresent() && candidates.size() > 1) {
            conflict(change, candidates, "REMOTE_CONTACT_COLLISION");
            return;
          }
          if (same.isPresent() && candidates.size() == 1)
            match = new CustomerSync.Match("LINK", (UUID) same.get().get("global_id"), List.of());
          if (match.status().equals("CONFLICT")) {
            conflict(change, candidates, "REMOTE_CONTACT_COLLISION");
            return;
          }
          UUID localId = remote.globalId();
          CustomerSync.Snapshot merged = remote;
          if (match.target() != null) {
            var row = byGlobal.get(match.target());
            localId = (UUID) row.get("customer_id");
            merged = CustomerSync.merge(snapshot(row), remote);
          }
          // A membership removal updates a known customer; unrelated other-branch customers are not
          // copied.
          if (match.target() == null && remote.preferredBranch() != identity.branchId()) {
            persist(change, localId, null, "OUT_OF_BRANCH");
            return;
          }
          persist(change, localId, merged, "APPLIED");
        });
  }

  private void conflict(
      CustomerSync.Change change, List<Map<String, Object>> candidates, String reason) {
    UUID id =
        UUID.nameUUIDFromBytes(
            (change.eventId() + ":conflict").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var payload =
        Map.of(
            "proposedId",
            change.snapshot().globalId(),
            "proposedRevision",
            candidates.stream()
                .filter(r -> change.snapshot().globalId().equals(r.get("customer_id")))
                .map(r -> r.get("revision"))
                .findFirst()
                .orElse(-1L),
            "fields",
            change.snapshot().fields(),
            "remote",
            change.snapshot(),
            "matches",
            candidates.stream().map(row -> row.get("customer_id")).toList());
    jdbc.queryForObject(
        "SELECT pos.customer_conflict_write(?,?,?,?,?,?::jsonb,?,-1,NULL)",
        Object.class,
        proof,
        terminal,
        id,
        change.eventId(),
        change.digest(),
        journal.encode(payload),
        reason);
    persist(change, change.snapshot().globalId(), null, "CONFLICT");
  }

  private void persist(
      CustomerSync.Change change, UUID id, CustomerSync.Snapshot value, String result) {
    Map<String, Object> data = null;
    if (value != null) {
      data = new LinkedHashMap<>();
      data.put("globalId", value.globalId());
      data.put("cloudId", value.cloudId());
      data.put("preferredBranch", value.preferredBranch());
      data.put("fields", value.fields());
      var times = new HashMap<String, String>();
      value.fieldTimes().forEach((key, time) -> times.put(key, time.toString()));
      data.put("fieldTimes", times);
      data.put("banned", value.banned());
      data.put("banReason", value.banReason());
      data.put("banChangedAt", value.banChangedAt().toString());
    }
    jdbc.queryForObject(
        "SELECT pos.apply_customer_remote(?,?,?,?::jsonb,?,?,?::jsonb)",
        Object.class,
        proof,
        terminal,
        id,
        data == null ? null : journal.encode(data),
        change.eventId(),
        change.digest(),
        journal.encode(
            Map.of(
                "kind",
                "CUSTOMER",
                "state",
                result,
                "eventId",
                change.eventId(),
                "checkpoint",
                change.checkpoint(),
                "source",
                change.source(),
                "serverTime",
                change.serverTime())));
  }

  @Override
  public List<Map<String, Object>> pending(int limit) {
    if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid batch size");
    var rows =
        jdbc.queryForList(
            "SELECT o.event_id AS \"eventId\",o.payload::text AS payload,o.event_type AS \"eventType\",o.aggregate_id AS \"aggregateId\",o.aggregate_revision AS revision,o.stream_position AS \"branchSequence\",o.created_at AS \"recordedAt\" FROM pos.outbox_event o JOIN pos.customer_sync_pending p ON p.event_id=o.event_id WHERE p.branch_id=? AND p.state='PENDING' ORDER BY o.stream_position LIMIT ?",
            identity.branchId(),
            limit);
    rows.forEach(
        row -> {
          String payload = (String) row.get("payload");
          row.put("digest", CommandJournal.digest(payload));
          row.put("payload", journal.parse(payload));
        });
    return rows;
  }

  @Override
  public void acknowledge(CustomerSync.Ack ack) {
    transactions.executeWithoutResult(
        status ->
            jdbc.queryForObject(
                "SELECT pos.ack_customer_sync(?,?,?,?,?,?,?)",
                Object.class,
                proof,
                terminal,
                ack.eventId(),
                ack.contentDigest(),
                ack.reference(),
                ack.globalId(),
                ack.cloudId()));
  }
}
