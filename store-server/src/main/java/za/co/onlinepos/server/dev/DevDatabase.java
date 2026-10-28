package za.co.onlinepos.server.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("dev")
public class DevDatabase {
  public record Identity(Path root, String token, String instanceId) {
    @Override
    public String toString() {
      return "DevIdentity[REDACTED]";
    }
  }

  public static Path dataRoot(Environment env) {
    String override = env.getProperty("pos.dev.data-dir");
    if (override != null) {
      Path path = Path.of(override);
      if (!path.isAbsolute())
        throw new IllegalArgumentException("Development data directory must be absolute");
      return path.normalize();
    }
    Path home = Path.of(System.getProperty("user.home"));
    String os = System.getProperty("os.name");
    if (os.startsWith("Mac")) return home.resolve("Library/Application Support/OnlinePOS/dev");
    if (os.startsWith("Windows")) {
      String local = System.getenv("LOCALAPPDATA");
      return (local == null ? home.resolve("AppData/Local") : Path.of(local))
          .resolve("OnlinePOS/dev");
    }
    String xdg = System.getenv("XDG_DATA_HOME");
    return (xdg == null ? home.resolve(".local/share") : Path.of(xdg)).resolve("OnlinePOS/dev");
  }

  public static void protect(Path path, boolean directory) throws Exception {
    if (Files.getFileStore(path).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(
          path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
  }

  @Bean(destroyMethod = "close")
  HikariDataSource devDataSource(Environment env) throws Exception {
    if (env.matchesProfiles("installed", "local-store", "h2-development"))
      throw new IllegalStateException("dev cannot be combined with other database profiles");
    if (!"127.0.0.1".equals(env.getProperty("server.address")))
      throw new IllegalStateException("Development backend must bind loopback");
    Path root = dataRoot(env);
    if (root.toString().contains(";"))
      throw new IllegalArgumentException("Data directory cannot contain H2 option separators");
    Files.createDirectories(root);
    protect(root, true);
    var ownership =
        FileChannel.open(
            root.resolve("backend.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    java.nio.channels.FileLock lease;
    try {
      lease = ownership.tryLock();
    } catch (java.nio.channels.OverlappingFileLockException locked) {
      ownership.close();
      throw new IllegalStateException("Another development backend owns this directory");
    }
    if (lease == null) {
      ownership.close();
      throw new IllegalStateException("Another development backend owns this directory");
    }
    try {
      return openDatabase(env, root, ownership, lease);
    } catch (Exception failure) {
      try {
        if (lease.isValid()) lease.close();
        if (ownership.isOpen()) ownership.close();
      } catch (java.io.IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  private HikariDataSource openDatabase(
      Environment env, Path root, FileChannel ownership, java.nio.channels.FileLock lease)
      throws Exception {
    Path database = root.resolve("onlinepos.mv.db");
    Path credential = root.resolve("credentials.json");
    Path legacy =
        Path.of(env.getProperty("pos.dev.legacy-dir", "runtime/h2")).toAbsolutePath().normalize();
    if (!Files.exists(database)
        && !Files.exists(credential)
        && Files.exists(legacy.resolve("onlinepos.mv.db"))) {
      if (Files.exists(root.resolve("adoption.pending")))
        throw new IllegalStateException("Incomplete database adoption; review retained original");
      // H2 uses an exclusive file lock. Never copy a database owned by a running process.
      try (var file =
              FileChannel.open(
                  legacy.resolve("onlinepos.mv.db"),
                  StandardOpenOption.READ,
                  StandardOpenOption.WRITE);
          var lock = file.tryLock()) {
        if (lock == null)
          throw new IllegalStateException("Stop the old H2 backend before adoption");
        if (!Files.isRegularFile(legacy.resolve("credentials.json")))
          throw new IllegalStateException("Legacy credential missing");
        Files.createFile(root.resolve("adoption.pending"));
        Files.copy(legacy.resolve("credentials.json"), credential);
        Files.copy(legacy.resolve("onlinepos.mv.db"), database);
        protect(credential, false);
        protect(database, false);
      }
    }
    if (Files.exists(database) != Files.exists(credential))
      throw new IllegalStateException(
          "Database and credential must both exist; refusing to replace either");
    ObjectMapper json = new ObjectMapper();
    if (!Files.exists(credential)) {
      String password = randomSecret();
      Files.createFile(credential);
      protect(credential, false);
      json.writeValue(credential.toFile(), Map.of("password", password));
    }
    String password = json.readTree(credential.toFile()).path("password").asText();
    if (password.length() < 16)
      throw new IllegalStateException("Invalid protected database credential");
    boolean existingDatabase = Files.exists(database);
    var config = new HikariConfig();
    config.setJdbcUrl(
        "jdbc:h2:file:"
            + root.resolve("onlinepos")
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_ON_EXIT=FALSE");
    config.setUsername("onlinepos");
    config.setPassword(password);
    config.setMaximumPoolSize(4);
    config.setConnectionTimeout(5000);
    var source =
        new HikariDataSource(config) {
          private final java.util.concurrent.atomic.AtomicBoolean released =
              new java.util.concurrent.atomic.AtomicBoolean();

          @Override
          public void close() {
            try {
              super.close();
            } finally {
              if (released.compareAndSet(false, true)) {
                try {
                  lease.close();
                  ownership.close();
                } catch (java.io.IOException failure) {
                  throw new IllegalStateException(
                      "Unable to release development database ownership");
                }
              }
            }
          }
        };
    try {
      JdbcTemplate jdbc = new JdbcTemplate(source);
      if (existingDatabase) validateLegacy(source);
      Flyway.configure()
          .dataSource(source)
          .defaultSchema("dev_meta")
          .schemas("dev_meta")
          .locations("classpath:db/h2-migration", "classpath:db/h2")
          .cleanDisabled(true)
          .load()
          .migrate();
      protect(database, false);
      Files.deleteIfExists(root.resolve("adoption.pending"));
      return source;
    } catch (Exception failure) {
      source.close();
      throw failure;
    }
  }

  private static void validateLegacy(javax.sql.DataSource source) throws Exception {
    try (var reference =
        java.sql.DriverManager.getConnection(
            "jdbc:h2:mem:verify_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            "sa",
            "")) {
      new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
              new org.springframework.core.io.ClassPathResource("db/h2/schema.sql"))
          .execute(
              new org.springframework.jdbc.datasource.SingleConnectionDataSource(reference, true));
      var referenceJdbc =
          new JdbcTemplate(
              new org.springframework.jdbc.datasource.SingleConnectionDataSource(reference, true));
      var actual = new JdbcTemplate(source);
      for (var column :
          referenceJdbc.queryForList(
              "SELECT table_name,column_name,data_type FROM information_schema.columns WHERE table_schema='public'")) {
        int matches =
            actual.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name=? AND (data_type=? OR (data_type='numeric' AND ?='double precision'))",
                Integer.class,
                column.get("table_name"),
                column.get("column_name"),
                column.get("data_type"),
                column.get("data_type"));
        if (matches != 1)
          throw new IllegalStateException(
              "Unrecognized legacy schema; retained original is unchanged");
      }
    }
  }

  @Bean
  Identity devIdentity(HikariDataSource source, Environment env) throws Exception {
    Path root = dataRoot(env);
    String instance = UUID.randomUUID().toString();
    String token = randomSecret();
    Path file = root.resolve("connection.json");
    if (Files.exists(file)) Files.delete(file);
    Files.createFile(file);
    protect(file, false);
    new ObjectMapper()
        .writeValue(
            file.toFile(),
            Map.of(
                "token",
                token,
                "instanceId",
                instance,
                "port",
                Integer.parseInt(env.getProperty("server.port", "18091"))));
    return new Identity(root, token, instance);
  }

  static String randomSecret() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  @Bean
  JdbcTemplate devJdbc(HikariDataSource source) {
    var jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(5);
    return jdbc;
  }

  @Bean
  TransactionTemplate devTransactions(HikariDataSource source) {
    return new TransactionTemplate(new DataSourceTransactionManager(source));
  }
}
