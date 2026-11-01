package za.co.onlinepos.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import za.co.onlinepos.server.DatabaseTool;

/** Protected disposable V6 fixture, never connects to the installed Windows store. */
@EnabledIfEnvironmentVariable(named = "POS_TEST_DATABASE_URL", matches = ".+/pos_stage3_test")
class SalesMigrationIT {
  @Test
  void v6UpgradePreservesRowsAndAppliedChecksums() {
    String url =
        System.getenv("POS_TEST_DATABASE_URL").replace("/pos_stage3_test", "/pos_stage3_restore");
    if (!url.endsWith("/pos_stage3_restore") || !url.startsWith("jdbc:postgresql://127.0.0.1:"))
      throw new IllegalStateException("Disposable loopback clone required");
    var source = DatabaseTool.source(url, "pos_owner", System.getenv("POS_TEST_OWNER_PASSWORD"));
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("DROP SCHEMA IF EXISTS pos CASCADE");
    jdbc.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
    Flyway.configure()
        .dataSource(source)
        .locations("classpath:db/migration")
        .target("6")
        .load()
        .migrate();
    var checksums =
        jdbc.queryForList(
            "SELECT version,checksum FROM public.flyway_schema_history ORDER BY installed_rank");
    assertEquals(
        List.of(705883066, 1463029665, 1771422558, -742917607, -1382202340, -340618992),
        checksums.stream().map(r -> r.get("checksum")).toList());
    jdbc.update(
        "INSERT INTO pos.store_identity VALUES(true,1,?,?)", UUID.randomUUID(), UUID.randomUUID());
    UUID staff = UUID.randomUUID(),
        item = UUID.randomUUID(),
        price = UUID.randomUUID(),
        draft = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pos.staff(staff_id,branch_id,username,password_hash,role) VALUES(?,1,'preserved','existing-verifier','MANAGER')",
        staff);
    jdbc.update(
        "INSERT INTO pos.catalog_item(item_id,branch_id,source_type,source_id,description,definition,definition_revision) VALUES(?,1,'UPGRADE_TEST','source','Existing product','{}',0)",
        item);
    jdbc.update(
        "INSERT INTO pos.branch_price(price_id,branch_id,item_id,variant_key,amount,available) VALUES(?,1,?,'size',25.50,true)",
        price,
        item);
    jdbc.update(
        "INSERT INTO pos.order_draft(sync_id,branch_id,state,provisional_reference,total) VALUES(?,1,'DRAFT','preserved-draft',25.50)",
        draft);
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    assertEquals(
        checksums,
        jdbc.queryForList(
            "SELECT version,checksum FROM public.flyway_schema_history WHERE installed_rank<=6 ORDER BY installed_rank"));
    assertEquals(
        "existing-verifier",
        jdbc.queryForObject(
            "SELECT password_hash FROM pos.staff WHERE staff_id=?", String.class, staff));
    assertEquals(
        "Existing product",
        jdbc.queryForObject(
            "SELECT description FROM pos.catalog_item WHERE item_id=?", String.class, item));
    assertEquals(
        "DRAFT",
        jdbc.queryForObject(
            "SELECT state FROM pos.order_draft WHERE sync_id=?", String.class, draft));
    assertEquals(
        "19",
        jdbc.queryForObject(
            "SELECT version FROM public.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
            String.class));
  }
}
