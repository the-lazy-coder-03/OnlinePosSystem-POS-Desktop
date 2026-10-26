package za.co.onlinepos.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.PosModule;
import za.co.onlinepos.server.DatabaseTool;
import za.co.onlinepos.server.local.CommandJournal;
import za.co.onlinepos.server.local.DeveloperRecovery;
import za.co.onlinepos.server.local.LocalDatabase;
import za.co.onlinepos.server.local.LocalFailure;
import za.co.onlinepos.server.local.ModuleAccess;
import za.co.onlinepos.server.local.RbacProvisioning;

@EnabledIfEnvironmentVariable(named = "POS_TEST_DATABASE_URL", matches = ".+/pos_stage3_test")
class DeveloperRecoveryIT {
  private final LocalDatabase.Identity identity =
      new LocalDatabase.Identity(1, UUID.randomUUID(), UUID.randomUUID());
  private final String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);
  private DataSource ownerSource;
  private JdbcTemplate owner, runtime;
  private DeveloperRecovery recovery;
  private ModuleAccess modules;
  private UUID ownerId;

  @BeforeEach
  void setup() {
    String url =
        System.getenv("POS_TEST_DATABASE_URL").replace("/pos_stage3_test", "/pos_stage3_restore");
    ownerSource = DatabaseTool.source(url, "pos_owner", System.getenv("POS_TEST_OWNER_PASSWORD"));
    owner = new JdbcTemplate(ownerSource);
    owner.execute("DROP SCHEMA IF EXISTS pos CASCADE");
    owner.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
    Flyway.configure().dataSource(ownerSource).locations("classpath:db/migration").load().migrate();
    owner.update(
        "INSERT INTO pos.store_identity(branch_id,cluster_id,installation_id) VALUES(?,?,?)",
        1,
        identity.clusterId(),
        identity.installationId());
    owner.update("INSERT INTO pos.stream_head(branch_id) VALUES(1)");
    DatabaseTool.bootstrap(
        ownerSource, identity, "owner", UUID.randomUUID().toString().toCharArray());
    RbacProvisioning.apply(ownerSource, identity, "set-owner", "owner", Set.of(), new char[0], key);
    RbacProvisioning.apply(
        ownerSource, identity, "set-pin", "owner", Set.of(), "654321".toCharArray(), key);
    ownerId =
        owner.queryForObject("SELECT staff_id FROM pos.staff WHERE username='owner'", UUID.class);
    var source =
        DatabaseTool.source(url, "pos_runtime", System.getenv("POS_TEST_RUNTIME_PASSWORD"));
    runtime = new JdbcTemplate(source);
    modules =
        new ModuleAccess(
            runtime,
            new TransactionTemplate(new DataSourceTransactionManager(source)),
            identity,
            new MockEnvironment().withProperty("pos.pin-key", key));
    recovery = new DeveloperRecovery(ownerSource, identity);
  }

  private String login(String pin, String terminal) {
    return (String)
        modules.enter(new ModuleAccess.Entry(pin, PosModule.ADMINISTRATION), terminal).get("token");
  }

  @Test
  void enrolResetIsNarrowAuditedAndRevokesSessionsOnBothTerminals() {
    assertThrows(
        LocalFailure.class,
        () -> recovery.recover("12345".toCharArray(), "222222".toCharArray(), key));
    String master = login("654321", "master"), slave = login("654321", "slave");
    recovery.enrol("12345".toCharArray(), false);
    assertThrows(LocalFailure.class, () -> login("12345", "master"));
    recovery.recover("12345".toCharArray(), "222222".toCharArray(), key);
    assertThrows(LocalFailure.class, () -> modules.authenticate(master, "master", false));
    assertThrows(LocalFailure.class, () -> modules.authenticate(slave, "slave", false));
    assertThrows(LocalFailure.class, () -> login("654321", "master"));
    assertNotNull(login("222222", "master"));
    assertNotNull(login("222222", "slave"));
    assertEquals(1, owner.queryForObject("SELECT count(*) FROM pos.staff", Integer.class));
    assertEquals(ownerId, owner.queryForObject("SELECT staff_id FROM pos.store_owner", UUID.class));
    assertEquals(
        1,
        owner.queryForObject(
            "SELECT count(*) FROM pos.audit_record WHERE action='OWNER_PIN_RECOVERED'",
            Integer.class));
    String audit = owner.queryForList("SELECT details FROM pos.audit_record").toString();
    assertFalse(audit.contains("12345"));
    assertFalse(audit.contains("222222"));
  }

  @Test
  void failuresPersistAcrossInstancesAndEnforceCooldown() {
    recovery.enrol("12345".toCharArray(), false);
    for (int i = 0; i < 5; i++)
      assertThrows(
          LocalFailure.class,
          () ->
              new DeveloperRecovery(ownerSource, identity)
                  .recover("99999".toCharArray(), "222222".toCharArray(), key));
    assertEquals(
        5,
        owner.queryForObject(
            "SELECT failures FROM pos.pin_throttle WHERE scope='developer-recovery'",
            Integer.class));
    assertThrows(
        LocalFailure.class,
        () -> recovery.recover("12345".toCharArray(), "222222".toCharArray(), key));
    assertEquals(
        6,
        owner.queryForObject(
            "SELECT count(*) FROM pos.audit_record WHERE action='DEVELOPER_RECOVERY_FAILED'",
            Integer.class));
    owner.update(
        "UPDATE pos.pin_throttle SET locked_until=clock_timestamp()-interval '1 second' WHERE scope='developer-recovery'");
    recovery.recover("12345".toCharArray(), "222222".toCharArray(), key);
  }

  @Test
  void ownerCanSeeAndDisableButRuntimeCannotReadVerifierOrEnrol() {
    recovery.enrol("12345".toCharArray(), false);
    String token = login("654321", "slave");
    String proof = CommandJournal.digest(token);
    String status =
        runtime.queryForObject(
            "SELECT pos.developer_recovery_status(?,?)::text", String.class, proof, "slave");
    assertTrue(status.contains("true"));
    assertFalse(status.contains("verifier"));
    assertThrows(
        Exception.class, () -> runtime.queryForList("SELECT * FROM pos.developer_recovery"));
    runtime.queryForObject(
        "SELECT pos.disable_developer_recovery(?,?)", Object.class, proof, "slave");
    assertFalse(owner.queryForObject("SELECT enabled FROM pos.developer_recovery", Boolean.class));
    assertThrows(
        LocalFailure.class,
        () -> recovery.recover("12345".toCharArray(), "222222".toCharArray(), key));
    assertThrows(
        Exception.class,
        () ->
            runtime.queryForObject(
                "SELECT pos.disable_developer_recovery(?,?)",
                Object.class,
                proof,
                "other-terminal"));
    var source =
        DatabaseTool.source(
            System.getenv("POS_TEST_DATABASE_URL")
                .replace("/pos_stage3_test", "/pos_stage3_restore"),
            "pos_runtime",
            System.getenv("POS_TEST_RUNTIME_PASSWORD"));
    assertThrows(
        SecurityException.class,
        () -> new DeveloperRecovery(source, identity).enrol("12345".toCharArray(), true));
  }

  @Test
  void bindingRotationAndExistingPinsArePreserved() {
    recovery.enrol("12345".toCharArray(), false);
    String first =
        owner.queryForObject("SELECT verifier FROM pos.developer_recovery", String.class);
    assertFalse(first.contains("12345"));
    assertThrows(IllegalStateException.class, () -> recovery.enrol("12345".toCharArray(), false));
    var wrong = new LocalDatabase.Identity(1, UUID.randomUUID(), identity.installationId());
    assertThrows(
        SecurityException.class,
        () ->
            new DeveloperRecovery(ownerSource, wrong)
                .recover("12345".toCharArray(), "222222".toCharArray(), key));
    owner.update("UPDATE pos.developer_recovery SET installation_id=?", UUID.randomUUID());
    assertThrows(
        LocalFailure.class,
        () -> recovery.recover("12345".toCharArray(), "222222".toCharArray(), key));
    recovery.enrol("54321".toCharArray(), true);
    assertNotEquals(
        first, owner.queryForObject("SELECT verifier FROM pos.developer_recovery", String.class));
    assertThrows(
        LocalFailure.class,
        () -> recovery.recover("12345".toCharArray(), "222222".toCharArray(), key));
    assertNotNull(login("654321", "master"));
    recovery.recover("54321".toCharArray(), "222222".toCharArray(), key);
  }

  @Test
  void nonOwnerCannotInspectOrDisableAndNoNetworkResetRouteExists() {
    UUID manager = UUID.randomUUID();
    owner.update(
        "INSERT INTO pos.staff(staff_id,branch_id,username,password_hash,role) VALUES (?,1,'manager',?,'MANAGER')",
        manager,
        za.co.onlinepos.server.local.Passwords.hash(UUID.randomUUID().toString().toCharArray()));
    RbacProvisioning.apply(
        ownerSource, identity, "set-pin", "manager", Set.of(), "333333".toCharArray(), key);
    String token = login("333333", "slave");
    assertThrows(
        Exception.class,
        () ->
            runtime.queryForObject(
                "SELECT pos.developer_recovery_status(?,?)",
                Object.class,
                CommandJournal.digest(token),
                "slave"));
    assertThrows(
        Exception.class,
        () ->
            runtime.queryForObject(
                "SELECT pos.disable_developer_recovery(?,?)",
                Object.class,
                CommandJournal.digest(token),
                "slave"));
    var transactions =
        new TransactionTemplate(new DataSourceTransactionManager(runtime.getDataSource()));
    var journal =
        new CommandJournal(
            runtime, transactions, new com.fasterxml.jackson.databind.ObjectMapper(), identity);
    var service =
        new za.co.onlinepos.server.local.AdministrationService(
            runtime,
            journal,
            modules,
            identity,
            new MockEnvironment().withProperty("pos.pin-key", key));
    assertEquals(
        403,
        assertThrows(LocalFailure.class, () -> service.recoveryStatus(token, "slave")).status());
    assertEquals(
        403,
        assertThrows(LocalFailure.class, () -> service.disableRecovery(token, "slave")).status());
    assertFalse(
        java.util.Arrays.stream(
                za.co.onlinepos.server.local.AdministrationEndpoints.class.getDeclaredMethods())
            .anyMatch(m -> m.getName().toLowerCase().contains("reset")));
  }

  @Test
  void auditFailureRollsBackPinAndSessionChanges() {
    recovery.enrol("12345".toCharArray(), false);
    String token = login("654321", "master");
    owner.execute(
        "CREATE FUNCTION pos.reject_recovery_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='OWNER_PIN_RECOVERED' THEN RAISE EXCEPTION 'audit unavailable'; END IF; RETURN NEW; END $$");
    owner.execute(
        "CREATE TRIGGER reject_recovery_test BEFORE INSERT ON pos.audit_record FOR EACH ROW EXECUTE FUNCTION pos.reject_recovery_test()");
    assertThrows(
        Exception.class,
        () -> recovery.recover("12345".toCharArray(), "222222".toCharArray(), key));
    assertNotNull(modules.authenticate(token, "master", false));
    assertNotNull(login("654321", "master"));
    assertThrows(LocalFailure.class, () -> login("222222", "master"));
  }
}
