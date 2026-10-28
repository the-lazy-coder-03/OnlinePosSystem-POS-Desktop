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

  @Test
  void v3PinsAndPasswordsSurviveWithoutImplicitOwnerAssignment() {
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
        .target("3")
        .load()
        .migrate();
    var cluster = UUID.randomUUID();
    var installation = UUID.randomUUID();
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO pos.store_identity VALUES (true,2,?,?)", cluster, installation);
    jdbc.update("INSERT INTO pos.stream_head(branch_id) VALUES (2)");
    String passwordHash = Passwords.hash(UUID.randomUUID().toString().toCharArray());
    jdbc.update(
        "INSERT INTO pos.staff(staff_id,branch_id,username,password_hash,role) VALUES (?,2,'preserved.manager',?,'MANAGER')",
        id,
        passwordHash);
    String pinHash = Passwords.hashPin("000123".toCharArray());
    String lookup = "a".repeat(64);
    jdbc.update("INSERT INTO pos.staff_pin VALUES (2,?,?,?)", id, lookup, pinHash);
    Flyway.configure().dataSource(owner).locations("classpath:db/migration").load().migrate();
    assertEquals(
        passwordHash,
        jdbc.queryForObject(
            "SELECT password_hash FROM pos.staff WHERE staff_id=?", String.class, id));
    assertEquals(
        pinHash,
        jdbc.queryForObject("SELECT hash FROM pos.staff_pin WHERE staff_id=?", String.class, id));
    assertEquals(
        lookup,
        jdbc.queryForObject("SELECT lookup FROM pos.staff_pin WHERE staff_id=?", String.class, id));
    assertEquals(
        true,
        jdbc.queryForObject(
            "SELECT password_login_enabled FROM pos.staff WHERE staff_id=?", Boolean.class, id));
    assertEquals(
        passwordHash,
        jdbc.queryForObject(
            "SELECT verifier FROM pos.staff_auth_method WHERE staff_id=? AND method='PASSWORD'",
            String.class,
            id));
    assertEquals(
        pinHash,
        jdbc.queryForObject(
            "SELECT verifier FROM pos.staff_auth_method WHERE staff_id=? AND method='PIN'",
            String.class,
            id));
    assertEquals(
        lookup,
        jdbc.queryForObject(
            "SELECT lookup FROM pos.staff_auth_method WHERE staff_id=? AND method='PIN'",
            String.class,
            id));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM pos.staff_auth_method WHERE staff_id=? AND account_type='LOCAL'",
            Integer.class,
            id));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pos.store_owner", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM pos.role_permission WHERE branch_id=2 AND role_name='MANAGER' AND permission='audit.view'",
            Integer.class));
  }

  @Test
  void v4CatalogueSeedsEditableRecordsWithoutChangingImportedData() throws Exception {
    var source =
        DatabaseTool.source(
            System.getenv("POS_TEST_DATABASE_URL")
                .replace("/pos_stage3_test", "/pos_stage3_restore"),
            "pos_owner",
            System.getenv("POS_TEST_OWNER_PASSWORD"));
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("DROP SCHEMA IF EXISTS pos CASCADE");
    jdbc.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
    Flyway.configure()
        .dataSource(source)
        .locations("classpath:db/migration")
        .target("4")
        .load()
        .migrate();
    var cluster = UUID.randomUUID();
    jdbc.update("INSERT INTO pos.store_identity VALUES(true,1,?,?)", cluster, UUID.randomUUID());
    jdbc.update("INSERT INTO pos.stream_head(branch_id) VALUES(1)");
    var json = new ObjectMapper();
    var tables = json.readTree(getClass().getResourceAsStream("/catalogue-source-fixture.json"));
    var snapshot = json.createObjectNode();
    snapshot.set("tables", tables);
    snapshot.put("branchId", 1).put("snapshotId", UUID.randomUUID().toString());
    var id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pos.catalogue_bootstrap(branch_id,snapshot_id,sha256,snapshot) VALUES(1,?, ?,?::jsonb)",
        id,
        "a".repeat(64),
        snapshot.toString());
    String before =
        jdbc.queryForObject("SELECT snapshot::text FROM pos.catalogue_bootstrap", String.class);
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    assertEquals(
        before,
        jdbc.queryForObject("SELECT snapshot::text FROM pos.catalogue_bootstrap", String.class));
    assertEquals(
        "a".repeat(64),
        jdbc.queryForObject("SELECT sha256 FROM pos.catalogue_bootstrap", String.class));
    assertEquals(
        31, jdbc.queryForObject("SELECT count(*) FROM pos.catalogue_record", Integer.class));
    assertEquals(
        "[1,1,1]",
        jdbc.queryForObject(
            "SELECT source_id FROM pos.catalogue_record WHERE source_type='branch_pizza_price'",
            String.class));
    assertEquals(0, jdbc.queryForObject("SELECT revision FROM pos.catalogue_version", Long.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM pos.role_permission WHERE permission='catalogue.manage'",
            Integer.class));
  }

  @Test
  void legacyCustomersAndCancelledDraftReferencesSurviveV9WithCollisionsFlagged() {
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
        .target("8")
        .load()
        .migrate();
    jdbc.update(
        "INSERT INTO pos.store_identity VALUES (true,1,?,?)", UUID.randomUUID(), UUID.randomUUID());
    jdbc.update("INSERT INTO pos.stream_head(branch_id) VALUES (1)");
    UUID customer = UUID.randomUUID(), duplicate = UUID.randomUUID(), order = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pos.local_customer(customer_id,branch_id,display_name,phone,address,revision) VALUES (?,1,'Legacy','082 123 4567','Preserved address',7),(?,1,'Duplicate','+27821234567','Other address',2)",
        customer,
        duplicate);
    jdbc.update(
        "INSERT INTO pos.order_draft(sync_id,branch_id,customer_id,state,provisional_reference,total) VALUES (?,1,?,'CANCELLED','legacy-reference',0)",
        order,
        customer);
    Flyway.configure().dataSource(owner).locations("classpath:db/migration").load().migrate();
    assertEquals(
        customer,
        jdbc.queryForObject(
            "SELECT customer_id FROM pos.order_draft WHERE sync_id=?", UUID.class, order));
    assertEquals(
        "CANCELLED",
        jdbc.queryForObject(
            "SELECT state FROM pos.order_draft WHERE sync_id=?", String.class, order));
    assertEquals(
        7L,
        jdbc.queryForObject(
            "SELECT revision FROM pos.local_customer WHERE customer_id=?", Long.class, customer));
    assertEquals(
        "Preserved address",
        jdbc.queryForObject(
            "SELECT profile->>'formatted_address' FROM pos.local_customer WHERE customer_id=?",
            String.class,
            customer));
    assertEquals(
        "+27821234567",
        jdbc.queryForObject(
            "SELECT profile->>'phone1' FROM pos.local_customer WHERE customer_id=?",
            String.class,
            customer));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM pos.customer_conflict WHERE reason='LEGACY_CONTACT_COLLISION'",
            Integer.class));
    assertEquals(
        "{}",
        jdbc.queryForObject(
            "SELECT field_times::text FROM pos.local_customer WHERE customer_id=?",
            String.class,
            customer));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM pos.local_customer WHERE updated_at IS NOT NULL", Integer.class));
  }
}
