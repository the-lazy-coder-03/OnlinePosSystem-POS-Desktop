package za.co.onlinepos.server.local;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.Auditing;
import za.co.onlinepos.contracts.PosModule;

@Service
@Profile({"installed", "local-store"})
public class AuditPolicyService {
  private final JdbcTemplate jdbc;
  private final ModuleAccess modules;
  private final AuditService audit;
  private final CommandJournal journal;
  private final LocalDatabase.Identity identity;
  private final TransactionTemplate independent;

  public AuditPolicyService(
      JdbcTemplate jdbc,
      ModuleAccess modules,
      AuditService audit,
      CommandJournal journal,
      LocalDatabase.Identity identity,
      TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.modules = modules;
    this.audit = audit;
    this.journal = journal;
    this.identity = identity;
    independent = new TransactionTemplate(transactions.getTransactionManager());
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public ModuleAccess.Session require(String token, String terminal, boolean view) {
    try {
      var session = modules.authenticate(token, terminal, false);
      if (session.module() != PosModule.ADMINISTRATION
          || (view && !session.permissions().contains("audit.view")))
        throw new LocalFailure(403, "Audit permission required");
      return session;
    } catch (LocalFailure failure) {
      rejected(null, terminal, "AUDIT_ACCESS_REFUSED", "SESSION_OR_PERMISSION");
      throw failure;
    }
  }

  private void rejected(UUID actor, String terminal, String action, String reason) {
    independent.executeWithoutResult(
        s ->
            audit.record(
                actor, terminal, action, identity.clusterId(), null, Map.of("reason", reason)));
  }

  public Map<String, Object> settings(String token, String terminal) {
    require(token, terminal, false);
    var result =
        new HashMap<>(
            jdbc.queryForMap(
                "SELECT p.branch_id AS \"branchId\",p.revision,p.updated_at AS \"updatedAt\",p.updated_by_owner_id AS \"approvedBy\",s.username AS \"approvedByName\" FROM pos.audit_policy p LEFT JOIN pos.staff s ON s.staff_id=p.updated_by_owner_id AND s.branch_id=p.branch_id WHERE p.branch_id=?",
                identity.branchId()));
    var values = new TreeMap<String, Boolean>();
    jdbc.query(
        "SELECT event_type,enabled FROM pos.audit_tracking_settings WHERE branch_id=?",
        rs -> {
          values.put(rs.getString(1), rs.getBoolean(2));
        },
        identity.branchId());
    result.put("settings", values);
    result.put("scope", "CURRENT_BRANCH");
    return result;
  }

  public com.fasterxml.jackson.databind.JsonNode save(
      String token, String terminal, Auditing.SettingsEdit edit) {
    var initiator = require(token, terminal, false);
    CommandJournal.requireUuid(edit.commandId());
    var expected = new java.util.TreeSet<String>();
    for (var type : Auditing.EventType.values()) if (type.optional()) expected.add(type.name());
    if (edit.settings() == null
        || !edit.settings().keySet().equals(expected)
        || edit.settings().containsValue(null)
        || edit.expectedRevision() < 0)
      throw new LocalFailure(400, "Complete supported settings required");
    var safe =
        Map.of(
            "settings",
            new TreeMap<>(edit.settings()),
            "expectedRevision",
            edit.expectedRevision());
    String digest = CommandJournal.digest("AUDIT_POLICY:" + journal.encode(journal.tree(safe)));
    // Resolve idempotent retries before requesting fresh owner approval. Never reuse a command for
    // different content/origin.
    var prior =
        jdbc.queryForList(
            "SELECT actor_id,terminal_identity,payload_hash,result::text FROM pos.command_receipt WHERE command_id=?",
            edit.commandId());
    if (!prior.isEmpty()) {
      var row = prior.getFirst();
      if (!initiator.staffId().equals(row.get("actor_id"))
          || !terminal.equals(row.get("terminal_identity"))
          || !digest.equals(row.get("payload_hash")))
        throw new LocalFailure(409, "Command identity conflict");
      return journal.parse((String) row.get("result"));
    }
    String ownerToken = null;
    try {
      var owner =
          modules.enterOwner(
              new ModuleAccess.Entry(edit.ownerPin(), PosModule.ADMINISTRATION), terminal);
      ownerToken = (String) owner.get("token");
      String proof = CommandJournal.digest(ownerToken);
      var actor =
          new StaffService.Actor(
              initiator.staffId(),
              jdbc.queryForObject(
                  "SELECT role FROM pos.staff WHERE staff_id=? AND branch_id=?",
                  String.class,
                  initiator.staffId(),
                  identity.branchId()),
              terminal,
              initiator.credentialVersion());
      return journal.execute(
          edit.commandId(),
          actor,
          "AUDIT_POLICY",
          safe,
          () -> {
            UUID approval = UUID.randomUUID();
            jdbc.queryForObject(
                "SELECT pos.issue_audit_approval(?,?,?,?,?::jsonb,?)",
                Object.class,
                approval,
                CommandJournal.digest(token),
                proof,
                terminal,
                journal.encode(edit.settings()),
                edit.expectedRevision());
            Long revision =
                jdbc.queryForObject(
                    "SELECT pos.apply_audit_policy(?,?,?,?,?::jsonb,?,?)",
                    Long.class,
                    approval,
                    CommandJournal.digest(token),
                    proof,
                    terminal,
                    journal.encode(edit.settings()),
                    edit.expectedRevision(),
                    edit.commandId());
            var result = new HashMap<>(settings(token, terminal));
            result.put("commandId", edit.commandId());
            return journal.tree(result);
          });
    } catch (LocalFailure failure) {
      if (failure.status() != 429)
        rejected(initiator.staffId(), terminal, "AUDIT_ACCESS_REFUSED", "OWNER_APPROVAL_REJECTED");
      throw failure;
    } catch (org.springframework.dao.DataAccessException failure) {
      rejected(
          initiator.staffId(),
          terminal,
          "AUDIT_ACCESS_REFUSED",
          "POLICY_CONFLICT_OR_APPROVAL_INVALID");
      throw failure;
    } finally {
      if (ownerToken != null) modules.discardApprovalSession(ownerToken);
    }
  }
}
