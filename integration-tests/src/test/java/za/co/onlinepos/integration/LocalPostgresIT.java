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
  private StaffService.Actor manager;
  private String token;

  @BeforeEach
  void fixture() {
    ADMIN.execute(
        "TRUNCATE pos.catalogue_bootstrap,pos.staff,pos.staff_session,pos.catalog_item,pos.branch_price,pos.local_customer,pos.order_draft,pos.order_line,pos.order_line_extra,pos.payment_metadata,pos.command_receipt,pos.audit_record,pos.outbox_event,pos.inbox_receipt,pos.terminal_printer CASCADE");
    ADMIN.update("UPDATE pos.stream_head SET position=0");
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
        2,
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
    var staffFilter = new za.co.onlinepos.server.StaffAuthenticationFilter(staff, null);
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
    operations.price(
        manager,
        price,
        new LocalCommands.PriceEdit(UUID.randomUUID(), 0, new BigDecimal("90.00"), true));
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
        ADMIN.execute("TRUNCATE pos.store_identity CASCADE");
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
      ADMIN.execute("TRUNCATE pos.store_identity CASCADE");
      ADMIN.update(
          "INSERT INTO pos.store_identity(branch_id,cluster_id,installation_id) VALUES (?,?,?)",
          IDENTITY.branchId(),
          IDENTITY.clusterId(),
          IDENTITY.installationId());
      ADMIN.update("INSERT INTO pos.stream_head(branch_id) VALUES (1)");
    }
  }
}
