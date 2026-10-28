package za.co.onlinepos.server.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.DevData;

class DevDataTest {
  @TempDir Path directory;

  private MockEnvironment environment(Path root) {
    var env =
        new MockEnvironment()
            .withProperty("server.address", "127.0.0.1")
            .withProperty("pos.dev.data-dir", root.toString())
            .withProperty("pos.dev.legacy-dir", directory.resolve("no-legacy").toString());
    env.setActiveProfiles("dev");
    return env;
  }

  private DevCategoryService service(javax.sql.DataSource source) {
    return new DevCategoryService(
        new JdbcTemplate(source),
        new TransactionTemplate(new DataSourceTransactionManager(source)),
        new ObjectMapper());
  }

  private DevData.Save command(Long revision, String name, boolean active) {
    return new DevData.Save(UUID.randomUUID(), revision, name, 0, active);
  }

  @Test
  void persistenceRevisionsSearchAndIdempotency() throws Exception {
    var config = new DevDatabase();
    var env = environment(directory.resolve("db"));
    int id;
    try (var source = config.devDataSource(env)) {
      var service = service(source);
      var command = command(null, " Burgers ", true);
      var created = service.save(null, command);
      id = created.id();
      assertEquals(created, service.save(null, command));
      assertEquals("Burgers", created.name());
      assertEquals(1, service.list("burger", "active", 0, 10).total());
      assertThrows(
          DevCategoryService.Failure.class,
          () -> service.save(null, new DevData.Save(command.commandId(), null, "Other", 0, true)));
      assertThrows(
          DevCategoryService.Failure.class,
          () -> service.save(null, command(null, "BURGERS", true)));
      var disabled = service.save(id, command(0L, "Burgers", false));
      assertEquals(1, disabled.revision());
      assertEquals(0, service.list("", "active", 0, 10).total());
      assertEquals(1, service.list("", "disabled", 0, 10).total());
      assertThrows(
          DevCategoryService.Failure.class, () -> service.save(id, command(0L, "Stale", true)));
      assertThrows(
          DevCategoryService.Failure.class, () -> service.save(null, command(null, " ", true)));
      assertThrows(DevCategoryService.Failure.class, () -> service.list("", "invalid", 0, 10));
      var jdbc = new JdbcTemplate(source);
      assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM dev_meta.commands", Integer.class));
      assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM dev_meta.changes", Integer.class));
    }
    try (var source = config.devDataSource(env)) {
      var service = service(source);
      assertFalse(service.list("", "all", 0, 10).records().getFirst().active());
      service.save(id, command(1L, "Burgers restored", true));
      assertEquals(1, service.list("restored", "active", 0, 10).total());
    }
  }

