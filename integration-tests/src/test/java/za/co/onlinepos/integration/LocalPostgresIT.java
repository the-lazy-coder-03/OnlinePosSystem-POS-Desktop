package za.co.onlinepos.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import za.co.onlinepos.contracts.LocalCommands;
import za.co.onlinepos.server.DatabaseTool;
import za.co.onlinepos.server.StoreServerApplication;
import za.co.onlinepos.server.local.CommandJournal;
import za.co.onlinepos.server.local.LocalDatabase;
import za.co.onlinepos.server.local.LocalFailure;
import za.co.onlinepos.server.local.LocalOperations;
import za.co.onlinepos.server.local.StaffService;

@SpringBootTest(
    classes = StoreServerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("local-store")
@EnabledIfEnvironmentVariable(named = "POS_TEST_DATABASE_URL", matches = ".+/pos_stage3_test")
class LocalPostgresIT {
  private static final LocalDatabase.Identity IDENTITY =
      new LocalDatabase.Identity(
          1,
          UUID.fromString("37fa75cb-bc7f-44a3-a105-a7e7fa788413"),
          UUID.fromString("18ddca74-9fe5-4b3c-aee8-f397230b760d"));
  private static final String TERMINAL = "development:126091ff-0b19-48a3-94b6-d3767f69ec78";
  private static final String PASSWORD = UUID.randomUUID().toString();
  private static final UUID PRICE = UUID.randomUUID();
  private static final UUID ITEM = UUID.randomUUID();
  private static final javax.sql.DataSource OWNER =
      DatabaseTool.source(
          System.getenv("POS_TEST_DATABASE_URL"),
          "pos_owner",
          System.getenv("POS_TEST_OWNER_PASSWORD"));
  private static final JdbcTemplate ADMIN = new JdbcTemplate(OWNER);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry properties) {
    DatabaseTool.migrate(OWNER, IDENTITY);
    properties.add("pos.database.url", () -> System.getenv("POS_TEST_DATABASE_URL"));
    properties.add("pos.database.password", () -> System.getenv("POS_TEST_RUNTIME_PASSWORD"));
    properties.add("pos.branch-id", () -> 1);
    properties.add("pos.pin-key", () -> PIN_KEY);
    properties.add("pos.cluster-id", IDENTITY::clusterId);
    properties.add("pos.installation-id", IDENTITY::installationId);
    properties.add("pos.development-terminal", () -> TERMINAL.substring(12));
  }

  @Autowired MockMvc http;
  @Autowired org.springframework.boot.test.web.client.TestRestTemplate realHttp;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate runtime;
  @Autowired LocalOperations operations;
  @Autowired StaffService staff;
  @Autowired CommandJournal journal;
  @Autowired za.co.onlinepos.server.local.ModuleAccess modules;
  @Autowired za.co.onlinepos.server.local.AdministrationService administration;
  @Autowired za.co.onlinepos.server.local.AuditService audit;
  @Autowired za.co.onlinepos.server.local.AuditPolicyService auditPolicy;
  private static final String PIN_KEY = java.util.Base64.getEncoder().encodeToString(new byte[32]);
  private StaffService.Actor manager;
  private String token;

  // Schema-owner-only disposable fixture reset, never an application API or runtime privilege.
  private static void truncateAuditFixtures(String sql) {
    ADMIN.execute("ALTER TABLE pos.audit_record DISABLE TRIGGER immutable_audit_truncate");
    try {
      ADMIN.execute(sql);
    } finally {
      ADMIN.execute("ALTER TABLE pos.audit_record ENABLE TRIGGER immutable_audit_truncate");
    }
  }

  @BeforeEach
  void fixture() {
    truncateAuditFixtures(
        "TRUNCATE pos.catalogue_bootstrap,pos.staff,pos.staff_session,pos.catalog_item,pos.branch_price,pos.local_customer,pos.order_draft,pos.order_line,pos.order_line_extra,pos.payment_metadata,pos.command_receipt,pos.audit_record,pos.outbox_event,pos.inbox_receipt,pos.terminal_printer CASCADE");
    ADMIN.execute("TRUNCATE pos.pin_throttle,pos.rbac_role CASCADE");
    ADMIN.update("INSERT INTO pos.rbac_role VALUES (1,'MANAGER'),(1,'CASHIER')");
    for (var module : za.co.onlinepos.contracts.PosModule.values())
      ADMIN.update("INSERT INTO pos.role_permission VALUES (1,'MANAGER',?)", module.permission());
    ADMIN.update(
        "INSERT INTO pos.role_permission VALUES (1,'MANAGER','rbac.manage'),(1,'MANAGER','staff.manage'),(1,'CASHIER','module.sales_orders')");
    ADMIN.update("UPDATE pos.stream_head SET position=0");
    ADMIN.update("UPDATE pos.audit_tracking_settings SET enabled=true");
    ADMIN.update("UPDATE pos.audit_policy SET revision=0,updated_at=NULL,updated_by_owner_id=NULL");
    ADMIN.update("DELETE FROM pos.audit_approval");
    DatabaseTool.bootstrap(OWNER, IDENTITY, "manager", PASSWORD.toCharArray());
    ADMIN.update(
        "INSERT INTO pos.catalog_item(item_id,branch_id,source_type,source_id,description,definition,definition_revision) VALUES (?,1,'TEST_FIXTURE','fixture','Test item','{}',0)",
        ITEM);
    ADMIN.update(
        "INSERT INTO pos.branch_price(price_id,branch_id,item_id,variant_key,amount,available) VALUES (?,1,?,'plain',25.50,true)",
        PRICE,
        ITEM);
    token =
        (String) staff.login(new StaffService.Login("manager", PASSWORD), TERMINAL).get("token");
    manager = staff.authenticate(token, TERMINAL);
  }

  @Test
  void realServletErrorsPreserveAuthenticationAndPermissionStatusCodes() {
    assertEquals(
        401, realHttp.getForEntity("/api/local/menu", String.class).getStatusCode().value());
    var headers = new org.springframework.http.HttpHeaders();
    headers.setBearerAuth(token);
    var request = new org.springframework.http.HttpEntity<Void>(headers);
    assertEquals(
        200,
        realHttp
            .exchange(
                "/api/local/menu", org.springframework.http.HttpMethod.GET, request, String.class)
            .getStatusCode()
            .value());
    assertEquals(
        403,
        realHttp
            .exchange(
                "/api/local/branches/2/menu",
                org.springframework.http.HttpMethod.GET,
                request,
                String.class)
            .getStatusCode()
            .value());
    assertEquals(
        403,
        realHttp
            .exchange(
                "/api/local/orders", org.springframework.http.HttpMethod.GET, request, String.class)
            .getStatusCode()
            .value());
  }

  @Test
  void migrationsAreRepeatableBoundToInstallationAndRuntimeCannotChangeSchemaOrAudit() {
    DatabaseTool.migrate(OWNER, IDENTITY);
    Integer checksum =
        ADMIN.queryForObject(
            "SELECT checksum FROM public.flyway_schema_history WHERE version='1'", Integer.class);
    try {
      ADMIN.update("UPDATE public.flyway_schema_history SET checksum=0 WHERE version='1'");
      assertThrows(
          org.flywaydb.core.api.FlywayException.class, () -> DatabaseTool.migrate(OWNER, IDENTITY));
    } finally {
      ADMIN.update(
          "UPDATE public.flyway_schema_history SET checksum=? WHERE version='1'", checksum);
    }
    assertThrows(
        IllegalStateException.class,
        () ->
            DatabaseTool.migrate(
                OWNER, new LocalDatabase.Identity(2, UUID.randomUUID(), UUID.randomUUID())));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> runtime.execute("CREATE TABLE pos.forbidden(id int)"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> runtime.update("UPDATE pos.audit_record SET action='tampered'"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> runtime.update("DELETE FROM pos.outbox_event"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            runtime.update(
                "INSERT INTO pos.local_customer(customer_id,branch_id,display_name) VALUES (?,2,'wrong branch')",
                UUID.randomUUID()));
    assertEquals(
        6,
        ADMIN.queryForObject(
            "SELECT count(*) FROM public.flyway_schema_history WHERE success", Integer.class));
    assertThrows(
        IllegalStateException.class,
        () -> DatabaseTool.bootstrap(OWNER, IDENTITY, "another", PASSWORD.toCharArray()));
  }

  @Test
  void httpRequiresStaffBranchAndPermissionsAndLoginIsThrottled() throws Exception {
    http.perform(get("/api/local/branches/1/menu")).andExpect(status().isUnauthorized());
    http.perform(get("/api/local/branches/2/menu").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    http.perform(get("/api/local/branches/1/menu").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].amount").value(25.50));
    http.perform(
            post("/api/local/orders")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    http.perform(
            post("/api/local/auth/login")
                .contentType("application/json")
                .content(json.writeValueAsString(new StaffService.Login("manager", PASSWORD))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("MANAGER"));
    http.perform(get("/api/local/menu").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/local/staff")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        new StaffService.StaffEdit(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            "invalid",
                            null,
                            true,
                            0,
                            PASSWORD))))
        .andExpect(status().isBadRequest());
    UUID cashier = UUID.randomUUID();
    staff.edit(
        manager,
        new StaffService.StaffEdit(
            UUID.randomUUID(), cashier, "cashier", "CASHIER", true, 0, PASSWORD));
    String cashierToken =
        (String) staff.login(new StaffService.Login("cashier", PASSWORD), TERMINAL).get("token");
    http.perform(
            patch("/api/local/branches/1/prices/" + PRICE)
                .header("Authorization", "Bearer " + cashierToken)
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        new LocalCommands.PriceEdit(
                            UUID.randomUUID(), 0, new BigDecimal("30.00"), true))))
        .andExpect(status().isForbidden());
    assertThrows(LocalFailure.class, () -> staff.authenticate(token, "another-terminal"));
    for (int n = 0; n < 5; n++)
      assertThrows(
          LocalFailure.class,
          () -> staff.login(new StaffService.Login("cashier", "incorrect-value"), TERMINAL));
    assertThrows(
        LocalFailure.class,
        () -> staff.login(new StaffService.Login("cashier", PASSWORD), TERMINAL));
    http.perform(
            post("/api/local/auth/logout")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk());
    assertThrows(LocalFailure.class, () -> staff.authenticate(token, TERMINAL));
  }

  @Test
  void retryIsIdempotentPayloadChangesConflictAndPricesRequireExactCents() {
    var edit = new LocalCommands.PriceEdit(UUID.randomUUID(), 0, new BigDecimal("30.00"), true);
    var first = operations.price(manager, PRICE, edit);
    assertEquals(first, operations.price(manager, PRICE, edit));
    assertThrows(
        LocalFailure.class,
        () ->
            operations.price(
                manager,
                PRICE,
                new LocalCommands.PriceEdit(edit.commandId(), 0, new BigDecimal("31.00"), true)));
    assertThrows(
        LocalFailure.class,
        () ->
            operations.price(
                manager,
                PRICE,
                new LocalCommands.PriceEdit(UUID.randomUUID(), 0, new BigDecimal("32.00"), true)));
    assertThrows(
        ArithmeticException.class,
        () ->
            operations.price(
                manager,
                PRICE,
                new LocalCommands.PriceEdit(UUID.randomUUID(), 1, new BigDecimal("32.001"), true)));
    assertThrows(
        LocalFailure.class,
        () ->
            operations.price(
                manager,
                PRICE,
                new LocalCommands.PriceEdit(
                    UUID.randomUUID(), 1, new BigDecimal("1e999999999"), true)));
    assertEquals(1, runtime.queryForObject("SELECT count(*) FROM pos.outbox_event", Integer.class));
    assertEquals(
        1, runtime.queryForObject("SELECT count(*) FROM pos.command_receipt", Integer.class));
  }

  @Test
  void concurrentPriceChangesHaveOneWinnerAndMonotonicCommittedStream() throws Exception {
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      Callable<Integer> change =
          () -> {
            try {
              operations.price(
                  manager,
                  PRICE,
                  new LocalCommands.PriceEdit(UUID.randomUUID(), 0, new BigDecimal("26.00"), true));
              return 200;
            } catch (LocalFailure failure) {
              return failure.status();
            }
          };
      var results = pool.invokeAll(List.of(change, change));
      assertEquals(
          List.of(200, 409),
          results.stream()
              .map(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  })
              .sorted()
              .toList());
    }
    assertEquals(1L, runtime.queryForObject("SELECT position FROM pos.stream_head", Long.class));
    assertEquals(
        1L,
        runtime.queryForObject("SELECT max(stream_position) FROM pos.outbox_event", Long.class));
  }

  @Test
  void draftsKeepOriginalUuidAndPricesWithoutBecomingSales() {
    var create =
        new LocalCommands.CreateDraft(
            UUID.randomUUID(),
            UUID.randomUUID(),
            List.of(new LocalCommands.DraftLine(UUID.randomUUID(), PRICE, 0, 2)));
    JsonNode first = operations.createDraft(manager, create);
    assertEquals(0, new BigDecimal("51.00").compareTo(first.get("total").decimalValue()));
    assertEquals("DRAFT", first.get("state").asText());
    operations.price(
        manager,
        PRICE,
        new LocalCommands.PriceEdit(UUID.randomUUID(), 0, new BigDecimal("100.00"), true));
    assertEquals(first, operations.createDraft(manager, create));
    assertEquals(
        0,
        new BigDecimal("51.00")
            .compareTo(operations.draft(create.orderId()).get("total").decimalValue()));
    assertThrows(
        LocalFailure.class,
        () ->
            operations.createDraft(
                manager,
                new LocalCommands.CreateDraft(
                    UUID.randomUUID(), UUID.randomUUID(), create.lines())));
    var cancel = new LocalCommands.CancelDraft(UUID.randomUUID(), 0);
    assertEquals(
        operations.cancel(manager, create.orderId(), cancel),
        operations.cancel(manager, create.orderId(), cancel));
    assertThrows(
        LocalFailure.class,
        () ->
            operations.cancel(
                manager, create.orderId(), new LocalCommands.CancelDraft(UUID.randomUUID(), 0)));
    assertEquals(
        0, runtime.queryForObject("SELECT count(*) FROM pos.payment_metadata", Integer.class));
  }

  @Test
  void staffEditsInvalidateSessionsAndCannotRemoveLastManagerOrLeakVerifiers() throws Exception {
    assertThrows(
        LocalFailure.class,
        () ->
            staff.edit(
                manager,
                new StaffService.StaffEdit(
                    UUID.randomUUID(), manager.staffId(), "manager", "CASHIER", true, 0, null)));
    UUID id = UUID.randomUUID();
    UUID retryId = UUID.randomUUID();
    var command =
        new StaffService.StaffEdit(
            UUID.randomUUID(), retryId, "retry.staff", "CASHIER", true, 0, PASSWORD);
    assertEquals(staff.edit(manager, command), staff.edit(manager, command));
    assertEquals(
        0L,
        runtime.queryForObject(
            "SELECT revision FROM pos.staff WHERE staff_id=?", Long.class, retryId));
    assertThrows(
        LocalFailure.class,
        () ->
            staff.edit(
                manager,
                new StaffService.StaffEdit(
                    command.commandId(), retryId, "changed.staff", "CASHIER", true, 0, PASSWORD)));
    staff.edit(
        manager,
        new StaffService.StaffEdit(UUID.randomUUID(), id, "cashier", "CASHIER", true, 0, PASSWORD));
    String session =
        (String) staff.login(new StaffService.Login("cashier", PASSWORD), TERMINAL).get("token");
    StaffService.Actor staleActor = staff.authenticate(session, TERMINAL);
    staff.edit(
        manager,
        new StaffService.StaffEdit(UUID.randomUUID(), id, "cashier", "CASHIER", false, 0, null));
    assertThrows(LocalFailure.class, () -> staff.authenticate(session, TERMINAL));
    assertThrows(
        LocalFailure.class,
        () ->
            operations.createDraft(
                staleActor,
                new LocalCommands.CreateDraft(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    List.of(new LocalCommands.DraftLine(UUID.randomUUID(), PRICE, 0, 1)))));
    String result =
        http.perform(get("/api/local/staff").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(result.contains("password"));
    assertFalse(result.contains(PASSWORD));
    assertNotEquals(
        ADMIN.queryForObject(
            "SELECT password_hash FROM pos.staff WHERE username='manager'", String.class),
        ADMIN.queryForObject(
            "SELECT password_hash FROM pos.staff WHERE username='cashier'", String.class));
    assertFalse(
        ADMIN
            .queryForObject(
                "SELECT string_agg(payload::text,'') FROM pos.outbox_event", String.class)
            .contains(PASSWORD));
  }

  @Test
  void failedOutboxInsertRollsBackBusinessStateAuditAndCommandReceipt() {
    ADMIN.execute(
        "CREATE FUNCTION pos.fail_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected failure'; END $$");
    ADMIN.execute(
        "CREATE TRIGGER fail_outbox BEFORE INSERT ON pos.outbox_event FOR EACH ROW EXECUTE FUNCTION pos.fail_outbox()");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              operations.price(
                  manager,
                  PRICE,
                  new LocalCommands.PriceEdit(
                      UUID.randomUUID(), 0, new BigDecimal("99.00"), true)));
      assertEquals(
          new BigDecimal("25.50"),
          runtime.queryForObject("SELECT amount FROM pos.branch_price", BigDecimal.class));
      assertEquals(
          0, runtime.queryForObject("SELECT count(*) FROM pos.command_receipt", Integer.class));
      assertEquals(
          0,
          runtime.queryForObject(
              "SELECT count(*) FROM pos.audit_record WHERE action='PRICE_CHANGED'", Integer.class));
      assertEquals(0L, runtime.queryForObject("SELECT position FROM pos.stream_head", Long.class));
    } finally {
      ADMIN.execute("DROP TRIGGER fail_outbox ON pos.outbox_event");
      ADMIN.execute("DROP FUNCTION pos.fail_outbox()");
    }
  }

  @Test
  void postgresDumpRestoresIdentityVerifiersAndDurableCommandHistoryIntoIsolatedDatabase()
      throws Exception {
    String bin = System.getenv("POS_TEST_PG_BIN");
    assertNotNull(bin, "CI must configure real PostgreSQL dump/restore binaries");
    operations.price(
        manager,
        PRICE,
        new LocalCommands.PriceEdit(UUID.randomUUID(), 0, new BigDecimal("28.00"), true));
    java.net.URI uri = java.net.URI.create(System.getenv("POS_TEST_DATABASE_URL").substring(5));
    var file = java.nio.file.Files.createTempFile("pos-stage3-restore-", ".dump");
    try {
      run(bin, "pg_dump", uri, List.of("-d", "pos_stage3_test", "-Fc", "-f", file.toString()));
      run(
          bin,
          "pg_restore",
          uri,
          List.of("-d", "pos_stage3_restore", "--clean", "--if-exists", file.toString()));
      var restored =
          new JdbcTemplate(
              DatabaseTool.source(
                  "jdbc:postgresql://"
                      + uri.getHost()
                      + ":"
                      + uri.getPort()
                      + "/pos_stage3_restore",
                  "pos_owner",
                  System.getenv("POS_TEST_OWNER_PASSWORD")));
      assertEquals(
          IDENTITY.clusterId(),
          restored.queryForObject("SELECT cluster_id FROM pos.store_identity", UUID.class));
      assertEquals(
          1, restored.queryForObject("SELECT count(*) FROM pos.command_receipt", Integer.class));
      assertEquals(
          new BigDecimal("28.00"),
          restored.queryForObject("SELECT amount FROM pos.branch_price", BigDecimal.class));
      assertEquals(
          1, restored.queryForObject("SELECT count(*) FROM pos.outbox_event", Integer.class));
      assertEquals(
          ADMIN.queryForObject(
              "SELECT password_hash FROM pos.staff WHERE username='manager'", String.class),
          restored.queryForObject(
              "SELECT password_hash FROM pos.staff WHERE username='manager'", String.class));
    } finally {
      java.nio.file.Files.deleteIfExists(file);
    }
  }

  @Test
  void fullCentPrecisionSurvivesJsonAndCommandReplayBeyondDoubleIntegerRange() {
    BigDecimal amount = new BigDecimal("9007199254740991.01");
    var edit = new LocalCommands.PriceEdit(UUID.randomUUID(), 0, amount, true);
    var first = operations.price(manager, PRICE, edit);
    assertEquals(0, amount.compareTo(first.get("amount").decimalValue()));
    assertEquals(first, operations.price(manager, PRICE, edit));
    assertEquals(
        amount, runtime.queryForObject("SELECT amount FROM pos.branch_price", BigDecimal.class));
  }

  @Test
  void registeredTlsTerminalAndStaffTokenAreBothRequiredAndRevocationTakesEffect(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
    var caKey = za.co.onlinepos.installation.Certificates.keyPair();
    var ca =
        za.co.onlinepos.installation.Certificates.issue(
            "Test CA", caKey.getPublic(), null, caKey.getPrivate(), true, false, List.of());
    var values = new java.util.HashMap<String, byte[]>();
    values.put("ca-key", caKey.getPrivate().getEncoded());
    values.put(
        "ca-certificate",
        za.co.onlinepos.installation.Certificates.encode(ca)
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    var secrets =
        new za.co.onlinepos.installation.SecretStore() {
          public void put(String name, byte[] value) {
            values.put(name, value);
          }

          public byte[] get(String name) {
            return values.get(name);
          }
        };
    var installation =
        new za.co.onlinepos.installation.Installation(
            1,
            IDENTITY.installationId(),
            IDENTITY.clusterId(),
            UUID.randomUUID(),
            1,
            za.co.onlinepos.contracts.InstallationRole.MASTER,
            "Test Master",
            "INSTALLED");
    var registry =
        new za.co.onlinepos.installation.TerminalRegistry(
            directory, java.time.Clock.systemUTC(), installation, secrets);
    var invite = registry.invite("https://localhost:8443");
    UUID terminal = UUID.randomUUID();
    var key = za.co.onlinepos.installation.Certificates.keyPair();
    var signature = java.security.Signature.getInstance("SHA256withRSA");
    signature.initSign(key.getPrivate());
    signature.update(
        (invite.token() + ":" + terminal).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var reply =
        registry.redeem(
            new za.co.onlinepos.installation.Pairing.Request(
                IDENTITY.clusterId(),
                1,
                terminal,
                invite.token(),
                java.util.Base64.getEncoder().encodeToString(key.getPublic().getEncoded()),
                java.util.Base64.getEncoder().encodeToString(signature.sign())));
    var certificate = za.co.onlinepos.installation.Certificates.parse(reply.clientCertificate());
    String fingerprint = za.co.onlinepos.installation.Certificates.fingerprint(certificate);
    String bound =
        (String) staff.login(new StaffService.Login("manager", PASSWORD), fingerprint).get("token");
    var terminalFilter = new za.co.onlinepos.server.TerminalAuthenticationFilter(registry);
    var staffFilter = new za.co.onlinepos.server.StaffAuthenticationFilter(staff, null, modules);
    java.util.function.BiFunction<String, Boolean, Integer> call =
        (session, secure) -> {
          var request =
              new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/local/auth/me");
          request.setSecure(secure);
          request.setAttribute(
              "jakarta.servlet.request.X509Certificate",
              new java.security.cert.X509Certificate[] {certificate});
          request.addHeader("Authorization", "Bearer " + session);
          var response = new org.springframework.mock.web.MockHttpServletResponse();
          try {
            terminalFilter.doFilter(
                request,
                response,
                (req, res) ->
                    staffFilter.doFilter(
                        req,
                        res,
                        (authenticated, result) ->
                            assertNotNull(authenticated.getAttribute("pos.staff"))));
            return response.getStatus();
          } catch (Exception exception) {
            throw new IllegalStateException(exception);
          } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
          }
        };
    assertEquals(200, call.apply(bound, true));
    assertEquals(401, call.apply(token, true));
    assertEquals(403, call.apply(bound, false));
    // Exercise the complete installed-style filter chain over real TLS, not only MockMvc.
    var serverKey = za.co.onlinepos.installation.Certificates.keyPair();
    var serverCertificate =
        za.co.onlinepos.installation.Certificates.issue(
            "localhost",
            serverKey.getPublic(),
            ca,
            caKey.getPrivate(),
            false,
            false,
            List.of("localhost"));
    String storePassword = UUID.randomUUID().toString();
    var serverStore = directory.resolve("server.p12");
    java.nio.file.Files.write(
        serverStore,
        za.co.onlinepos.installation.Certificates.store(
            serverKey.getPrivate(), serverCertificate, ca, storePassword.toCharArray()));
    var trustStore = directory.resolve("trust.p12");
    java.nio.file.Files.write(
        trustStore,
        za.co.onlinepos.installation.Certificates.store(
            null, null, ca, storePassword.toCharArray()));
    var properties = new java.util.HashMap<String, Object>();
    properties.put("server.port", 0);
    properties.put("server.address", "127.0.0.1");
    properties.put("server.ssl.enabled", true);
    properties.put("server.ssl.client-auth", "want");
    properties.put("server.ssl.key-store", serverStore.toUri().toString());
    properties.put("server.ssl.key-store-password", storePassword);
    properties.put("server.ssl.trust-store", trustStore.toUri().toString());
    properties.put("server.ssl.trust-store-password", storePassword);
    properties.put("pos.database.url", System.getenv("POS_TEST_DATABASE_URL"));
    properties.put("pos.database.password", System.getenv("POS_TEST_RUNTIME_PASSWORD"));
    properties.put("pos.branch-id", 1);
    properties.put("pos.cluster-id", IDENTITY.clusterId().toString());
    properties.put("pos.installation-id", IDENTITY.installationId().toString());
    properties.put("pos.development-terminal", "");
    var application =
        new org.springframework.boot.builder.SpringApplicationBuilder(StoreServerApplication.class)
            .profiles("local-store")
            .initializers(
                context -> {
                  context
                      .getEnvironment()
                      .getPropertySources()
                      .addFirst(
                          new org.springframework.core.env.MapPropertySource(
                              "trusted-lan-test", properties));
                  context.getBeanFactory().registerSingleton("terminalRegistry", registry);
                });
    var clientKeys = java.security.KeyStore.getInstance("PKCS12");
    clientKeys.load(
        new java.io.ByteArrayInputStream(
            za.co.onlinepos.installation.Certificates.store(
                key.getPrivate(), certificate, ca, storePassword.toCharArray())),
        storePassword.toCharArray());
    var keyManagers =
        javax.net.ssl.KeyManagerFactory.getInstance(
            javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(clientKeys, storePassword.toCharArray());
    var trust = java.security.KeyStore.getInstance("PKCS12");
    trust.load(
        new java.io.ByteArrayInputStream(java.nio.file.Files.readAllBytes(trustStore)),
        storePassword.toCharArray());
    var trustManagers =
        javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trust);
    var tls = javax.net.ssl.SSLContext.getInstance("TLS");
    tls.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
    try (var context = application.run();
        var client = java.net.http.HttpClient.newBuilder().sslContext(tls).build()) {
      int port =
          ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)
                  context)
              .getWebServer()
              .getPort();
      var base = java.net.URI.create("https://localhost:" + port);
      var login =
          java.net.http.HttpRequest.newBuilder(base.resolve("/api/local/auth/login"))
              .header("Content-Type", "application/json")
              .POST(
                  java.net.http.HttpRequest.BodyPublishers.ofString(
                      json.writeValueAsString(new StaffService.Login("manager", PASSWORD))))
              .build();
      var httpsReply = client.send(login, java.net.http.HttpResponse.BodyHandlers.ofString());
      assertEquals(200, httpsReply.statusCode(), "Enrolled TLS terminal must be able to log in");
      String staffToken = json.readTree(httpsReply.body()).get("token").asText();
      var request =
          java.net.http.HttpRequest.newBuilder(base.resolve("/api/local/menu"))
              .header("Authorization", "Bearer " + staffToken)
              .GET()
              .build();
      assertEquals(
          200,
          client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
    }
    registry.revoke(terminal);
    assertEquals(403, call.apply(bound, true));
  }

  private static void run(String bin, String executable, java.net.URI uri, List<String> args)
      throws Exception {
    var command =
        new java.util.ArrayList<>(
            List.of(
                java.nio.file.Path.of(
                        bin,
                        executable
                            + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""))
                    .toString(),
                "-h",
                uri.getHost(),
                "-p",
                Integer.toString(uri.getPort()),
                "-U",
                "pos_owner"));
    command.addAll(args);
    var process = new ProcessBuilder(command).redirectErrorStream(true);
    process.environment().put("PGPASSWORD", System.getenv("POS_TEST_OWNER_PASSWORD"));
    var child = process.start();
    String output =
        new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(0, child.exitValue(), output);
  }

  @Autowired za.co.onlinepos.server.catalogue.CatalogueImport catalogue;

  private za.co.onlinepos.contracts.CatalogueSnapshot snapshot(int branch) throws Exception {
    var format = new za.co.onlinepos.server.catalogue.SnapshotFormat();
    java.util.Map<String, java.util.List<java.util.Map<String, Object>>> tables;
    try (var input = getClass().getResourceAsStream("/catalogue-source-fixture.json")) {
      tables = json.readValue(input, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
    tables
        .values()
        .forEach(
            rows ->
                rows.forEach(
                    row -> {
                      if (row.containsKey("branch_id")) row.put("branch_id", branch);
                    }));
    tables.get("branch").getFirst().put("name", branch == 1 ? "Kenridge" : "Uitzicht");
    var initial =
        new za.co.onlinepos.contracts.CatalogueSnapshot(
            1,
            UUID.randomUUID(),
            branch,
            format.SOURCE,
            "2026-10-25T10:00:00Z",
            format.POLICY,
            tables,
            java.util.Map.of(),
            java.util.Map.of());
    return new za.co.onlinepos.contracts.CatalogueSnapshot(
        1,
        initial.snapshotId(),
        branch,
        initial.sourceRevision(),
        initial.capturedAt(),
        initial.pricingPolicy(),
        tables,
        format.counts(initial),
        format.hashes(initial));
  }

  private void emptyCatalogue() {
    ADMIN.execute("TRUNCATE pos.catalogue_bootstrap,pos.catalog_item,pos.branch_price CASCADE");
  }

  @Test
  void catalogueImportsEverySourceTableAndExactPriceBaselinesWithSafeReplay() throws Exception {
    emptyCatalogue();
    var format = new za.co.onlinepos.server.catalogue.SnapshotFormat();
    var snapshot = snapshot(1);
    var command =
        new za.co.onlinepos.contracts.CatalogueSnapshot.Import(
            UUID.randomUUID(), format.fingerprint(snapshot), snapshot);
    var result = catalogue.apply(manager, 1, command);
    assertEquals("LOCAL", result.get("priceAuthority").asText());
    assertEquals(result, catalogue.apply(manager, 1, command));
    assertEquals(
        result,
        catalogue.apply(
            manager,
            1,
            new za.co.onlinepos.contracts.CatalogueSnapshot.Import(
                UUID.randomUUID(), command.expectedSha256(), snapshot)));
    assertEquals(
        1,
        runtime.queryForObject(
            "SELECT count(*) FROM pos.outbox_event WHERE event_type='CATALOGUE_BOOTSTRAPPED'",
            Integer.class));
    assertEquals(9, runtime.queryForObject("SELECT count(*) FROM pos.branch_price", Integer.class));
    var read = catalogue.catalogue(1);
    assertEquals(format.tree(snapshot), read.get("snapshot"));
    assertEquals(31, read.get("snapshot").get("tables").size());
    assertEquals(
        new BigDecimal("85.50"),
        runtime.queryForObject(
            "SELECT p.amount FROM pos.branch_price p JOIN pos.catalog_item c USING(item_id) WHERE c.source_type='branch_pizza_price'",
            BigDecimal.class));
    assertEquals(
        new BigDecimal("110.00"),
        runtime.queryForObject(
            "SELECT p.amount FROM pos.branch_price p JOIN pos.catalog_item c USING(item_id) WHERE c.source_type='special'",
            BigDecimal.class));
    UUID price =
        za.co.onlinepos.server.catalogue.CatalogueImport.stable(
            1, "price", "branch_pizza_price", "[1,1,1]");
    ADMIN.update("INSERT INTO pos.role_permission VALUES (1,'MANAGER','catalogue.manage')");
    provisionPin("manager", "012345");
    String access = enter("012345", za.co.onlinepos.contracts.PosModule.CATALOGUE);
    var priceRow = editor.state(access, TERMINAL).path("tables").path("branch_pizza_price").get(0);
    var priceValues = catalogueValues(priceRow);
    priceValues.put("price", new BigDecimal("90.00"));
    editor.edit(
        access,
        TERMINAL,
        catalogueEdit(access, "branch_pizza_price", priceRow, priceValues, false, false));
    token =
        (String) staff.login(new StaffService.Login("manager", PASSWORD), TERMINAL).get("token");
    manager = staff.authenticate(token, TERMINAL);
    catalogue.apply(manager, 1, command);
    assertEquals(
        new BigDecimal("90.00"),
        runtime.queryForObject(
            "SELECT amount FROM pos.branch_price WHERE price_id=?", BigDecimal.class, price));
    var different = snapshot(1);
    assertEquals(
        409,
        assertThrows(
                LocalFailure.class,
                () ->
                    catalogue.apply(
                        manager,
                        1,
                        new za.co.onlinepos.contracts.CatalogueSnapshot.Import(
                            UUID.randomUUID(), format.fingerprint(different), different)))
            .status());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> runtime.update("UPDATE pos.catalogue_bootstrap SET sha256='bad'"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            runtime.update("INSERT INTO pos.catalog_item SELECT * FROM pos.catalog_item LIMIT 1"));
    http.perform(get("/api/local/branches/1/catalogue").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    http.perform(get("/api/local/branches/2/catalogue").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  void catalogueRejectsCorruptionForeignBranchesAndExistingDataWithoutMutation() throws Exception {
    var format = new za.co.onlinepos.server.catalogue.SnapshotFormat();
    var snapshot = snapshot(1);
    var command =
        new za.co.onlinepos.contracts.CatalogueSnapshot.Import(
            UUID.randomUUID(), format.fingerprint(snapshot), snapshot);
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> catalogue.apply(manager, 1, command));
    assertEquals(
        0, runtime.queryForObject("SELECT count(*) FROM pos.catalogue_bootstrap", Integer.class));
    emptyCatalogue();
    var foreign = snapshot(2);
    format.validate(foreign); // both branch contracts validate independently
    assertEquals(
        403,
        assertThrows(
                LocalFailure.class,
                () ->
                    catalogue.apply(
                        manager,
                        1,
                        new za.co.onlinepos.contracts.CatalogueSnapshot.Import(
                            UUID.randomUUID(), format.fingerprint(foreign), foreign)))
            .status());
    assertEquals(
        400,
        assertThrows(
                LocalFailure.class,
                () ->
                    catalogue.apply(
                        manager,
                        1,
                        new za.co.onlinepos.contracts.CatalogueSnapshot.Import(
                            UUID.randomUUID(), "0".repeat(64), snapshot)))
            .status());
    snapshot.tables().get("menu_item").getFirst().put("category_id", 999);
    assertThrows(IllegalArgumentException.class, () -> format.validate(snapshot));
    var invalid = snapshot(1);
    invalid.tables().get("branch_pizza_price").getFirst().put("price", new BigDecimal("12.001"));
    assertThrows(ArithmeticException.class, () -> format.validate(invalid));
    var missing = snapshot(1);
    missing.tables().remove("special_day");
    assertThrows(IllegalArgumentException.class, () -> format.validate(missing));
    assertEquals(
        0, runtime.queryForObject("SELECT count(*) FROM pos.catalogue_bootstrap", Integer.class));
    assertEquals(0, runtime.queryForObject("SELECT count(*) FROM pos.outbox_event", Integer.class));
  }

  @Test
  void catalogueRequiresManagerAndRollsBackEverythingWhenOutboxFails() throws Exception {
    emptyCatalogue();
    var format = new za.co.onlinepos.server.catalogue.SnapshotFormat();
    var snapshot = snapshot(1);
    var command =
        new za.co.onlinepos.contracts.CatalogueSnapshot.Import(
            UUID.randomUUID(), format.fingerprint(snapshot), snapshot);
    var cashier =
        new StaffService.Actor(
            manager.staffId(), "CASHIER", manager.terminal(), manager.credentialVersion());
    assertEquals(
        403, assertThrows(LocalFailure.class, () -> catalogue.apply(cashier, 1, command)).status());
    http.perform(
            post("/api/local/branches/1/catalogue/bootstrap")
                .contentType("application/json")
                .content(json.writeValueAsBytes(command)))
        .andExpect(status().isUnauthorized());
    ADMIN.execute(
        "ALTER TABLE pos.outbox_event ADD CONSTRAINT stage4_failure CHECK(event_type<>'CATALOGUE_BOOTSTRAPPED')");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> catalogue.apply(manager, 1, command));
      assertEquals(
          0, runtime.queryForObject("SELECT count(*) FROM pos.catalogue_bootstrap", Integer.class));
      assertEquals(
          0, runtime.queryForObject("SELECT count(*) FROM pos.catalog_item", Integer.class));
      assertEquals(
          0, runtime.queryForObject("SELECT count(*) FROM pos.branch_price", Integer.class));
      assertEquals(
          0, runtime.queryForObject("SELECT count(*) FROM pos.command_receipt", Integer.class));
      assertEquals(
          0, runtime.queryForObject("SELECT position FROM pos.stream_head", Integer.class));
    } finally {
      ADMIN.execute("ALTER TABLE pos.outbox_event DROP CONSTRAINT stage4_failure");
    }
    http.perform(
            post("/api/local/branches/1/catalogue/bootstrap")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(json.writeValueAsBytes(command)))
        .andExpect(status().isOk());
  }

  @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

  @Test
  void sourceSeedSnapshotsForBothBranchesRoundTripWithExactEffectivePrices() throws Exception {
    var format = new za.co.onlinepos.server.catalogue.SnapshotFormat();
    try {
      for (int branch : new int[] {1, 2}) {
        // Only the dedicated disposable test database is touched; rebind after removing fixtures.
        truncateAuditFixtures("TRUNCATE pos.store_identity CASCADE");
        var identity =
            new LocalDatabase.Identity(branch, IDENTITY.clusterId(), IDENTITY.installationId());
        ADMIN.update(
            "INSERT INTO pos.store_identity(branch_id,cluster_id,installation_id) VALUES (?,?,?)",
            branch,
            identity.clusterId(),
            identity.installationId());
        ADMIN.update("INSERT INTO pos.stream_head(branch_id) VALUES (?)", branch);
        DatabaseTool.bootstrap(OWNER, identity, "manager", PASSWORD.toCharArray());
        var actor =
            new StaffService.Actor(
                ADMIN.queryForObject("SELECT staff_id FROM pos.staff", UUID.class),
                "MANAGER",
                TERMINAL,
                0);
        var branchJournal = new CommandJournal(runtime, transactions, json, identity);
        var importer =
            new za.co.onlinepos.server.catalogue.CatalogueImport(runtime, branchJournal, identity);
        za.co.onlinepos.contracts.CatalogueSnapshot snapshot;
        String externalSnapshots = System.getenv("POS_TEST_CATALOGUE_SNAPSHOT_DIR");
        String filename = "cloud-seed-branch" + branch + ".json";
        try (var input =
            externalSnapshots == null
                ? getClass().getResourceAsStream("/" + filename)
                : java.nio.file.Files.newInputStream(
                    java.nio.file.Path.of(externalSnapshots).resolve(filename))) {
          snapshot = json.readValue(input, za.co.onlinepos.contracts.CatalogueSnapshot.class);
        }
        format.validate(snapshot);
        var command =
            new za.co.onlinepos.contracts.CatalogueSnapshot.Import(
                UUID.randomUUID(), format.fingerprint(snapshot), snapshot);
        importer.apply(actor, branch, command);
        assertEquals(format.tree(snapshot), importer.catalogue(branch).get("snapshot"));
        int expected = 0;
        for (var entry :
            java.util.Map.of(
                    "branch_menu_item_price",
                    "price",
                    "branch_pizza_price",
                    "price",
                    "branch_extra_price",
                    "price",
                    "branch_pizza_base_option_price",
                    "price",
                    "branch_burger_component_price",
                    "price",
                    "modifier_option",
                    "additional_price",
                    "salad_ingredients",
                    "price",
                    "special",
                    "bundle_price",
                    "special_addon",
                    "price")
                .entrySet()) {
          for (var row : format.rows(snapshot, entry.getKey())) {
            UUID price =
                za.co.onlinepos.server.catalogue.CatalogueImport.stable(
                    branch, "price", entry.getKey(), format.key(entry.getKey(), row));
            assertEquals(
                0,
                za.co.onlinepos.server.catalogue.SnapshotFormat.money(row.get(entry.getValue()))
                    .compareTo(
                        runtime.queryForObject(
                            "SELECT amount FROM pos.branch_price WHERE price_id=? AND branch_id=?",
                            BigDecimal.class,
                            price,
                            branch)));
            expected++;
          }
        }
        if (externalSnapshots == null) assertEquals(branch == 1 ? 241 : 237, expected);
        else assertTrue(expected > 0, "External snapshot must contain priced records");
        assertEquals(
            expected,
            runtime.queryForObject("SELECT count(*) FROM pos.branch_price", Integer.class));
        assertEquals(
            1, runtime.queryForObject("SELECT count(*) FROM pos.outbox_event", Integer.class));
        assertEquals(
            403,
            assertThrows(LocalFailure.class, () -> importer.catalogue(branch == 1 ? 2 : 1))
                .status());
      }
    } finally {
      truncateAuditFixtures("TRUNCATE pos.store_identity CASCADE");
      ADMIN.update(
          "INSERT INTO pos.store_identity(branch_id,cluster_id,installation_id) VALUES (?,?,?)",
          IDENTITY.branchId(),
          IDENTITY.clusterId(),
          IDENTITY.installationId());
      ADMIN.update("INSERT INTO pos.stream_head(branch_id) VALUES (1)");
    }
  }

  private void provisionPin(String username, String pin) {
    za.co.onlinepos.server.local.RbacProvisioning.apply(
        OWNER, IDENTITY, "set-pin", username, java.util.Set.of(), pin.toCharArray(), PIN_KEY);
  }

  private String enter(String pin, za.co.onlinepos.contracts.PosModule module) {
    return (String)
        modules
            .enter(new za.co.onlinepos.server.local.ModuleAccess.Entry(pin, module), TERMINAL)
            .get("token");
  }

  @Test
  void modulePinLeadingZerosBoundariesAndLogout() throws Exception {
    provisionPin("manager", "012345");
    http.perform(
            post("/api/local/modules/enter")
                .contentType("application/json")
                .content("{\"pin\":\"012345\",\"module\":\"REPORTS\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("manager"));
    String moduleToken = enter("012345", za.co.onlinepos.contracts.PosModule.REPORTS);
    var session = modules.authenticate(moduleToken, TERMINAL, false);
    assertEquals("manager", session.username());
    assertEquals(za.co.onlinepos.contracts.PosModule.REPORTS, session.module());
    assertEquals(
        401,
        assertThrows(
                LocalFailure.class,
                () -> modules.authenticate(moduleToken, "other-terminal", false))
            .status());
    http.perform(get("/api/local/modules/me").header("Authorization", "Bearer " + moduleToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.module").value("REPORTS"));
    http.perform(get("/api/local/menu").header("Authorization", "Bearer " + moduleToken))
        .andExpect(status().isUnauthorized());
    http.perform(get("/api/local/modules/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    http.perform(
            post("/api/local/modules/logout")
                .content("{}")
                .header("Authorization", "Bearer " + moduleToken))
        .andExpect(status().isOk());
    assertThrows(LocalFailure.class, () -> modules.authenticate(moduleToken, TERMINAL, false));
    assertEquals(
        401,
        assertThrows(
                LocalFailure.class,
                () -> enter("12345", za.co.onlinepos.contracts.PosModule.REPORTS))
            .status());
    assertTrue(
        ADMIN.queryForObject(
                "SELECT count(*) FROM pos.audit_record WHERE details::text LIKE '%012345%'",
                Integer.class)
            == 0);
  }

  @Test
  void modulePinPermissionDenialDuplicateAndImmediateRevocation() throws Exception {
    UUID cashier = UUID.randomUUID();
    staff.edit(
        manager,
        new StaffService.StaffEdit(
            UUID.randomUUID(), cashier, "cashier", "CASHIER", true, 0, PASSWORD));
    provisionPin("manager", "012345");
    provisionPin("cashier", "023456");
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> provisionPin("cashier", "012345"));
    assertEquals(
        403,
        assertThrows(
                LocalFailure.class,
                () -> enter("023456", za.co.onlinepos.contracts.PosModule.REPORTS))
            .status());
    String access = enter("023456", za.co.onlinepos.contracts.PosModule.SALES_ORDERS);
    za.co.onlinepos.server.local.RbacProvisioning.apply(
        OWNER,
        IDENTITY,
        "set-role",
        "CUSTOM",
        java.util.Set.of("module.sales_orders", "module.reports"),
        new char[0],
        PIN_KEY);
    za.co.onlinepos.server.local.RbacProvisioning.apply(
        OWNER,
        IDENTITY,
        "assign-roles",
        "cashier",
        java.util.Set.of("CASHIER", "CUSTOM"),
        new char[0],
        PIN_KEY);
    assertThrows(LocalFailure.class, () -> modules.authenticate(access, TERMINAL, false));
    String report = enter("023456", za.co.onlinepos.contracts.PosModule.REPORTS);
    assertTrue(
        modules
            .authenticate(report, TERMINAL, false)
            .permissions()
            .contains("module.sales_orders"));
    za.co.onlinepos.server.local.RbacProvisioning.apply(
        OWNER,
        IDENTITY,
        "set-role",
        "CUSTOM",
        java.util.Set.of("module.sales_orders"),
        new char[0],
        PIN_KEY);
    assertThrows(LocalFailure.class, () -> modules.authenticate(report, TERMINAL, false));
    assertEquals(
        403,
        assertThrows(
                LocalFailure.class,
                () -> enter("023456", za.co.onlinepos.contracts.PosModule.REPORTS))
            .status());
    String sales = enter("023456", za.co.onlinepos.contracts.PosModule.SALES_ORDERS);
    ADMIN.update("UPDATE pos.staff SET enabled=false WHERE staff_id=?", cashier);
    assertThrows(LocalFailure.class, () -> modules.authenticate(sales, TERMINAL, false));
    assertEquals(
        401,
        assertThrows(
                LocalFailure.class,
                () -> enter("023456", za.co.onlinepos.contracts.PosModule.SALES_ORDERS))
            .status());
  }

  @Test
  void modulePinThrottlingIsDurableAcrossTerminalsAndExpiryIsEnforced() {
    provisionPin("manager", "012345");
    for (int i = 0; i < 5; i++)
      assertEquals(
          401,
          assertThrows(
                  LocalFailure.class,
                  () -> enter("999999", za.co.onlinepos.contracts.PosModule.REPORTS))
              .status());
    assertEquals(
        429,
        assertThrows(
                LocalFailure.class,
                () -> enter("012345", za.co.onlinepos.contracts.PosModule.REPORTS))
            .status());
    String other =
        (String)
            modules
                .enter(
                    new za.co.onlinepos.server.local.ModuleAccess.Entry(
                        "012345", za.co.onlinepos.contracts.PosModule.REPORTS),
                    "different")
                .get("token");
    ADMIN.update("UPDATE pos.module_session SET expires_at=clock_timestamp()-interval '1 second'");
    assertThrows(LocalFailure.class, () -> modules.authenticate(other, "different", true));
    ADMIN.execute("TRUNCATE pos.pin_throttle");
    for (int i = 0; i < 30; i++) {
      final String terminal = "guess-" + i;
      assertEquals(
          401,
          assertThrows(
                  LocalFailure.class,
                  () ->
                      modules.enter(
                          new za.co.onlinepos.server.local.ModuleAccess.Entry(
                              "999999", za.co.onlinepos.contracts.PosModule.REPORTS),
                          terminal))
              .status());
    }
    assertEquals(
        429,
        assertThrows(
                LocalFailure.class,
                () ->
                    modules.enter(
                        new za.co.onlinepos.server.local.ModuleAccess.Entry(
                            "012345", za.co.onlinepos.contracts.PosModule.REPORTS),
                        "fresh-terminal"))
            .status());
    ADMIN.update(
        "UPDATE pos.pin_throttle SET window_start=clock_timestamp()-interval '6 minutes',locked_until=clock_timestamp()-interval '1 second'");
    String recovered = enter("012345", za.co.onlinepos.contracts.PosModule.REPORTS);
    assertNotNull(modules.authenticate(recovered, TERMINAL, false));
  }

  @Test
  void finalRbacAdministratorProtectedAndRuntimeCannotProvisionPrivileges() {
    assertThrows(
        org.springframework.transaction.TransactionSystemException.class,
        () ->
            za.co.onlinepos.server.local.RbacProvisioning.apply(
                OWNER,
                IDENTITY,
                "assign-roles",
                "manager",
                java.util.Set.of("CASHIER"),
                new char[0],
                PIN_KEY));
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> ADMIN.update("UPDATE pos.staff SET enabled=false"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            runtime.update(
                "INSERT INTO pos.staff_role SELECT branch_id,staff_id,'CASHIER' FROM pos.staff"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> runtime.update("UPDATE pos.role_permission SET permission='rbac.manage'"));
    // Password recovery remains valid after additive migrations.
    assertNotNull(
        staff.authenticate(
            (String)
                staff.login(new StaffService.Login("manager", PASSWORD), TERMINAL).get("token"),
            TERMINAL));
  }

  @Test
  void passwordRecoveryCannotBypassConfiguredStaffManagementPermission() throws Exception {
    var grants = new java.util.TreeSet<>(modules.permissions(manager.staffId()));
    grants.remove("staff.manage");
    za.co.onlinepos.server.local.RbacProvisioning.apply(
        OWNER, IDENTITY, "set-role", "MANAGER", grants, new char[0], PIN_KEY);
    assertThrows(LocalFailure.class, () -> staff.authenticate(token, TERMINAL));
    String fresh =
        (String) staff.login(new StaffService.Login("manager", PASSWORD), TERMINAL).get("token");
    http.perform(get("/api/local/staff").header("Authorization", "Bearer " + fresh))
        .andExpect(status().isForbidden());
    assertEquals(
        403,
        assertThrows(LocalFailure.class, () -> staff.list(staff.authenticate(fresh, TERMINAL)))
            .status());
  }

  private String adminToken(boolean designateOwner) {
    provisionPin("manager", "012345");
    if (designateOwner)
      za.co.onlinepos.server.local.RbacProvisioning.apply(
          OWNER, IDENTITY, "set-owner", "manager", java.util.Set.of(), new char[0], PIN_KEY);
    return enter("012345", za.co.onlinepos.contracts.PosModule.ADMINISTRATION);
  }

  private JsonNode adminRow(String access, UUID id) {
    return json.valueToTree(administration.staff(access, TERMINAL, "", 0))
        .path("items")
        .valueStream()
        .filter(row -> row.path("staffId").asText().equals(id.toString()))
        .findFirst()
        .orElseThrow();
  }

  private UUID adminCreate(String access, String username, java.util.Set<String> roles) {
    UUID id = UUID.randomUUID();
    administration.editStaff(
        access,
        TERMINAL,
        new za.co.onlinepos.contracts.Administration.StaffEdit(
            UUID.randomUUID(), id, username, true, roles, -1));
    return id;
  }

  private void adminPin(String access, UUID id, String pin) {
    administration.editPin(
        access,
        TERMINAL,
        id,
        new za.co.onlinepos.contracts.Administration.PinEdit(
            UUID.randomUUID(), adminRow(access, id).path("revision").asLong(), pin));
  }

  @Test
  void administrationRequiresItsOwnModuleSessionAndExplicitOwnerDesignation() throws Exception {
    String access = adminToken(false);
    assertEquals(false, administration.overview(access, TERMINAL).get("ownerConfigured"));
    http.perform(get("/api/local/admin/overview")).andExpect(status().isUnauthorized());
    String passwordToken =
        (String) staff.login(new StaffService.Login("manager", PASSWORD), TERMINAL).get("token");
    http.perform(
            get("/api/local/admin/overview").header("Authorization", "Bearer " + passwordToken))
        .andExpect(status().isUnauthorized());
    String reports = enter("012345", za.co.onlinepos.contracts.PosModule.REPORTS);
    http.perform(get("/api/local/admin/overview").header("Authorization", "Bearer " + reports))
        .andExpect(status().isForbidden());
    http.perform(get("/api/local/admin/overview").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owner").value(false));
    assertEquals(
        401,
        assertThrows(LocalFailure.class, () -> administration.overview(access, "wrong-terminal"))
            .status());
    assertEquals(
        403,
        assertThrows(LocalFailure.class, () -> adminPin(access, manager.staffId(), "111111"))
            .status());
    assertEquals(0, ADMIN.queryForObject("SELECT count(*) FROM pos.store_owner", Integer.class));
  }

  @Test
  void administrationCreatesPinOnlyStaffWithIdempotencyAndSafeResponses() throws Exception {
    String access = adminToken(true);
    UUID id = UUID.randomUUID(), command = UUID.randomUUID();
    var create =
        new za.co.onlinepos.contracts.Administration.StaffEdit(
            command, id, "new.cashier", true, java.util.Set.of("CASHIER"), -1);
    var first = administration.editStaff(access, TERMINAL, create);
    assertEquals(first, administration.editStaff(access, TERMINAL, create));
    assertEquals(
        1,
        ADMIN.queryForObject(
            "SELECT count(*) FROM pos.audit_record WHERE command_id=?", Integer.class, command));
    var row = adminRow(access, id);
    assertFalse(row.path("pinConfigured").asBoolean());
    assertFalse(row.path("passwordLoginEnabled").asBoolean());
    // Even a known valid password hash cannot enable password login for a PIN-only account.
    ADMIN.update(
        "UPDATE pos.staff SET password_hash=? WHERE staff_id=?",
        za.co.onlinepos.server.local.Passwords.hash(PASSWORD.toCharArray()),
        id);
    assertEquals(
        401,
        assertThrows(
                LocalFailure.class,
                () -> staff.login(new StaffService.Login("new.cashier", PASSWORD), TERMINAL))
            .status());
    assertEquals(
        401,
        assertThrows(
                LocalFailure.class,
                () -> enter("000123", za.co.onlinepos.contracts.PosModule.SALES_ORDERS))
            .status());
    adminPin(access, id, "000123");
    String sales = enter("000123", za.co.onlinepos.contracts.PosModule.SALES_ORDERS);
    assertEquals(id, modules.authenticate(sales, TERMINAL, false).staffId());
    String body = json.writeValueAsString(administration.staff(access, TERMINAL, "new.", 0));
    for (String forbidden :
        java.util.List.of("000123", "password_hash", "lookup", "600000:", PIN_KEY))
      assertFalse(body.contains(forbidden));
    var duplicate =
        new za.co.onlinepos.contracts.Administration.StaffEdit(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "new.cashier",
            true,
            java.util.Set.of("CASHIER"),
            -1);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/local/admin/staff")
                .header("Authorization", "Bearer " + access)
                .contentType("application/json")
                .content(json.writeValueAsBytes(duplicate)))
        .andExpect(status().isConflict());
    var stale =
        new za.co.onlinepos.contracts.Administration.StaffEdit(
            UUID.randomUUID(), id, "wrong", true, java.util.Set.of("CASHIER"), -1);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/local/admin/staff")
                .header("Authorization", "Bearer " + access)
                .contentType("application/json")
                .content(json.writeValueAsBytes(stale)))
        .andExpect(status().isConflict());
    assertEquals("new.cashier", adminRow(access, id).path("username").asText());
    long revision = adminRow(access, id).path("revision").asLong();
    administration.editStaff(
        access,
        TERMINAL,
        new za.co.onlinepos.contracts.Administration.StaffEdit(
            UUID.randomUUID(), id, "new.cashier", false, java.util.Set.of("CASHIER"), revision));
    assertThrows(LocalFailure.class, () -> modules.authenticate(sales, TERMINAL, false));
  }

  @Test
  void administrationProtectsOwnerAndPreventsPrivilegeDelegationByLimitedManagers()
      throws Exception {
    String access = adminToken(true);
    administration.editRole(
        access,
        TERMINAL,
        new za.co.onlinepos.contracts.Administration.RoleEdit(
            UUID.randomUUID(),
            "STAFF_HELPER",
            java.util.Set.of("module.administration", "staff.manage"),
            -1));
    UUID helper = adminCreate(access, "helper", java.util.Set.of("STAFF_HELPER"));
    adminPin(access, helper, "111222");
    String limited = enter("111222", za.co.onlinepos.contracts.PosModule.ADMINISTRATION);
    assertEquals(
        403,
        assertThrows(LocalFailure.class, () -> administration.roles(limited, TERMINAL, false))
            .status());
    assertEquals(
        403, assertThrows(LocalFailure.class, () -> adminPin(limited, helper, "123456")).status());
    assertEquals(
        403,
        assertThrows(
                LocalFailure.class,
                () -> adminCreate(limited, "escalated", java.util.Set.of("MANAGER")))
            .status());
    var editOwner =
        new za.co.onlinepos.contracts.Administration.StaffEdit(
            UUID.randomUUID(),
            manager.staffId(),
            "takeover",
            true,
            java.util.Set.of("MANAGER"),
            adminRow(access, manager.staffId()).path("revision").asLong());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/local/admin/staff")
                .header("Authorization", "Bearer " + limited)
                .contentType("application/json")
                .content(json.writeValueAsBytes(editOwner)))
        .andExpect(status().isForbidden());
    assertEquals("manager", adminRow(access, manager.staffId()).path("username").asText());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> runtime.update("UPDATE pos.store_owner SET staff_id=?", helper));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            runtime.update(
                "UPDATE pos.staff SET username='forged' WHERE staff_id=?", manager.staffId()));
    // A direct call to the narrow SQL function still requires the owner for PIN changes.
    var pinData =
        java.util.Map.of(
            "staffId",
            helper,
            "expectedRevision",
            0,
            "lookup",
            "0".repeat(64),
            "hash",
            "600000:invalid:invalid");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            runtime.queryForObject(
                "SELECT pos.apply_admin_change(?,?,?,?::jsonb)",
                Long.class,
                CommandJournal.digest(limited),
                TERMINAL,
                "pin",
                json.writeValueAsString(pinData)));
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/local/admin/owner")
                .header("Authorization", "Bearer " + limited)
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void administrationRoleRevisionsRevocationAndPermanentOwnerAccess() throws Exception {
    String access = adminToken(true);
    var create =
        new za.co.onlinepos.contracts.Administration.RoleEdit(
            UUID.randomUUID(),
            "AUDITOR",
            java.util.Set.of("module.administration", "audit.view"),
            -1);
    var created = administration.editRole(access, TERMINAL, create);
    assertEquals(created, administration.editRole(access, TERMINAL, create));
    var roleAudit =
        json.valueToTree(
            administration.audit(access, TERMINAL, 0, null, "ADMIN_ROLE_CHANGED", null, null));
    assertEquals("AUDITOR", roleAudit.path("items").get(0).path("targetName").asText());
    UUID auditor = adminCreate(access, "auditor", java.util.Set.of("AUDITOR"));
    adminPin(access, auditor, "222333");
    String auditToken = enter("222333", za.co.onlinepos.contracts.PosModule.ADMINISTRATION);
    assertNotNull(administration.audit(auditToken, TERMINAL, 0, null, null, null, null));
    assertEquals(
        403,
        assertThrows(LocalFailure.class, () -> administration.staff(auditToken, TERMINAL, "", 0))
            .status());
    administration.editRole(
        access,
        TERMINAL,
        new za.co.onlinepos.contracts.Administration.RoleEdit(
            UUID.randomUUID(), "AUDITOR", java.util.Set.of("module.administration"), 0));
    assertThrows(LocalFailure.class, () -> modules.authenticate(auditToken, TERMINAL, false));
    String fresh = enter("222333", za.co.onlinepos.contracts.PosModule.ADMINISTRATION);
    assertEquals(
        403,
        assertThrows(
                LocalFailure.class,
                () -> administration.audit(fresh, TERMINAL, 0, null, null, null, null))
            .status());
    var stale =
        new za.co.onlinepos.contracts.Administration.RoleEdit(
            UUID.randomUUID(), "AUDITOR", java.util.Set.of("audit.view"), 0);
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/local/admin/roles")
                .header("Authorization", "Bearer " + access)
                .contentType("application/json")
                .content(json.writeValueAsBytes(stale)))
        .andExpect(status().isConflict());
    assertEquals(
        400,
        assertThrows(
                LocalFailure.class,
                () ->
                    administration.editRole(
                        access,
                        TERMINAL,
                        new za.co.onlinepos.contracts.Administration.RoleEdit(
                            UUID.randomUUID(),
                            "FUTURE",
                            java.util.Set.of("sales.layouts.edit"),
                            -1)))
            .status());
    var changed =
        administration.editRole(
            access,
            TERMINAL,
            new za.co.onlinepos.contracts.Administration.RoleEdit(
                UUID.randomUUID(), "MANAGER", java.util.Set.of(), 0));
    assertTrue(changed.path("reauthenticate").asBoolean());
    String ownerAgain = enter("012345", za.co.onlinepos.contracts.PosModule.ADMINISTRATION);
    assertTrue(
        modules
            .authenticate(ownerAgain, TERMINAL, false)
            .permissions()
            .containsAll(
                java.util.Set.of(
                    "rbac.manage", "staff.manage", "audit.view", "module.administration")));
    var disable =
        new za.co.onlinepos.contracts.Administration.StaffEdit(
            UUID.randomUUID(),
            manager.staffId(),
            "manager",
            false,
            java.util.Set.of("MANAGER"),
            adminRow(ownerAgain, manager.staffId()).path("revision").asLong());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/local/admin/staff")
                .header("Authorization", "Bearer " + ownerAgain)
                .contentType("application/json")
                .content(json.writeValueAsBytes(disable)))
        .andExpect(status().isForbidden());
  }

  @Test
  void administrationAuditFiltersPaginationAndPinConflictAreSafeAndAtomic() throws Exception {
    String access = adminToken(true);
    UUID other = adminCreate(access, "other", java.util.Set.of("CASHIER"));
    adminPin(access, other, "333444");
    long revision = adminRow(access, other).path("revision").asLong();
    UUID command = UUID.randomUUID();
    int auditBefore = ADMIN.queryForObject("SELECT count(*) FROM pos.audit_record", Integer.class);
    var collision =
        new za.co.onlinepos.contracts.Administration.PinEdit(command, revision, "012345");
    var response =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/api/local/admin/staff/" + other + "/pin")
                    .header("Authorization", "Bearer " + access)
                    .contentType("application/json")
                    .content(json.writeValueAsBytes(collision)))
            .andExpect(status().isConflict())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(response.contains("012345"));
    assertFalse(response.contains("lookup"));
    assertEquals(
        auditBefore, ADMIN.queryForObject("SELECT count(*) FROM pos.audit_record", Integer.class));
    assertEquals(
        0,
        ADMIN.queryForObject(
            "SELECT count(*) FROM pos.command_receipt WHERE command_id=?", Integer.class, command));
    assertEquals(revision, adminRow(access, other).path("revision").asLong());
    for (int i = 0; i < 53; i++)
      ADMIN.update(
          "INSERT INTO pos.audit_record(audit_id,branch_id,actor_id,action,aggregate_id,details,occurred_at) VALUES (?,1,?,'TEST_FILTER',?,?::jsonb,?)",
          UUID.randomUUID(),
          manager.staffId(),
          other,
          "{\"pin\":\"AUDIT_SECRET_SHOULD_NOT_LEAK\"}",
          java.sql.Timestamp.from(java.time.Instant.parse("2026-10-25T10:00:00Z").plusSeconds(i)));
    var page0 =
        json.valueToTree(
            administration.audit(
                access,
                TERMINAL,
                0,
                "manager",
                "TEST_FILTER",
                java.time.Instant.parse("2026-10-25T10:00:00Z"),
                java.time.Instant.parse("2026-10-25T10:01:00Z")));
    var page1 =
        json.valueToTree(
            administration.audit(
                access,
                TERMINAL,
                1,
                "manager",
                "TEST_FILTER",
                java.time.Instant.parse("2026-10-25T10:00:00Z"),
                java.time.Instant.parse("2026-10-25T10:01:00Z")));
    http.perform(
            get("/api/local/admin/audit")
                .header("Authorization", "Bearer " + access)
                .param("actor", "manager")
                .param("action", "TEST_FILTER")
                .param("from", "2026-10-25T10:00:00Z")
                .param("to", "2026-10-25T10:01:00Z")
                .param("page", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(50))
        .andExpect(jsonPath("$.hasMore").value(true));
    assertEquals(50, page0.path("items").size());
    assertTrue(page0.path("hasMore").asBoolean());
    assertEquals(3, page1.path("items").size());
    assertFalse(page1.path("hasMore").asBoolean());
    assertEquals("other", page0.path("items").get(0).path("targetName").asText());
    assertFalse(page0.toString().contains("AUDIT_SECRET"));
    assertFalse(page0.toString().contains("details"));
    assertFalse(
        json.valueToTree(administration.audit(access, TERMINAL, 0, "protected", null, null, null))
            .path("items")
            .isEmpty());
    assertEquals(
        400,
        assertThrows(
                LocalFailure.class,
                () -> administration.audit(access, TERMINAL, -1, null, null, null, null))
            .status());
  }

  @Test
  void administrationOwnerPinResetAndProtectedOwnershipTransferRevokeSessions() {
    String access = adminToken(true);
    UUID nextOwner = adminCreate(access, "next.owner", java.util.Set.of("CASHIER"));
    adminPin(access, nextOwner, "444555");
    var reset =
        administration.editPin(
            access,
            TERMINAL,
            manager.staffId(),
            new za.co.onlinepos.contracts.Administration.PinEdit(
                UUID.randomUUID(),
                adminRow(access, manager.staffId()).path("revision").asLong(),
                "555666"));
    assertTrue(reset.path("reauthenticate").asBoolean());
    assertThrows(LocalFailure.class, () -> modules.authenticate(access, TERMINAL, false));
    String fresh = enter("555666", za.co.onlinepos.contracts.PosModule.ADMINISTRATION);
    za.co.onlinepos.server.local.RbacProvisioning.apply(
        OWNER, IDENTITY, "set-owner", "next.owner", java.util.Set.of(), new char[0], PIN_KEY);
    assertThrows(LocalFailure.class, () -> modules.authenticate(fresh, TERMINAL, false));
    String next = enter("444555", za.co.onlinepos.contracts.PosModule.ADMINISTRATION);
    assertEquals(true, administration.overview(next, TERMINAL).get("owner"));
    String former = enter("555666", za.co.onlinepos.contracts.PosModule.ADMINISTRATION);
    assertEquals(false, administration.overview(former, TERMINAL).get("owner"));
    assertEquals(
        403,
        assertThrows(LocalFailure.class, () -> adminPin(former, nextOwner, "666777")).status());
    // Existing password-based recovery for the original account remains available.
    assertNotNull(staff.login(new StaffService.Login("manager", PASSWORD), TERMINAL).get("token"));
  }

  @Autowired za.co.onlinepos.server.catalogue.CatalogueManagementService editor;

  private String catalogueEditor() throws Exception {
    emptyCatalogue();
    var f = new za.co.onlinepos.server.catalogue.SnapshotFormat();
    var source = snapshot(1);
    catalogue.apply(
        manager,
        1,
        new za.co.onlinepos.contracts.CatalogueSnapshot.Import(
            UUID.randomUUID(), f.fingerprint(source), source));
    ADMIN.update("INSERT INTO pos.role_permission VALUES (1,'MANAGER','catalogue.manage')");
    provisionPin("manager", "012345");
    return enter("012345", za.co.onlinepos.contracts.PosModule.CATALOGUE);
  }

  private java.util.Map<String, Object> catalogueValues(JsonNode row) {
    return json.convertValue(
        row, new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
  }

  private za.co.onlinepos.contracts.CatalogueManagement.Edit catalogueEdit(
      String access,
      String table,
      JsonNode row,
      java.util.Map<String, Object> values,
      boolean remove,
      boolean shared) {
    var state = editor.state(access, TERMINAL);
    List<Long> key = row == null ? null : new java.util.ArrayList<>();
    if (row != null)
      for (var k : state.path("schema").path(table).path("keys"))
        key.add(row.path(k.asText()).longValue());
    return new za.co.onlinepos.contracts.CatalogueManagement.Edit(
        UUID.randomUUID(),
        state.path("revision").asLong(),
        List.of(
            new za.co.onlinepos.contracts.CatalogueManagement.Change(table, key, values, remove)),
        shared);
  }

  @Test
  void catalogueEditorUpdatesAllFamiliesWithoutChangingBootstrapOrStableIdentities()
      throws Exception {
    String access = catalogueEditor();
    var original = catalogue.catalogue(1);
    var ids =
        runtime.queryForList("SELECT item_id,price_id FROM pos.branch_price ORDER BY price_id");
    var state = editor.state(access, TERMINAL);
    var changes = new java.util.ArrayList<za.co.onlinepos.contracts.CatalogueManagement.Change>();
    for (var entry : state.path("tables").properties()) {
      String table = entry.getKey();
      for (var row : entry.getValue()) {
        var values = catalogueValues(row);
        if (values.containsKey("name")) values.put("name", "Edited " + table);
        if (values.containsKey("ingredient_name"))
          values.put("ingredient_name", "Edited salad ingredient");
        if (values.containsKey("price")) values.put("price", new BigDecimal("12.50"));
        if (values.containsKey("additional_price"))
          values.put("additional_price", new BigDecimal("1.25"));
        var key = new java.util.ArrayList<Long>();
        for (var k : state.path("schema").path(table).path("keys"))
          key.add(row.path(k.asText()).longValue());
        changes.add(
            new za.co.onlinepos.contracts.CatalogueManagement.Change(table, key, values, false));
      }
    }
    var edit =
        new za.co.onlinepos.contracts.CatalogueManagement.Edit(UUID.randomUUID(), 0, changes, true);
    var saved = editor.edit(access, TERMINAL, edit);
    assertEquals(1, saved.path("revision").asLong());
    assertEquals(saved, editor.edit(access, TERMINAL, edit));
    assertEquals(
        ids,
        runtime.queryForList("SELECT item_id,price_id FROM pos.branch_price ORDER BY price_id"));
    assertEquals(original.path("snapshot"), catalogue.catalogue(1).path("snapshot"));
    assertEquals(original.path("sha256"), catalogue.catalogue(1).path("sha256"));
    assertEquals(
        1,
        runtime.queryForObject(
            "SELECT count(*) FROM pos.audit_record WHERE action='CATALOGUE_CHANGED'",
            Integer.class));
    assertTrue(
        runtime
            .queryForObject(
                "SELECT definition::text FROM pos.catalog_item WHERE source_type='branch_pizza_price'",
                String.class)
            .contains("Edited ingredient"));
    assertEquals(
        new BigDecimal("12.50"),
        runtime.queryForObject(
            "SELECT amount FROM pos.branch_price p JOIN pos.catalog_item c USING(branch_id,item_id) WHERE c.source_type='branch_menu_item_price'",
            BigDecimal.class));
  }

  @Test
  void catalogueCreatesMastersAndRelatedPricesAndRejectsRemovalOfMasters() throws Exception {
    String access = catalogueEditor();
    for (String table :
        List.of(
            "menu_category",
            "menu_item",
            "pizza_category",
            "pizza",
            "pizza_size",
            "price_category",
            "ingredient",
            "pizza_base_option",
            "burger_component",
            "burger_recipe",
            "modifier_group",
            "modifier_option",
            "salad_ingredients")) {
      var row = editor.state(access, TERMINAL).path("tables").path(table).get(0);
      var values = catalogueValues(row);
      if (values.containsKey("name")) values.put("name", "New " + table);
      if (values.containsKey("ingredient_name"))
        values.put("ingredient_name", "New salad ingredient");
      editor.edit(access, TERMINAL, catalogueEdit(access, table, null, values, false, true));
      assertEquals(2, editor.state(access, TERMINAL).path("tables").path(table).size());
    }
    var newItem =
        editor
            .state(access, TERMINAL)
            .path("tables")
            .path("menu_item")
            .valueStream()
            .filter(r -> r.path("name").asText().equals("New menu_item"))
            .findFirst()
            .orElseThrow();
    assertTrue(newItem.path("id").asLong() >= 1000000000);
    editor.edit(
        access,
        TERMINAL,
        catalogueEdit(
            access,
            "branch_menu_item_price",
            null,
            java.util.Map.of(
                "branch_id",
                1,
                "menu_item_id",
                newItem.path("id").asLong(),
                "price",
                new BigDecimal("55.20")),
            false,
            false));
    assertEquals(
        1,
        runtime.queryForObject(
            "SELECT count(*) FROM pos.catalog_item WHERE description='New menu_item'",
            Integer.class));
    var remove = catalogueEdit(access, "menu_item", newItem, catalogueValues(newItem), true, false);
    assertEquals(
        400,
        assertThrows(LocalFailure.class, () -> editor.edit(access, TERMINAL, remove)).status());
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/local/catalogue/records")
                .header("Authorization", "Bearer " + access)
                .contentType("application/json")
                .content(
                    json.writeValueAsBytes(
                        catalogueEdit(
                            access, "menu_item", newItem, catalogueValues(newItem), false, false))))
        .andExpect(status().isOk());
  }

  @Test
  void catalogueApiRequiresCatalogueModuleAndExplicitEditPermissionWithImmediateRevocation()
      throws Exception {
    String writable = catalogueEditor();
    String access = writable;
    String legacy =
        (String) staff.login(new StaffService.Login("manager", PASSWORD), TERMINAL).get("token");
    UUID importedPrice =
        runtime.queryForObject("SELECT price_id FROM pos.branch_price LIMIT 1", UUID.class);
    var legacyEdit =
        new LocalCommands.PriceEdit(UUID.randomUUID(), 0, new BigDecimal("1.00"), true);
    assertEquals(
        403,
        assertThrows(
                LocalFailure.class,
                () ->
                    operations.price(
                        staff.authenticate(legacy, TERMINAL), importedPrice, legacyEdit))
            .status());
    http.perform(
            patch("/api/local/branches/1/prices/" + importedPrice)
                .header("Authorization", "Bearer " + legacy)
                .contentType("application/json")
                .content(json.writeValueAsBytes(legacyEdit)))
        .andExpect(status().isForbidden());

    http.perform(get("/api/local/catalogue/state").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.editable").value(true));
    http.perform(get("/api/local/catalogue/state").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    String other = enter("012345", za.co.onlinepos.contracts.PosModule.ADMINISTRATION);
    http.perform(get("/api/local/catalogue/state").header("Authorization", "Bearer " + other))
        .andExpect(status().isForbidden());
    assertEquals(
        401,
        assertThrows(LocalFailure.class, () -> editor.state(writable, "other-terminal")).status());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> runtime.update("UPDATE pos.catalogue_record SET data='{}'"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            runtime.queryForObject(
                "SELECT pos.apply_catalogue_change(?,?,0,'[]','[]')",
                Long.class,
                CommandJournal.digest(other),
                TERMINAL));
    ADMIN.update("DELETE FROM pos.role_permission WHERE permission='catalogue.manage'");
    assertEquals(
        401, assertThrows(LocalFailure.class, () -> editor.state(writable, TERMINAL)).status());
    access = enter("012345", za.co.onlinepos.contracts.PosModule.CATALOGUE);
    assertFalse(editor.state(access, TERMINAL).path("editable").asBoolean());
    var row = editor.state(access, TERMINAL).path("tables").path("menu_item").get(0);
    var edit = catalogueEdit(access, "menu_item", row, catalogueValues(row), false, false);
    final String browse = access;
    assertEquals(
        403, assertThrows(LocalFailure.class, () -> editor.edit(browse, TERMINAL, edit)).status());
    ADMIN.update("UPDATE pos.module_session SET expires_at=clock_timestamp()-interval '1 second'");
    assertEquals(
        401, assertThrows(LocalFailure.class, () -> editor.state(browse, TERMINAL)).status());
  }

  @Test
  void catalogueValidatesPricesReferencesSizesAndInactiveIngredientAssignments() throws Exception {
    String access = catalogueEditor();
    for (String table :
        List.of(
            "branch_menu_item_price",
            "branch_pizza_price",
            "branch_extra_price",
            "branch_burger_component_price",
            "salad_ingredients",
            "modifier_option")) {
      var row = editor.state(access, TERMINAL).path("tables").path(table).get(0);
      var values = catalogueValues(row);
      values.put(
          table.equals("modifier_option") ? "additional_price" : "price", new BigDecimal("-0.01"));
      var edit = catalogueEdit(access, table, row, values, false, false);
      assertEquals(
          400,
          assertThrows(LocalFailure.class, () -> editor.edit(access, TERMINAL, edit)).status());
    }
    var row = editor.state(access, TERMINAL).path("tables").path("ingredient").get(0);
    var values = catalogueValues(row);
    values.put("active", false);
    editor.edit(access, TERMINAL, catalogueEdit(access, "ingredient", row, values, false, false));
    assertFalse(editor.state(access, TERMINAL).path("warnings").isEmpty());
    var relationship =
        editor.state(access, TERMINAL).path("tables").path("pizza_default_ingredient").get(0);
    editor.edit(
        access,
        TERMINAL,
        catalogueEdit(
            access,
            "pizza_default_ingredient",
            relationship,
            catalogueValues(relationship),
            true,
            false));
    var assignment =
        catalogueEdit(
            access, "pizza_default_ingredient", null, catalogueValues(relationship), false, false);
    assertEquals(
        400,
        assertThrows(LocalFailure.class, () -> editor.edit(access, TERMINAL, assignment)).status());
    var item = editor.state(access, TERMINAL).path("tables").path("menu_item").get(0);
    var invalid = catalogueValues(item);
    invalid.put("category_id", 999999);
    var broken = catalogueEdit(access, "menu_item", item, invalid, false, false);
    assertEquals(
        400,
        assertThrows(LocalFailure.class, () -> editor.edit(access, TERMINAL, broken)).status());
    var allowed = editor.state(access, TERMINAL).path("tables").path("pizza_allowed_size").get(0);
    var disallow =
        catalogueEdit(access, "pizza_allowed_size", allowed, catalogueValues(allowed), true, false);
    assertEquals(
        400,
        assertThrows(LocalFailure.class, () -> editor.edit(access, TERMINAL, disallow)).status());
    var duplicate =
        catalogueEdit(access, "pizza_allowed_size", null, catalogueValues(allowed), false, false);
    assertEquals(
        400,
        assertThrows(LocalFailure.class, () -> editor.edit(access, TERMINAL, duplicate)).status());
    var component =
        editor.state(access, TERMINAL).path("tables").path("burger_item_default_component").get(0);
    var identityChange = catalogueValues(component);
    identityChange.put("component_id", 999999);
    var changedKey =
        catalogueEdit(
            access, "burger_item_default_component", component, identityChange, false, false);
    assertEquals(
        400,
        assertThrows(LocalFailure.class, () -> editor.edit(access, TERMINAL, changedKey)).status());
    var foreignValues =
        java.util.Map.<String, Object>of("branch_id", 2, "menu_item_id", 1, "price", 1);
    var foreign =
        catalogueEdit(access, "branch_menu_item_price", null, foreignValues, false, false);
    assertEquals(
        403,
        assertThrows(LocalFailure.class, () -> editor.edit(access, TERMINAL, foreign)).status());
    var group = editor.state(access, TERMINAL).path("tables").path("modifier_group").get(0);
    invalid = catalogueValues(group);
    invalid.put("min_select", 2);
    invalid.put("max_select", 1);
    var limits = catalogueEdit(access, "modifier_group", group, invalid, false, false);
    assertEquals(
        400,
        assertThrows(LocalFailure.class, () -> editor.edit(access, TERMINAL, limits)).status());
  }

  @Test
  void catalogueSharedRecipeConfirmationStaleEditsAndAtomicOutboxFailure() throws Exception {
    String access = catalogueEditor();
    var row = editor.state(access, TERMINAL).path("tables").path("burger_recipe_component").get(0);
    var values = catalogueValues(row);
    values.put("is_removable", false);
    var unconfirmed = catalogueEdit(access, "burger_recipe_component", row, values, false, false);
    assertEquals(
        409,
        assertThrows(LocalFailure.class, () -> editor.edit(access, TERMINAL, unconfirmed))
            .status());
    var confirmed = catalogueEdit(access, "burger_recipe_component", row, values, false, true);
    assertEquals(1, editor.edit(access, TERMINAL, confirmed).path("affectedItems").size());
    var stale =
        new za.co.onlinepos.contracts.CatalogueManagement.Edit(
            UUID.randomUUID(), 0, confirmed.changes(), true);
    assertEquals(
        409, assertThrows(LocalFailure.class, () -> editor.edit(access, TERMINAL, stale)).status());
    var state = editor.state(access, TERMINAL);
    var next =
        catalogueEdit(
            access,
            "burger_recipe_component",
            state.path("tables").path("burger_recipe_component").get(0),
            values,
            false,
            true);
    ADMIN.execute(
        "CREATE FUNCTION pos.reject_catalogue_event() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic outbox failure'; END $$");
    ADMIN.execute(
        "CREATE TRIGGER reject_catalogue_event BEFORE INSERT ON pos.outbox_event FOR EACH ROW EXECUTE FUNCTION pos.reject_catalogue_event()");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> editor.edit(access, TERMINAL, next));
    } finally {
      ADMIN.execute("DROP TRIGGER reject_catalogue_event ON pos.outbox_event");
      ADMIN.execute("DROP FUNCTION pos.reject_catalogue_event()");
    }
    assertEquals(state, editor.state(access, TERMINAL));
    assertEquals(
        0,
        runtime.queryForObject(
            "SELECT count(*) FROM pos.command_receipt WHERE command_id=?",
            Integer.class,
            next.commandId()));
  }

  @Test
  void catalogueConcurrentEditsPreserveDraftSnapshotsAndRemovedPricesBecomeUnavailable()
      throws Exception {
    String access = catalogueEditor();
    var price =
        runtime.queryForMap(
            "SELECT p.price_id,p.revision FROM pos.branch_price p JOIN pos.catalog_item c USING(branch_id,item_id) WHERE c.source_type='branch_menu_item_price'");
    var actor =
        staff.authenticate(
            (String)
                staff.login(new StaffService.Login("manager", PASSWORD), TERMINAL).get("token"),
            TERMINAL);
    UUID order = UUID.randomUUID();
    operations.createDraft(
        actor,
        new LocalCommands.CreateDraft(
            UUID.randomUUID(),
            order,
            List.of(
                new LocalCommands.DraftLine(
                    UUID.randomUUID(),
                    (UUID) price.get("price_id"),
                    ((Number) price.get("revision")).longValue(),
                    1))));
    String draft =
        runtime.queryForObject(
            "SELECT snapshot::text FROM pos.order_line WHERE order_id=?", String.class, order);
    var row = editor.state(access, TERMINAL).path("tables").path("branch_menu_item_price").get(0);
    var values = catalogueValues(row);
    values.put("price", new BigDecimal("30.50"));
    var first = catalogueEdit(access, "branch_menu_item_price", row, values, false, false);
    var second =
        new za.co.onlinepos.contracts.CatalogueManagement.Edit(
            UUID.randomUUID(), first.expectedRevision(), first.changes(), false);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var results =
          List.of(first, second).stream()
              .map(
                  command ->
                      workers.submit(
                          () -> {
                            start.await();
                            try {
                              editor.edit(access, TERMINAL, command);
                              return 200;
                            } catch (LocalFailure failure) {
                              return failure.status();
                            }
                          }))
              .toList();
      start.countDown();
      var codes = new java.util.ArrayList<Integer>();
      for (var result : results) codes.add(result.get());
      codes.sort(Integer::compareTo);
      assertEquals(List.of(200, 409), codes);
    }
    assertEquals(
        draft,
        runtime.queryForObject(
            "SELECT snapshot::text FROM pos.order_line WHERE order_id=?", String.class, order));
    row = editor.state(access, TERMINAL).path("tables").path("branch_menu_item_price").get(0);
    editor.edit(
        access,
        TERMINAL,
        catalogueEdit(access, "branch_menu_item_price", row, catalogueValues(row), true, false));
    assertFalse(
        runtime.queryForObject(
            "SELECT available FROM pos.branch_price WHERE price_id=?",
            Boolean.class,
            price.get("price_id")));
    assertEquals(
        draft,
        runtime.queryForObject(
            "SELECT snapshot::text FROM pos.order_line WHERE order_id=?", String.class, order));
  }

  @Test
  void catalogueEditsAuditedCloudSeedAndOwnerCanExplicitlyDelegateEditing() throws Exception {
    emptyCatalogue();
    var format = new za.co.onlinepos.server.catalogue.SnapshotFormat();
    var seed =
        json.readValue(
            getClass().getResourceAsStream("/cloud-seed-branch1.json"),
            za.co.onlinepos.contracts.CatalogueSnapshot.class);
    catalogue.apply(
        manager,
        1,
        new za.co.onlinepos.contracts.CatalogueSnapshot.Import(
            UUID.randomUUID(), format.fingerprint(seed), seed));
    assertEquals(
        0,
        runtime.queryForObject(
            "SELECT count(*) FROM pos.role_permission WHERE permission='catalogue.manage'",
            Integer.class));
    String owner = adminToken(true);
    administration.editRole(
        owner,
        TERMINAL,
        new za.co.onlinepos.contracts.Administration.RoleEdit(
            UUID.randomUUID(),
            "CATALOGUE_EDITOR",
            java.util.Set.of("module.catalogue", "catalogue.manage"),
            -1));
    var id =
        runtime.queryForObject(
            "SELECT staff_id FROM pos.staff WHERE username='manager'", UUID.class);
    administration.editStaff(
        owner,
        TERMINAL,
        new za.co.onlinepos.contracts.Administration.StaffEdit(
            UUID.randomUUID(),
            id,
            "manager",
            true,
            java.util.Set.of("MANAGER", "CATALOGUE_EDITOR"),
            adminRow(owner, id).path("revision").asLong()));
    String access = enter("012345", za.co.onlinepos.contracts.PosModule.CATALOGUE);
    var row = editor.state(access, TERMINAL).path("tables").path("menu_item").get(0);
    var values = catalogueValues(row);
    values.put("description", "Edited locally");
    editor.edit(access, TERMINAL, catalogueEdit(access, "menu_item", row, values, false, false));
    assertEquals(1, editor.state(access, TERMINAL).path("revision").asLong());
    assertEquals(
        241, runtime.queryForObject("SELECT count(*) FROM pos.branch_price", Integer.class));
    assertEquals(format.tree(seed), catalogue.catalogue(1).path("snapshot"));
    assertEquals(
        seed.counts().values().stream().mapToInt(Integer::intValue).sum(),
        runtime.queryForObject("SELECT count(*) FROM pos.catalogue_record", Integer.class));
    var updated =
        editor
            .state(access, TERMINAL)
            .path("tables")
            .path("menu_item")
            .valueStream()
            .filter(r -> r.path("id").equals(row.path("id")))
            .findFirst()
            .orElseThrow();
    editor.edit(
        access,
        TERMINAL,
        catalogueEdit(access, "menu_item", updated, catalogueValues(updated), false, false));
    assertEquals(2, editor.state(access, TERMINAL).path("revision").asLong());
  }

  private java.util.Map<String, Boolean> tracking(boolean enabled) {
    var result = new java.util.TreeMap<String, Boolean>();
    for (var type : za.co.onlinepos.contracts.Auditing.EventType.values())
      if (type.optional()) result.put(type.name(), enabled);
    return result;
  }

  @Test
  void auditDefaultsClassificationAndImmutablePrivileges() {
    for (var type : za.co.onlinepos.contracts.Auditing.EventType.values())
      assertTrue(audit.isTrackingEnabled(type));
    ADMIN.update("UPDATE pos.audit_tracking_settings SET enabled=false");
    for (var type : za.co.onlinepos.contracts.Auditing.EventType.values()) {
      UUID command = UUID.randomUUID();
      audit.record(
          manager.staffId(),
          TERMINAL,
          type.name(),
          IDENTITY.clusterId(),
          command,
          java.util.Map.of("reason", "TEST"));
      assertEquals(
          type.optional() ? 0 : 1,
          ADMIN.queryForObject(
              "SELECT count(*) FROM pos.audit_record WHERE command_id=?", Integer.class, command));
    }
    for (String sql :
        List.of(
            "UPDATE pos.audit_record SET action='tampered'",
            "DELETE FROM pos.audit_record",
            "TRUNCATE pos.audit_record",
            "UPDATE pos.audit_tracking_settings SET enabled=true",
            "UPDATE pos.audit_policy SET revision=0"))
      assertThrows(org.springframework.dao.DataAccessException.class, () -> runtime.execute(sql));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> ADMIN.execute("DELETE FROM pos.audit_record"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> ADMIN.execute("TRUNCATE pos.audit_record"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            runtime.execute(
                "INSERT INTO pos.audit_record(audit_id,branch_id,actor_id,action,aggregate_id,details) VALUES(gen_random_uuid(),1,NULL,'STAFF_LOGIN',gen_random_uuid(),'{}')"));
  }

  @Test
  void ownerApprovedPolicyIsVersionedIdempotentAndNeverContainsPin() throws Exception {
    String access = adminToken(true);
    var edit =
        new za.co.onlinepos.contracts.Auditing.SettingsEdit(
            UUID.randomUUID(), 0, tracking(false), "012345");
    var saved = auditPolicy.save(access, TERMINAL, edit);
    assertEquals(1, saved.path("revision").asLong());
    assertEquals(
        saved,
        auditPolicy.save(
            access,
            TERMINAL,
            new za.co.onlinepos.contracts.Auditing.SettingsEdit(
                edit.commandId(), 0, tracking(false), "")));
    assertEquals(
        1,
        ADMIN.queryForObject(
            "SELECT count(*) FROM pos.audit_record WHERE action='AUDIT_SETTING_CHANGED'",
            Integer.class));
    assertEquals(
        false,
        ((java.util.Map<?, ?>) auditPolicy.settings(access, TERMINAL).get("settings"))
            .get("DRAFT_CANCELLED"));
    assertThrows(
        LocalFailure.class,
        () ->
            auditPolicy.save(
                access,
                TERMINAL,
                new za.co.onlinepos.contracts.Auditing.SettingsEdit(
                    edit.commandId(), 0, tracking(true), "012345")));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            auditPolicy.save(
                access,
                TERMINAL,
                new za.co.onlinepos.contracts.Auditing.SettingsEdit(
                    UUID.randomUUID(), 0, tracking(true), "012345")));
    assertEquals(
        0,
        ADMIN.queryForObject(
            "SELECT count(*) FROM pos.audit_record WHERE details::text LIKE '%012345%'",
            Integer.class));
    assertEquals(
        0,
        ADMIN.queryForObject(
            "SELECT count(*) FROM pos.command_receipt WHERE result::text LIKE '%012345%'",
            Integer.class));
    var freshService = new za.co.onlinepos.server.local.AuditService(runtime, json, IDENTITY);
    assertFalse(
        freshService.isTrackingEnabled(
            za.co.onlinepos.contracts.Auditing.EventType.DRAFT_CANCELLED));
    http.perform(get("/api/local/admin/audit/settings").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(1));
  }

  @Test
  void auditOwnerPinFailuresThrottleAndDirectRequestsCannotBypassApproval() throws Exception {
    String access = adminToken(false);
    for (int n = 0; n < 5; n++)
      assertEquals(
          401,
          assertThrows(
                  LocalFailure.class,
                  () ->
                      auditPolicy.save(
                          access,
                          TERMINAL,
                          new za.co.onlinepos.contracts.Auditing.SettingsEdit(
                              UUID.randomUUID(), 0, tracking(false), "012345")))
              .status());
    assertEquals(
        429,
        assertThrows(
                LocalFailure.class,
                () ->
                    auditPolicy.save(
                        access,
                        TERMINAL,
                        new za.co.onlinepos.contracts.Auditing.SettingsEdit(
                            UUID.randomUUID(), 0, tracking(false), "012345")))
            .status());
    assertEquals(
        5,
        ADMIN.queryForObject(
            "SELECT count(*) FROM pos.audit_record WHERE action='OWNER_PIN_FAILED'",
            Integer.class));
    assertEquals(
        0,
        ADMIN.queryForObject(
            "SELECT revision FROM pos.audit_policy WHERE branch_id=1", Long.class));
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/local/admin/audit/settings")
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        new za.co.onlinepos.contracts.Auditing.SettingsEdit(
                            UUID.randomUUID(), 0, tracking(false), "012345"))))
        .andExpect(status().isUnauthorized());
    assertTrue(
        ADMIN.queryForObject(
                "SELECT count(*) FROM pos.audit_record WHERE action='AUDIT_ACCESS_REFUSED'",
                Integer.class)
            > 0);
  }

  @Test
  void optionalHistoryNeverSuppressesBusinessReceiptsOrOutbox() {
    String access = adminToken(true);
    // PIN provisioning revokes the fixture password actor; reacquire it through the existing
    // recovery login.
    String recovery =
        (String) staff.login(new StaffService.Login("manager", PASSWORD), TERMINAL).get("token");
    var actor = staff.authenticate(recovery, TERMINAL);
    auditPolicy.save(
        access,
        TERMINAL,
        new za.co.onlinepos.contracts.Auditing.SettingsEdit(
            UUID.randomUUID(), 0, tracking(false), "012345"));
    var command = UUID.randomUUID();
    var edit = new LocalCommands.PriceEdit(command, 0, new BigDecimal("37.00"), true);
    var result = operations.price(actor, PRICE, edit);
    assertEquals(result, operations.price(actor, PRICE, edit));
    assertEquals(
        new BigDecimal("37.00"),
        ADMIN.queryForObject(
            "SELECT amount FROM pos.branch_price WHERE price_id=?", BigDecimal.class, PRICE));
    assertEquals(
        0,
        ADMIN.queryForObject(
            "SELECT count(*) FROM pos.audit_record WHERE command_id=?", Integer.class, command));
    assertEquals(
        1,
        ADMIN.queryForObject(
            "SELECT count(*) FROM pos.outbox_event WHERE event_type='PRICE_CHANGED'",
            Integer.class));
    assertEquals(
        1,
        ADMIN.queryForObject(
            "SELECT count(*) FROM pos.command_receipt WHERE command_id=?", Integer.class, command));
    auditPolicy.save(
        access,
        TERMINAL,
        new za.co.onlinepos.contracts.Auditing.SettingsEdit(
            UUID.randomUUID(), 1, tracking(true), "012345"));
    var next = new LocalCommands.PriceEdit(UUID.randomUUID(), 1, new BigDecimal("38.00"), true);
    operations.price(actor, PRICE, next);
    assertEquals(
        1,
        ADMIN.queryForObject(
            "SELECT count(*) FROM pos.audit_record WHERE action='PRICE_CHANGED'", Integer.class));
    assertTrue(
        ADMIN
            .queryForObject(
                "SELECT details::text FROM pos.audit_record WHERE action='PRICE_CHANGED'",
                String.class)
            .contains("oldValues"));
  }

  @Test
  void auditFailureRollsBackBusinessCommandAndOutbox() {
    ADMIN.execute(
        "CREATE FUNCTION pos.test_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='PRICE_CHANGED' THEN RAISE EXCEPTION 'test audit failure'; END IF; RETURN NEW; END $$");
    ADMIN.execute(
        "CREATE TRIGGER test_audit_failure BEFORE INSERT ON pos.audit_record FOR EACH ROW EXECUTE FUNCTION pos.test_audit_failure()");
    UUID command = UUID.randomUUID();
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              operations.price(
                  manager,
                  PRICE,
                  new LocalCommands.PriceEdit(command, 0, new BigDecimal("99"), true)));
      assertEquals(
          new BigDecimal("25.50"),
          ADMIN.queryForObject(
              "SELECT amount FROM pos.branch_price WHERE price_id=?", BigDecimal.class, PRICE));
      assertEquals(
          0,
          ADMIN.queryForObject(
              "SELECT count(*) FROM pos.command_receipt WHERE command_id=?",
              Integer.class,
              command));
      assertEquals(0, ADMIN.queryForObject("SELECT count(*) FROM pos.outbox_event", Integer.class));
    } finally {
      ADMIN.execute("DROP TRIGGER test_audit_failure ON pos.audit_record");
      ADMIN.execute("DROP FUNCTION pos.test_audit_failure()");
    }
  }

  @Test
  void auditApprovalRejectsExpiredTamperedAndReplayedChangesAtDatabaseBoundary() {
    String access = adminToken(true);
    String initiator = CommandJournal.digest(access);
    String fresh =
        (String)
            modules
                .enterOwner(
                    new za.co.onlinepos.server.local.ModuleAccess.Entry(
                        "012345", za.co.onlinepos.contracts.PosModule.ADMINISTRATION),
                    TERMINAL)
                .get("token");
    String proof = CommandJournal.digest(fresh);
    UUID approval = UUID.randomUUID();
    String values = journal.encode(tracking(false));
    runtime.queryForObject(
        "SELECT pos.issue_audit_approval(?,?,?,?,?::jsonb,?)",
        Object.class,
        approval,
        initiator,
        proof,
        TERMINAL,
        values,
        0L);
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            runtime.queryForObject(
                "SELECT pos.apply_audit_policy(?,?,?,?,?::jsonb,?,?)",
                Long.class,
                approval,
                initiator,
                proof,
                TERMINAL,
                journal.encode(tracking(true)),
                0L,
                UUID.randomUUID()));
    ADMIN.update(
        "UPDATE pos.audit_approval SET expires_at=clock_timestamp()-interval '1 second' WHERE approval_id=?",
        approval);
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            runtime.queryForObject(
                "SELECT pos.apply_audit_policy(?,?,?,?,?::jsonb,?,?)",
                Long.class,
                approval,
                initiator,
                proof,
                TERMINAL,
                values,
                0L,
                UUID.randomUUID()));
    ADMIN.update(
        "UPDATE pos.audit_approval SET expires_at=clock_timestamp()+interval '30 seconds' WHERE approval_id=?",
        approval);
    assertEquals(
        1,
        runtime.queryForObject(
            "SELECT pos.apply_audit_policy(?,?,?,?,?::jsonb,?,?)",
            Long.class,
            approval,
            initiator,
            proof,
            TERMINAL,
            values,
            0L,
            UUID.randomUUID()));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            runtime.queryForObject(
                "SELECT pos.apply_audit_policy(?,?,?,?,?::jsonb,?,?)",
                Long.class,
                approval,
                initiator,
                proof,
                TERMINAL,
                values,
                1L,
                UUID.randomUUID()));
    modules.discardApprovalSession(fresh);
  }

  @Test
  void auditCursorDetailsSnapshotsRedactionAndDuplicateProtection() throws Exception {
    ADMIN.update(
        "INSERT INTO pos.role_permission VALUES(1,'MANAGER','audit.view') ON CONFLICT DO NOTHING");
    String access = adminToken(true);
    UUID command = UUID.randomUUID();
    audit.record(
        manager.staffId(),
        TERMINAL,
        "STAFF_CHANGED",
        manager.staffId(),
        command,
        java.util.Map.of(
            "changes",
            java.util.Map.of("password", "hidden-password", "pin", "012345", "safe", "visible")));
    audit.record(
        manager.staffId(),
        TERMINAL,
        "STAFF_CHANGED",
        manager.staffId(),
        command,
        java.util.Map.of(
            "changes",
            java.util.Map.of("password", "hidden-password", "pin", "012345", "safe", "visible")));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            audit.record(
                manager.staffId(),
                TERMINAL,
                "STAFF_CHANGED",
                manager.staffId(),
                command,
                java.util.Map.of("reason", "different")));
    for (int n = 0; n < 55; n++)
      audit.record(
          manager.staffId(),
          TERMINAL,
          "STAFF_CHANGED",
          manager.staffId(),
          UUID.randomUUID(),
          java.util.Map.of("reason", "PAGE_TEST"));
    var search =
        new za.co.onlinepos.server.local.AuditService.Search(
            null, null, TERMINAL, "manager", null, "Security", "STAFF_CHANGED", "");
    var page = audit.history(search);
    assertEquals(50, ((List<?>) page.get("items")).size());
    assertEquals(true, page.get("hasMore"));
    var second =
        audit.history(
            new za.co.onlinepos.server.local.AuditService.Search(
                null,
                null,
                TERMINAL,
                "manager",
                null,
                "Security",
                "STAFF_CHANGED",
                (String) page.get("nextCursor")));
    assertEquals(6, ((List<?>) second.get("items")).size());
    UUID id =
        ADMIN.queryForObject(
            "SELECT audit_id FROM pos.audit_record WHERE command_id=?", UUID.class, command);
    String details = json.writeValueAsString(audit.detail(id));
    assertFalse(details.contains("hidden-password"));
    assertFalse(details.contains("012345"));
    http.perform(
            get("/api/local/admin/audit/events/" + id).header("Authorization", "Bearer " + access))
        .andExpect(status().isOk());
    http.perform(
            get("/api/local/admin/audit/events?category=Security&action=STAFF_CHANGED")
                .header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(50));
    assertEquals(
        0,
        ((List<?>)
                audit
                    .history(
                        new za.co.onlinepos.server.local.AuditService.Search(
                            null, null, "different-terminal", "", null, "", "", ""))
                    .get("items"))
            .size());
    assertThrows(
        LocalFailure.class,
        () ->
            audit.history(
                new za.co.onlinepos.server.local.AuditService.Search(
                    null, null, "", "", null, "", "", "invalid")));
  }

  @Test
  void concurrentAuditPolicySavesHaveOneWinner() throws Exception {
    String access = adminToken(true);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks =
          java.util.stream.IntStream.range(0, 2)
              .mapToObj(
                  n ->
                      executor.submit(
                          () -> {
                            try {
                              auditPolicy.save(
                                  access,
                                  TERMINAL,
                                  new za.co.onlinepos.contracts.Auditing.SettingsEdit(
                                      UUID.randomUUID(), 0, tracking(false), "012345"));
                              return true;
                            } catch (org.springframework.dao.DataAccessException conflict) {
                              return false;
                            }
                          }))
              .toList();
      int success = 0;
      for (var task : tasks) if (task.get()) success++;
      assertEquals(1, success);
      assertEquals(
          1,
          ADMIN.queryForObject(
              "SELECT revision FROM pos.audit_policy WHERE branch_id=1", Long.class));
    }
  }
}
