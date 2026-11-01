package za.co.onlinepos.server.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ScreenUpgradeTest {
  @Test
  void h2V12UpgradePreservesStoreAndAppliedMigrationChecksums() {
    var source = new JdbcDataSource();
    source.setURL(
        "jdbc:h2:mem:screen_upgrade_"
            + java.util.UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    Flyway.configure()
        .dataSource(source)
        .locations("classpath:db/h2-migration", "classpath:db/h2")
        .schemas("dev_meta")
        .defaultSchema("dev_meta")
        .target("12")
        .load()
        .migrate();
    var jdbc = new JdbcTemplate(source);
    jdbc.update("INSERT INTO public.branch(branch_id,name) VALUES(37,'Independent store 37')");
    var checksums =
        jdbc.queryForList(
            "SELECT version,checksum FROM dev_meta.flyway_schema_history ORDER BY installed_rank");
    var identity =
        jdbc.queryForObject(
            "SELECT store_uuid FROM public.branch WHERE branch_id=37", java.util.UUID.class);
    Flyway.configure()
        .dataSource(source)
        .locations("classpath:db/h2-migration", "classpath:db/h2")
        .schemas("dev_meta")
        .defaultSchema("dev_meta")
        .load()
        .migrate();
    assertEquals(
        checksums,
        jdbc.queryForList(
            "SELECT version,checksum FROM dev_meta.flyway_schema_history WHERE version IS NULL OR CAST(version AS INT)<=12 ORDER BY installed_rank"));
    assertEquals(
        identity,
        jdbc.queryForObject(
            "SELECT store_uuid FROM public.branch WHERE branch_id=37", java.util.UUID.class));
    assertEquals(
        "Independent store 37",
        jdbc.queryForObject("SELECT name FROM public.branch WHERE branch_id=37", String.class));
    assertEquals(
        "13",
        jdbc.queryForObject(
            "SELECT version FROM dev_meta.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",
            String.class));
    jdbc.execute("SHUTDOWN");
  }
}