  @Test
  void journalFailureRollsBackBusinessAndReceipt() throws Exception {
    try (var source = new DevDatabase().devDataSource(environment(directory.resolve("rollback")))) {
      var jdbc = new JdbcTemplate(source);
      jdbc.execute(
          "ALTER TABLE dev_meta.changes ADD CONSTRAINT reject_test CHECK(action <> 'CREATED')");
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> service(source).save(null, command(null, "Rollback", true)));
      assertEquals(
          0, jdbc.queryForObject("SELECT count(*) FROM public.menu_category", Integer.class));
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dev_meta.commands", Integer.class));
      assertEquals(
          1, jdbc.queryForObject("SELECT next_id FROM dev_meta.category_ids", Integer.class));
    }
  }

  @Test
  void concurrentEditsAndPagination() throws Exception {
    try (var source =
            new DevDatabase().devDataSource(environment(directory.resolve("concurrent")));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var service = service(source);
      var category = service.save(null, command(null, "A", true));
      var results =
          executor.invokeAll(
              java.util.List.of(
                  () -> {
                    try {
                      service.save(category.id(), command(0L, "Changed 1", true));
                      return true;
                    } catch (DevCategoryService.Failure failure) {
                      return false;
                    }
                  },
                  () -> {
                    try {
                      service.save(category.id(), command(0L, "Changed 2", true));
                      return true;
                    } catch (DevCategoryService.Failure failure) {
                      return false;
                    }
                  }));
      assertEquals(
          1,
          results.stream()
              .filter(
                  f -> {
                    try {
                      return Boolean.TRUE.equals(f.get());
                    } catch (Exception failure) {
                      throw new RuntimeException(failure);
                    }
                  })
              .count());
      service.save(null, command(null, "B", true));
      assertEquals(1, service.list("", "all", 1, 1).records().size());
      assertEquals(2, service.list("", "all", 0, 1).total());
    }
  }

  @Test
  void adoptsExistingFileWithoutChangingOriginalAndRejectsCompetingBackend() throws Exception {
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
      connection
          .createStatement()
          .executeUpdate("INSERT INTO menu_category(id,name) VALUES(41,'Existing')");
    }
    byte[] original = Files.readAllBytes(legacy.resolve("onlinepos.mv.db"));
    var config = new DevDatabase();
    var env =
        environment(directory.resolve("adopted"))
            .withProperty("pos.dev.legacy-dir", legacy.toString());
    try (var source = config.devDataSource(env)) {
      assertEquals("Existing", service(source).list("", "all", 0, 10).records().getFirst().name());
      assertEquals(42, service(source).save(null, command(null, "New", true)).id());
      assertThrows(IllegalStateException.class, () -> config.devDataSource(env));
      assertTrue(Files.exists(legacy.resolve("onlinepos.mv.db")));
    }
    org.junit.jupiter.api.Assertions.assertArrayEquals(
        original, Files.readAllBytes(legacy.resolve("onlinepos.mv.db")));
  }

  @Test
  void refusesUnrecognizedExistingDestinationWithoutClearingRecords() throws Exception {
    Path root = directory.resolve("foreign");
    Files.createDirectories(root);
    Files.writeString(
        root.resolve("credentials.json"), "{\"password\":\"long-enough-test-password\"}");
    String url =
        "jdbc:h2:file:" + root.resolve("onlinepos") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
    try (var connection =
        DriverManager.getConnection(url, "onlinepos", "long-enough-test-password")) {
      connection.createStatement().execute("CREATE TABLE keep_me(id INT PRIMARY KEY)");
      connection.createStatement().execute("INSERT INTO keep_me VALUES(9)");
    }
    assertThrows(
        IllegalStateException.class, () -> new DevDatabase().devDataSource(environment(root)));
    try (var connection =
            DriverManager.getConnection(url, "onlinepos", "long-enough-test-password");
        var rows = connection.createStatement().executeQuery("SELECT id FROM keep_me")) {
      assertTrue(rows.next());
      assertEquals(9, rows.getInt(1));
    }
  }

  @Test
  void refusesIncompatibleProfilesAndRelativeDirectory() {
    var config = new DevDatabase();
    var env = environment(directory.resolve("invalid"));
    env.setActiveProfiles("dev", "installed");
    assertThrows(IllegalStateException.class, () -> config.devDataSource(env));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DevDatabase.dataRoot(
                new MockEnvironment().withProperty("pos.dev.data-dir", "relative")));
  }

  @Test
  void categoryArchivePreservesIdentityAndCanBeRestoredAfterRestart() throws Exception {
    var config = new DevDatabase();
    var env = environment(directory.resolve("archive-db"));
    int id;
    try (var source = config.devDataSource(env)) {
      var service = service(source);
      var created = service.save(null, command(null, "Archive me", true));
      id = created.id();
      var request =
          new DevData.Save(UUID.randomUUID(), created.revision(), created.name(), 0, true, true);
      var archived = service.save(id, request);
      assertTrue(archived.archived());
      assertFalse(archived.active());
      assertEquals(archived, service.save(id, request));
      assertEquals(0, service.list("", "active", 0, 10).total());
      assertEquals(1, service.list("", "archived", 0, 10).total());
    }
    try (var source = config.devDataSource(env)) {
      var service = service(source);
      var archived = service.list("", "archived", 0, 10).records().getFirst();
      var restored =
          service.save(
              id,
              new DevData.Save(
                  UUID.randomUUID(), archived.revision(), archived.name(), 0, true, false));
      assertEquals(id, restored.id());
      assertTrue(restored.active());
      assertFalse(restored.archived());
    }
  }
}
