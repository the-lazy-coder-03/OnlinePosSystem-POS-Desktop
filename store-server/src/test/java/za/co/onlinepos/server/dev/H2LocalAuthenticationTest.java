package za.co.onlinepos.server.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import za.co.onlinepos.contracts.PosModule;
import za.co.onlinepos.server.StoreServerApplication;
import za.co.onlinepos.server.local.LocalFailure;

class H2LocalAuthenticationTest {
  @TempDir Path directory;

  org.springframework.context.ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(StoreServerApplication.class)
        .profiles("dev")
        .run(
            "--server.port=0",
            "--pos.dev.data-dir=" + directory.resolve("db"),
            "--pos.dev.legacy-dir=" + directory.resolve("absent"));
  }

  void setup(org.springframework.context.ConfigurableApplicationContext context) {
    context
        .getBean(DevStoreService.class)
        .setup(new DevStoreService.Setup(UUID.randomUUID(), "Independent", "Local store", "LOCAL"));
  }

  static class Time extends Clock {
    Instant now = Instant.parse("2026-10-28T12:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void ownerAndStaffPinSessionsPersistWithoutDefaultAccountsOrSecretReceipts() throws Exception {
    UUID owner;
    String session;
    try (var context = start()) {
      setup(context);
      var store = context.getBean(DevModuleStore.class);
      var auth = context.getBean(H2LocalAuthentication.class);
      assertEquals(
          0, store.jdbc.queryForObject("SELECT count(*) FROM dev_meta.local_staff", Integer.class));
      UUID command = UUID.randomUUID();
      var first = auth.bootstrapOwner(command, "owner.local", "012345".toCharArray());
      assertEquals(first, auth.bootstrapOwner(command, "owner.local", "012345".toCharArray()));
      owner = UUID.fromString(first.path("staffId").asText());
      assertThrows(
          LocalFailure.class,
          () -> auth.bootstrapOwner(UUID.randomUUID(), "other.owner", "999999".toCharArray()));
      assertEquals(
          401,
          assertThrows(
                  LocalFailure.class,
                  () ->
                      auth.enter(
                          "012345".toCharArray(),
                          UUID.randomUUID(),
                          PosModule.ADMINISTRATION,
                          "terminal-a",
                          false))
              .status());
      var normal =
          auth.enter("012345".toCharArray(), owner, PosModule.ADMINISTRATION, "terminal-a", false);
      assertEquals(
          403,
          assertThrows(
                  LocalFailure.class,
                  () ->
                      auth.createStaff(
                          normal.token(),
                          "terminal-a",
                          UUID.randomUUID(),
                          "cashier.one",
                          "CASHIER",
                          "111222".toCharArray()))
              .status());
      var verified =
          auth.enter("012345".toCharArray(), owner, PosModule.ADMINISTRATION, "terminal-a", true);
      var employee =
          auth.createStaff(
              verified.token(),
              "terminal-a",
              UUID.randomUUID(),
              "cashier.one",
              "CASHIER",
              "111222".toCharArray());
      assertTrue(verified.ownerVerified());
      assertThrows(
          LocalFailure.class,
          () ->
              auth.createStaff(
                  verified.token(),
                  "terminal-a",
                  UUID.randomUUID(),
                  "duplicate.pin",
                  "CASHIER",
                  "111222".toCharArray()));
      assertEquals(
          2, store.jdbc.queryForObject("SELECT count(*) FROM dev_meta.local_staff", Integer.class));
      assertEquals(
          403,
          assertThrows(
                  LocalFailure.class,
                  () ->
                      auth.enter(
                          "111222".toCharArray(),
                          null,
                          PosModule.ADMINISTRATION,
                          "terminal-b",
                          false))
              .status());
      var cashier =
          auth.enter(
              "111222".toCharArray(),
              UUID.fromString(employee.path("staffId").asText()),
              PosModule.SALES_ORDERS,
              "terminal-b",
              false);
      assertFalse(cashier.permissions().contains("staff.manage"));
      assertEquals(
          401,
          assertThrows(
                  LocalFailure.class,
                  () -> auth.authenticate(cashier.token(), "terminal-a", PosModule.SALES_ORDERS))
              .status());
      assertEquals(
          401,
          assertThrows(
                  LocalFailure.class,
                  () -> auth.authenticate(cashier.token(), "terminal-b", PosModule.CUSTOMERS))
              .status());
      UUID employeeId = UUID.fromString(employee.path("staffId").asText());
      auth.editStaff(
          verified.token(),
          "terminal-a",
          UUID.randomUUID(),
          employeeId,
          0,
          "cashier.one",
          "CASHIER",
          true,
          "222333".toCharArray());
      assertEquals(
          401,
          assertThrows(
                  LocalFailure.class,
                  () -> auth.authenticate(cashier.token(), "terminal-b", PosModule.SALES_ORDERS))
              .status());
      assertEquals(
          401,
          assertThrows(
                  LocalFailure.class,
                  () ->
                      auth.enter(
                          "111222".toCharArray(),
                          employeeId,
                          PosModule.SALES_ORDERS,
                          "terminal-b",
                          false))
              .status());
      assertNotNull(
          auth.enter(
              "222333".toCharArray(), employeeId, PosModule.SALES_ORDERS, "terminal-b", false));
      assertEquals(
          409,
          assertThrows(
                  LocalFailure.class,
                  () ->
                      auth.editStaff(
                          verified.token(),
                          "terminal-a",
                          UUID.randomUUID(),
                          employeeId,
                          0,
                          "cashier.one",
                          "CASHIER",
                          false,
                          null))
              .status());
      assertEquals(
          409,
          assertThrows(
                  LocalFailure.class,
                  () ->
                      auth.editStaff(
                          verified.token(),
                          "terminal-a",
                          UUID.randomUUID(),
                          owner,
                          0,
                          "owner.local",
                          "MANAGER",
                          false,
                          null))
              .status());
      session = normal.token();
      String receipts =
          store.jdbc.queryForList("SELECT result_json FROM dev_meta.commands").toString();
      String history =
          store.jdbc.queryForList("SELECT payload FROM dev_meta.module_events").toString();
      assertFalse((receipts + history).contains("012345"));
      assertFalse((receipts + history).contains("111222"));
      assertNotNull(
          store.jdbc.queryForObject(
              "SELECT verifier FROM dev_meta.local_auth_method WHERE staff_id=?",
              String.class,
              owner));
    }
    try (var context = start()) {
      var auth = context.getBean(H2LocalAuthentication.class);
      assertEquals(
          owner, auth.authenticate(session, "terminal-a", PosModule.ADMINISTRATION).staffId());
      auth.logout(session, "terminal-a", PosModule.ADMINISTRATION);
      assertEquals(
          401,
          assertThrows(
                  LocalFailure.class,
                  () -> auth.authenticate(session, "terminal-a", PosModule.ADMINISTRATION))
              .status());
      assertNotNull(
          auth.enter("012345".toCharArray(), owner, PosModule.CUSTOMERS, "terminal-a", false));
    }
    assertTrue(Files.exists(directory.resolve("db/local-pin.key")));
  }

  @Test
  void auditFailuresDoNotCreateSessionsOrEraseGuessCounters() {
    try (var context = start()) {
      setup(context);
      var store = context.getBean(DevModuleStore.class);
      var auth = context.getBean(H2LocalAuthentication.class);
      auth.bootstrapOwner(UUID.randomUUID(), "owner.local", "012345".toCharArray());
      store.jdbc.execute(
          "ALTER TABLE dev_meta.module_events ADD CONSTRAINT reject_auth_test CHECK(action NOT IN ('OWNER_PIN_FAILED','STAFF_LOGIN'))");
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () ->
              auth.enter(
                  "999999".toCharArray(), null, PosModule.ADMINISTRATION, "terminal-a", true));
      assertEquals(
          1,
          store.jdbc.queryForObject(
              "SELECT failures FROM dev_meta.local_pin_throttle WHERE scope='terminal:terminal-a'",
              Integer.class));
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> auth.enter("012345".toCharArray(), null, PosModule.CUSTOMERS, "terminal-a", false));
      assertEquals(
          0,
          store.jdbc.queryForObject(
              "SELECT count(*) FROM dev_meta.local_module_session", Integer.class));
      assertEquals(
          1,
          store.jdbc.queryForObject(
              "SELECT failures FROM dev_meta.local_pin_throttle WHERE scope='terminal:terminal-a'",
              Integer.class));
      store.jdbc.execute("ALTER TABLE dev_meta.module_events DROP CONSTRAINT reject_auth_test");
    }
  }

  @Test
  void missingProtectedKeyNeverSilentlyReplacesExistingCredentials() throws Exception {
    try (var context = start()) {
      setup(context);
      context
          .getBean(H2LocalAuthentication.class)
          .bootstrapOwner(UUID.randomUUID(), "owner.local", "012345".toCharArray());
    }
    Files.delete(directory.resolve("db/local-pin.key"));
    assertThrows(RuntimeException.class, this::start);
    assertFalse(Files.exists(directory.resolve("db/local-pin.key")));
  }

  @Test
  void failedAttemptsSurviveRollbackAndCooldownAndOwnerExpiryAreEnforced() {
    try (var context = start()) {
      setup(context);
      var store = context.getBean(DevModuleStore.class);
      var time = new Time();
      var auth =
          new H2LocalAuthentication(
              store, java.util.Base64.getEncoder().encodeToString(new byte[32]), time);
      auth.bootstrapOwner(UUID.randomUUID(), "owner.local", "012345".toCharArray());
      for (int n = 0; n < 5; n++)
        assertEquals(
            401,
            assertThrows(
                    LocalFailure.class,
                    () ->
                        auth.enter(
                            "999999".toCharArray(),
                            null,
                            PosModule.ADMINISTRATION,
                            "terminal-a",
                            true))
                .status());
      assertEquals(
          429,
          assertThrows(
                  LocalFailure.class,
                  () ->
                      auth.enter(
                          "012345".toCharArray(),
                          null,
                          PosModule.ADMINISTRATION,
                          "terminal-a",
                          true))
              .status());
      assertEquals(
          5,
          store.jdbc.queryForObject(
              "SELECT count(*) FROM dev_meta.module_events WHERE action='OWNER_PIN_FAILED'",
              Integer.class));
      time.now = time.now.plusSeconds(301);
      var owner =
          auth.enter("012345".toCharArray(), null, PosModule.ADMINISTRATION, "terminal-a", true);
      time.now = time.now.plusSeconds(61);
      assertEquals(
          401,
          assertThrows(
                  LocalFailure.class,
                  () -> auth.authenticate(owner.token(), "terminal-a", PosModule.ADMINISTRATION))
              .status());
      var ordinary =
          auth.enter("012345".toCharArray(), null, PosModule.CUSTOMERS, "terminal-a", false);
      store.jdbc.update("UPDATE dev_meta.local_staff SET credential_version=credential_version+1");
      assertEquals(
          401,
          assertThrows(
                  LocalFailure.class,
                  () -> auth.authenticate(ordinary.token(), "terminal-a", PosModule.CUSTOMERS))
              .status());
      assertEquals(
          401,
          assertThrows(
                  LocalFailure.class,
                  () ->
                      auth.enter(
                          "012345".toCharArray(), null, PosModule.CUSTOMERS, "terminal-a", false))
              .status());
    }
  }
}
