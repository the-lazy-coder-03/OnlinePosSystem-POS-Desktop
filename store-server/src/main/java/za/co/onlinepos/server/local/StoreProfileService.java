package za.co.onlinepos.server.local;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.PosModule;

/** Owner-controlled local profile. Integration and remote stores are deliberately separate. */
@Service
@Profile({"installed", "local-store"})
public class StoreProfileService {
  private final JdbcTemplate jdbc;
  private final ModuleAccess modules;
  private final CommandJournal journal;
  private final TransactionTemplate transactions;
  private final LocalDatabase.Identity identity;

  public StoreProfileService(
      JdbcTemplate jdbc,
      ModuleAccess modules,
      CommandJournal journal,
      TransactionTemplate transactions,
      LocalDatabase.Identity identity) {
    this.jdbc = jdbc;
    this.modules = modules;
    this.journal = journal;
    this.transactions = transactions;
    this.identity = identity;
  }

  private ModuleAccess.Session require(String token, String terminal) {
    var session = modules.authenticate(token, terminal, false);
    if (session.module() != PosModule.ADMINISTRATION
        || jdbc.queryForObject(
                "SELECT count(*) FROM pos.store_owner WHERE branch_id=? AND staff_id=?",
                Integer.class,
                identity.branchId(),
                session.staffId())
            != 1) throw new LocalFailure(403, "Designated local Owner required");
    return session;
  }

  public JsonNode state(String token, String terminal) {
    return transactions.execute(
        status -> {
          journal.lockStream();
          require(token, terminal);
          return profile();
        });
  }

  private JsonNode profile() {
    var row =
        jdbc.queryForMap(
            "SELECT branch_id,store_uuid,store_name AS name,store_code AS code,address,phone,email,active,archived,revision,business_id,business_name FROM pos.store_identity");
    var result =
        journal.tree(
            Map.of(
                "configured",
                row.get("business_name") != null && row.get("name") != null,
                "store",
                row,
                "business",
                java.util.List.of(
                    Map.of(
                        "business_id",
                        row.get("business_id"),
                        "name",
                        row.get("business_name") == null ? "" : row.get("business_name"))),
                "integrationEnabled",
                false));
    return result;
  }

  public JsonNode save(String token, String terminal, JsonNode input) {
    var session = require(token, terminal);
    UUID command;
    try {
      command = UUID.fromString(input.path("commandId").asText());
    } catch (IllegalArgumentException invalid) {
      throw new LocalFailure(400, "Command UUID required");
    }
    var fields =
        journal.tree(
            Map.of(
                "businessName",
                input.path("businessName").asText(),
                "name",
                input.path("name").asText(input.path("storeName").asText()),
                "code",
                input.path("code").asText(),
                "address",
                input.path("address").asText(),
                "phone",
                input.path("phone").asText(),
                "email",
                input.path("email").asText(),
                "active",
                input.path("active").asBoolean(true)));
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
        command,
        actor,
        "LOCAL_STORE_CHANGED",
        input,
        () -> {
          require(token, terminal);
          long revision =
              jdbc.queryForObject(
                  "SELECT pos.manage_local_store(?,?,?,?::jsonb)",
                  Long.class,
                  CommandJournal.digest(token),
                  terminal,
                  input.path("expectedRevision").asLong(0),
                  fields.toString());
          UUID store = jdbc.queryForObject("SELECT store_uuid FROM pos.store_identity", UUID.class);
          journal.event(
              actor,
              "LOCAL_STORE_CHANGED",
              store,
              revision,
              command,
              Map.of("branchId", identity.branchId(), "revision", revision));
          return profile();
        });
  }
}
