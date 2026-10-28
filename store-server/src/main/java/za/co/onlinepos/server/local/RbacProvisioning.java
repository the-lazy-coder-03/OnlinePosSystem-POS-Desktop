package za.co.onlinepos.server.local;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.PosModule;

/** Protected owner-console operations. Secrets never enter audit details or argv. */
public final class RbacProvisioning {
  private RbacProvisioning() {}

  public static Set<String> allowedPermissions() {
    var result = new TreeSet<String>();
    for (var module : PosModule.values()) result.add(module.permission());
    result.addAll(
        Set.of(
            "staff.manage", "rbac.manage", "audit.view", "catalogue.manage", "sales.layouts.edit"));
    result.addAll(za.co.onlinepos.contracts.Customers.PERMISSIONS);
    return result;
  }

  public static void apply(
      DataSource owner,
      LocalDatabase.Identity identity,
      String operation,
      String subject,
      Set<String> values,
      char[] pin,
      String key) {
    var jdbc = new JdbcTemplate(owner);
    if (!"pos_owner".equals(jdbc.queryForObject("SELECT current_user", String.class)))
      throw new SecurityException("Owner console required");
    if (!identity
        .clusterId()
        .equals(
            jdbc.queryForObject(
                "SELECT cluster_id FROM pos.store_identity WHERE branch_id=? AND installation_id=?",
                UUID.class,
                identity.branchId(),
                identity.installationId()))) throw new SecurityException("Wrong installation");
    new TransactionTemplate(new DataSourceTransactionManager(owner))
        .executeWithoutResult(
            status -> {
              jdbc.queryForObject(
                  "SELECT position FROM pos.stream_head WHERE branch_id=? FOR UPDATE",
                  Long.class,
                  identity.branchId());
              UUID aggregate = identity.installationId();
              if (operation.equals("set-role")) {
                if (!subject.matches("[A-Z][A-Z0-9_]{0,63}")
                    || !allowedPermissions().containsAll(values))
                  throw new IllegalArgumentException("Unknown role or permission");
                jdbc.update(
                    "INSERT INTO pos.rbac_role VALUES (?,?) ON CONFLICT DO NOTHING",
                    identity.branchId(),
                    subject);
                jdbc.update(
                    "DELETE FROM pos.role_permission WHERE branch_id=? AND role_name=?",
                    identity.branchId(),
                    subject);
                for (String permission : values)
                  jdbc.update(
                      "INSERT INTO pos.role_permission VALUES (?,?,?)",
                      identity.branchId(),
                      subject,
                      permission);
                jdbc.update(
                    "UPDATE pos.rbac_role SET revision=revision+1 WHERE branch_id=? AND name=?",
                    identity.branchId(),
                    subject);
              } else {
                UUID staff =
                    jdbc.queryForObject(
                        "SELECT staff_id FROM pos.staff WHERE branch_id=? AND username=?",
                        UUID.class,
                        identity.branchId(),
                        subject);
                aggregate = staff;
                if (operation.equals("set-owner")) {
                  jdbc.update(
                      "INSERT INTO pos.store_owner(branch_id,staff_id) VALUES (?,?) ON CONFLICT(branch_id) DO UPDATE SET staff_id=excluded.staff_id",
                      identity.branchId(),
                      staff);
                } else if (operation.equals("set-pin")) {
                  String hash = Passwords.hashPin(pin);
                  jdbc.update(
                      "INSERT INTO pos.staff_pin VALUES (?,?,?,?) ON CONFLICT (branch_id,staff_id) DO UPDATE SET lookup=excluded.lookup,hash=excluded.hash",
                      identity.branchId(),
                      staff,
                      ModuleAccess.lookup(key, identity.branchId(), pin),
                      hash);
                } else if (operation.equals("assign-roles")) {
                  if (values.isEmpty())
                    throw new IllegalArgumentException("At least one role required");
                  jdbc.update(
                      "DELETE FROM pos.staff_role WHERE branch_id=? AND staff_id=?",
                      identity.branchId(),
                      staff);
                  for (String role : values)
                    jdbc.update(
                        "INSERT INTO pos.staff_role VALUES (?,?,?)",
                        identity.branchId(),
                        staff,
                        role);
                } else throw new IllegalArgumentException("Unknown operation");
                jdbc.update(
                    "UPDATE pos.staff SET credential_version=credential_version+1,revision=revision+1 WHERE staff_id=?",
                    staff);
                jdbc.update("DELETE FROM pos.staff_session WHERE staff_id=?", staff);
              }
              new AuditService(jdbc, za.co.onlinepos.installation.JsonFiles.JSON, identity)
                  .record(
                      null,
                      "protected-owner-console",
                      "RBAC_" + operation.toUpperCase(Locale.ROOT).replace('-', '_'),
                      aggregate,
                      null,
                      java.util.Map.of("origin", "protected-owner-console", "subject", subject));
            });
  }
}
