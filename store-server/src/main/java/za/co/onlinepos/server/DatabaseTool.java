package za.co.onlinepos.server;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.installation.Administrator;
import za.co.onlinepos.installation.FileInstallationRepository;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.NetworkSettings;
import za.co.onlinepos.installation.WindowsSecretStore;
import za.co.onlinepos.server.local.LocalDatabase;
import za.co.onlinepos.server.local.Passwords;

/**
 * Elevated/local-console migration and first staff bootstrap. Never runs in the service lifecycle.
 */
public final class DatabaseTool {
  private static final java.io.BufferedReader INPUT =
      new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8));

  private DatabaseTool() {}

  public static void migrate(DataSource owner, LocalDatabase.Identity identity) {
    var jdbc = new JdbcTemplate(owner);
    if (!"pos_owner".equals(jdbc.queryForObject("SELECT current_user", String.class)))
      throw new IllegalStateException("Migration owner required");
    String database = jdbc.queryForObject("SELECT current_database()", String.class);
    if (!java.util.List.of("pos_store", "pos_stage3_test").contains(database)
        || jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name<>'flyway_schema_history'",
                Integer.class)
            != 0) throw new IllegalStateException("Refusing an unrelated database");
    if (Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT to_regclass('pos.store_identity') IS NOT NULL", Boolean.class)))
      verifyIdentity(jdbc, identity);
    Flyway.configure()
        .dataSource(owner)
        .locations("classpath:db/migration")
        .cleanDisabled(true)
        .load()
        .migrate();
    new TransactionTemplate(new DataSourceTransactionManager(owner))
        .executeWithoutResult(
            status -> {
              jdbc.execute("LOCK TABLE pos.store_identity IN EXCLUSIVE MODE");
              if (jdbc.queryForObject("SELECT count(*) FROM pos.store_identity", Integer.class)
                  == 0) {
                jdbc.update(
                    "INSERT INTO pos.store_identity(branch_id,cluster_id,installation_id) VALUES (?,?,?)",
                    identity.branchId(),
                    identity.clusterId(),
                    identity.installationId());
                jdbc.update(
                    "INSERT INTO pos.stream_head(branch_id) VALUES (?)", identity.branchId());
              }
              verifyIdentity(jdbc, identity);
              jdbc.execute("GRANT USAGE ON SCHEMA public TO pos_runtime");
              jdbc.execute("GRANT SELECT ON public.flyway_schema_history TO pos_runtime");
            });
  }

  private static void verifyIdentity(JdbcTemplate jdbc, LocalDatabase.Identity identity) {
    var rows =
        jdbc.queryForList("SELECT branch_id,cluster_id,installation_id FROM pos.store_identity");
    if (rows.isEmpty()) return;
    var row = rows.getFirst();
    if (rows.size() != 1
        || ((Number) row.get("branch_id")).intValue() != identity.branchId()
        || !row.get("cluster_id").equals(identity.clusterId())
        || !row.get("installation_id").equals(identity.installationId()))
      throw new IllegalStateException("Refusing a different installation database");
  }

  public static void bootstrap(
      DataSource owner, LocalDatabase.Identity identity, String username, char[] password) {
    if (username == null || !username.matches("[a-z0-9._-]{3,64}"))
      throw new IllegalArgumentException("Invalid staff username");
    String hash = Passwords.hash(password);
    var jdbc = new JdbcTemplate(owner);
    verifyIdentity(jdbc, identity);
    new TransactionTemplate(new DataSourceTransactionManager(owner))
        .executeWithoutResult(
            status -> {
              jdbc.queryForObject(
                  "SELECT position FROM pos.stream_head WHERE branch_id=? FOR UPDATE",
                  Long.class,
                  identity.branchId());
              if (jdbc.queryForObject("SELECT count(*) FROM pos.staff", Integer.class) != 0)
                throw new IllegalStateException(
                    "Staff bootstrap already completed; use manager administration");
              UUID id = UUID.randomUUID();
              jdbc.update(
                  "INSERT INTO pos.staff(staff_id,branch_id,username,password_hash,role) VALUES (?,?,?,?,'MANAGER')",
                  id,
                  identity.branchId(),
                  username,
                  hash);
              jdbc.update(
                  "INSERT INTO pos.audit_record(audit_id,branch_id,actor_id,action,aggregate_id,details) VALUES (?,?,?,'STAFF_BOOTSTRAPPED',?,'{}')",
                  UUID.randomUUID(),
                  identity.branchId(),
                  id,
                  id);
            });
  }

  public static PGSimpleDataSource source(String url, String username, String password) {
    var source = new PGSimpleDataSource();
    source.setUrl(url);
    source.setUser(username);
    source.setPassword(password);
    source.setConnectTimeout(5);
    return source;
  }

  public static void main(String[] args) throws Exception {
    if (System.getProperty("os.name").startsWith("Windows")) {
      try (var channel =
              java.nio.channels.FileChannel.open(
                  InstalledConfiguration.dataRoot().resolve("management.lock"),
                  java.nio.file.StandardOpenOption.CREATE,
                  java.nio.file.StandardOpenOption.WRITE);
          var held = channel.lock()) {
        run(args);
      }
    } else {
      run(args);
    }
  }

  private static void run(String[] args) throws Exception {
    try {
      String operation =
          Arrays.stream(args)
              .filter(a -> a.startsWith("--database-tool="))
              .findFirst()
              .orElseThrow()
              .substring(16);
      boolean windows = System.getProperty("os.name").startsWith("Windows");
      LocalDatabase.Identity identity;
      DataSource owner;
      if (windows) {
        var root = InstalledConfiguration.dataRoot();
        var installed = new FileInstallationRepository(root).load().orElseThrow();
        if (installed.role() != za.co.onlinepos.contracts.InstallationRole.MASTER)
          throw new SecurityException("Master required");
        identity =
            new LocalDatabase.Identity(
                installed.branchId(), installed.clusterId(), installed.installationId());
        if (!operation.equals("migrate")) {
          char[] admin = readPassword("Installation administrator: ");
          try {
            new Administrator(root, Clock.systemUTC()).verify(admin);
          } finally {
            Arrays.fill(admin, '\0');
          }
        }
        var secrets = new WindowsSecretStore(root);
        byte[] bundle = secrets.get("database-bundle");
        try {
          var credentials = JsonFiles.JSON.readValue(bundle, Map.class);
          owner =
              source(
                  "jdbc:postgresql://127.0.0.1:"
                      + NetworkSettings.load(root).postgresPort()
                      + "/pos_store",
                  "pos_owner",
                  (String) credentials.get("owner"));
          if (operation.equals("migrate"))
            secrets.put(
                "database-runtime",
                ((String) credentials.get("runtime")).getBytes(StandardCharsets.UTF_8));
        } finally {
          Arrays.fill(bundle, (byte) 0);
        }
      } else {
        identity =
            new LocalDatabase.Identity(
                Integer.parseInt(required("POS_BRANCH_ID")),
                UUID.fromString(required("POS_CLUSTER_ID")),
                UUID.fromString(required("POS_INSTALLATION_ID")));
        owner = source(required("POS_DB_URL"), "pos_owner", required("POS_DB_OWNER_PASSWORD"));
      }
      switch (operation) {
        case "migrate" -> migrate(owner, identity);
        case "authorize" -> {
          /* Local management authentication above; no database mutation. */
        }
        case "bootstrap-staff" -> {
          String user =
              Arrays.stream(args)
                  .filter(a -> a.startsWith("--username="))
                  .findFirst()
                  .orElseThrow()
                  .substring(11);
          char[] password = readPassword("New staff manager password (12-128 characters): ");
          try {
            bootstrap(owner, identity, user, password);
          } finally {
            Arrays.fill(password, '\0');
          }
        }
        default -> throw new IllegalArgumentException("Unsupported database operation");
      }
      System.out.println("LOCAL_DATABASE_OPERATION_OK " + operation);
    } catch (Exception exception) {
      System.err.println(
          "Local database operation refused. Check role, identity, credentials and migration diagnostics.");
      System.exit(1);
    }
  }

  private static String required(String name) {
    return java.util.Objects.requireNonNull(
        System.getenv(name), "Missing local database configuration");
  }

  private static char[] readPassword(String prompt) throws Exception {
    if (System.console() != null) return System.console().readPassword(prompt);
    // Redirected input supports controlled administration/CI; credentials never enter argv.
    System.err.print(prompt);
    String line = INPUT.readLine();
    if (line == null) throw new SecurityException("Credential input required");
    return line.toCharArray();
  }
}
