package za.co.onlinepos.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.server.DatabaseTool;
import za.co.onlinepos.server.local.CommandJournal;
import za.co.onlinepos.server.local.LocalDatabase;
import za.co.onlinepos.server.local.Passwords;
import za.co.onlinepos.server.local.StaffService;

@EnabledIfEnvironmentVariable(named = "POS_TEST_DATABASE_URL", matches = ".+/pos_stage3_test")
class RbacUpgradeIT {
  @Test
  void existingV2StaffPasswordsAndCatalogueSurviveRbacMigration() {
    var owner =
        DatabaseTool.source(
            System.getenv("POS_TEST_DATABASE_URL")
                .replace("/pos_stage3_test", "/pos_stage3_restore"),
            "pos_owner",
            System.getenv("POS_TEST_OWNER_PASSWORD"));
    var jdbc = new JdbcTemplate(owner);
    jdbc.execute("DROP SCHEMA IF EXISTS pos CASCADE");
    jdbc.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
    Flyway.configure()
        .dataSource(owner)
        .locations("classpath:db/migration")
        .target("2")
        .load()
        .migrate();
    var identity = new LocalDatabase.Identity(2, UUID.randomUUID(), UUID.randomUUID());
    jdbc.update(
        "INSERT INTO pos.store_identity VALUES (true,?,?,?)",
        2,
        identity.clusterId(),
        identity.installationId());
    jdbc.update("INSERT INTO pos.stream_head(branch_id) VALUES (2)");
    UUID manager = UUID.randomUUID(), cashier = UUID.randomUUID();
    String password = UUID.randomUUID().toString(), hash = Passwords.hash(password.toCharArray());
    jdbc.update(
        "INSERT INTO pos.staff(staff_id,branch_id,username,password_hash,role) VALUES (?,2,'existing.manager',?,'MANAGER'),(?,2,'existing.cashier',?,'CASHIER')",
        manager,
        hash,
        cashier,
        hash);
    UUID item = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pos.catalog_item(item_id,branch_id,source_type,source_id,description,definition,definition_revision) VALUES (?,2,'TEST_FIXTURE','preserved','Preserved item','{}',0)",
        item);
    Flyway.configure().dataSource(owner).locations("classpath:db/migration").load().migrate();
    assertEquals(
        hash,
        jdbc.queryForObject(
            "SELECT password_hash FROM pos.staff WHERE staff_id=?", String.class, manager));
    assertEquals(
        "Preserved item",
        jdbc.queryForObject(
            "SELECT description FROM pos.catalog_item WHERE item_id=?", String.class, item));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM pos.staff_role", Integer.class));
    assertEquals(
        6,
        jdbc.queryForObject(
            "SELECT count(*) FROM pos.role_permission WHERE role_name='MANAGER' AND permission LIKE 'module.%'",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM pos.role_permission WHERE role_name='CASHIER'", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pos.staff_pin", Integer.class));
    var transactions = new TransactionTemplate(new DataSourceTransactionManager(owner));
    var journal = new CommandJournal(jdbc, transactions, new ObjectMapper(), identity);
    var service = new StaffService(jdbc, transactions, journal, identity);
    assertNotNull(
        service
            .login(new StaffService.Login("existing.manager", password), "upgrade-test")
            .get("token"));
  }
}
