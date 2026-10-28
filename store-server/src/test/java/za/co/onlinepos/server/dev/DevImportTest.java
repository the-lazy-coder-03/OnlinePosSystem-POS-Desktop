package za.co.onlinepos.server.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

class DevImportTest {
  @TempDir Path directory;

  MockEnvironment env() {
    var env =
        new MockEnvironment()
            .withProperty("server.address", "127.0.0.1")
            .withProperty("pos.dev.data-dir", directory.resolve("db").toString())
            .withProperty("pos.dev.legacy-dir", directory.resolve("legacy").toString());
    env.setActiveProfiles("dev");
    return env;
  }

  @Test
  void conflictingIdsRollbackAllImportRowsAndRetainUserEdits() throws Exception {
    try (var source = new DevDatabase().devDataSource(env())) {
      var jdbc = new JdbcTemplate(source);
      var store =
          new DevModuleStore(
              jdbc,
              new ObjectMapper(),
              new TransactionTemplate(new DataSourceTransactionManager(source)));
      var repository = new DevCatalogueRepository(store);
      var importer = new DevCatalogueImport(repository);
      jdbc.update("INSERT INTO public.menu_category(id,name) VALUES(1,'User-owned category')");
      var seed = store.json.readTree(getClass().getResourceAsStream("/catalogue-h2-seed.json"));
      assertThrows(IllegalStateException.class, () -> importer.apply(seed));
      assertEquals(
          "User-owned category",
          jdbc.queryForObject("SELECT name FROM public.menu_category WHERE id=1", String.class));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.branch", Integer.class));
      assertEquals(
          0, jdbc.queryForObject("SELECT count(*) FROM dev_meta.import_receipt", Integer.class));
    }
  }

  @Test
  void lossyMonetaryConversionFailsBeforeAlteringValuesAndRetainsLegacy() throws Exception {
    Path legacy = directory.resolve("legacy");
    Files.createDirectories(legacy);
    Files.writeString(
        legacy.resolve("credentials.json"), "{\"password\":\"long-enough-test-password\"}");
    String url =
        "jdbc:h2:file:" + legacy.resolve("onlinepos") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
    try (var connection =
        DriverManager.getConnection(url, "onlinepos", "long-enough-test-password")) {
      new ResourceDatabasePopulator(new ClassPathResource("db/h2/schema.sql"))
          .execute(new SingleConnectionDataSource(connection, true));
      var statement = connection.createStatement();
      statement.executeUpdate("INSERT INTO branch(branch_id,name) VALUES(1,'Kenridge')");
      statement.executeUpdate("INSERT INTO menu_category(id,name) VALUES(1,'Legacy')");
      statement.executeUpdate(
          "INSERT INTO menu_item(id,category_id,name) VALUES(1,1,'Legacy item')");
      statement.executeUpdate("INSERT INTO branch_menu_item_price VALUES(1,1,1.234)");
    }
    byte[] original = Files.readAllBytes(legacy.resolve("onlinepos.mv.db"));
    assertThrows(Exception.class, () -> new DevDatabase().devDataSource(env()));
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        original, Files.readAllBytes(legacy.resolve("onlinepos.mv.db")));
    try (var connection =
            DriverManager.getConnection(
                "jdbc:h2:file:"
                    + directory.resolve("db/onlinepos")
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "onlinepos",
                "long-enough-test-password");
        var rows =
            connection.createStatement().executeQuery("SELECT price FROM branch_menu_item_price")) {
      assertTrue(rows.next());
      assertEquals(0, rows.getBigDecimal(1).compareTo(new java.math.BigDecimal("1.234")));
    }
  }
}
