package za.co.onlinepos.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomersTest {
  private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

  private CustomerSync.Snapshot snapshot(
      UUID id, String phone, String email, String city, Instant time) {
    return new CustomerSync.Snapshot(
        id,
        null,
        1,
        Map.of(
            "first_name",
            "Test",
            "phone1",
            phone,
            "email",
            email,
            "city",
            city,
            "street",
            city + " Street",
            "country",
            "ZA",
            "preferred_store",
            "Kenridge"),
        Map.of(
            "first_name",
            time,
            "last_name",
            time,
            "phone1",
            time,
            "phone2",
            time,
            "email",
            time,
            "address",
            time,
            "preferred_store",
            time),
        false,
        "",
        time);
  }

  @Test
  void validationNormalizesContactsAndRejectsCredentialsAndMissingIdentity() {
    assertEquals("+27821234567", Customers.phone("(082) 123-4567", true));
    assertEquals("+442012345678", Customers.phone("0044 20 1234 5678", true));
    assertEquals("+27821234567", Customers.phone("+27 82 123 4567", true));
    assertThrows(
        IllegalArgumentException.class, () -> Customers.validate(Map.of("first_name", "Only")));
    assertThrows(
        IllegalArgumentException.class, () -> Customers.validate(Map.of("phone1", "0821234567")));
    assertThrows(
        IllegalArgumentException.class, () -> Customers.validate(Map.of("password", "secret")));
    assertThrows(IllegalArgumentException.class, () -> Customers.phone("082", true));
    assertEquals("", Customers.phone("", false));
  }

  @Test
  void phoneMatchIsUniqueAndContactDisagreementPausesApplication() {
    var a = snapshot(UUID.randomUUID(), "0821234567", "a@example.test", "Local", NOW);
    var b = snapshot(UUID.randomUUID(), "0831234567", "b@example.test", "Other", NOW);
    var remote = snapshot(UUID.randomUUID(), "+27821234567", "b@example.test", "Cloud", NOW);
    assertEquals("LINK", CustomerSync.match(List.of(a), remote).status());
    assertEquals("CONFLICT", CustomerSync.match(List.of(a, b), remote).status());
    assertEquals(
        "CONFLICT",
        CustomerSync.match(
                List.of(a, snapshot(UUID.randomUUID(), "0821234567", "", "Duplicate", NOW)), remote)
            .status());
    assertEquals("CREATE", CustomerSync.match(List.of(), remote).status());
  }

  @Test
  void laterFieldsWinWithAtomicAddressAndCloudWinsEqualTime() {
    UUID id = UUID.randomUUID();
    var local = snapshot(id, "0821234567", "local@example.test", "Local", NOW);
    var cloud = snapshot(id, "0821234567", "cloud@example.test", "Cloud", NOW);
    assertEquals("Cloud", CustomerSync.merge(local, cloud).fields().get("city"));
    assertEquals("cloud@example.test", CustomerSync.merge(local, cloud).fields().get("email"));
    assertEquals(
        "Local",
        CustomerSync.merge(
                local, snapshot(id, "0821234567", "old@example.test", "Old", NOW.minusSeconds(1)))
            .fields()
            .get("city"));
    var times = new HashMap<>(cloud.fieldTimes());
    times.put("address", NOW.minusSeconds(1));
    var mixed =
        new CustomerSync.Snapshot(
            id, null, 1, cloud.fields(), times, true, "Review", NOW.plusSeconds(1));
    var merged = CustomerSync.merge(local, mixed);
    assertEquals("Local", merged.fields().get("city"));
    assertEquals("cloud@example.test", merged.fields().get("email"));
    assertTrue(merged.banned());
    assertEquals("Review", merged.banReason());
    CustomerSync.requireClock(NOW, NOW.plusSeconds(120));
    assertThrows(
        IllegalStateException.class, () -> CustomerSync.requireClock(NOW, NOW.plusSeconds(121)));
  }

  @Test
  void disabledAdapterContractChecksOriginReplayClockAndAcknowledgements() throws Exception {
    var received = new HashMap<UUID, String>();
    var change =
        new CustomerSync.Change(
            UUID.randomUUID(),
            "trusted-cloud",
            NOW,
            snapshot(UUID.randomUUID(), "0821234567", "", "Cloud", NOW));
    UUID pending = UUID.randomUUID();
    var acknowledgements = new java.util.ArrayList<CustomerSync.Ack>();
    var repo =
        new CustomerSync.Repository() {
          public String checkpoint() {
            return "";
          }

          public String receivedDigest(UUID id) {
            return received.get(id);
          }

          public void apply(CustomerSync.Change e) {
            received.put(e.eventId(), e.digest());
          }

          public void acknowledge(CustomerSync.Ack ack) {
            acknowledgements.add(ack);
          }

          public List<Map<String, Object>> pending(int limit) {
            return acknowledgements.isEmpty()
                ? List.of(Map.of("eventId", pending, "digest", "a".repeat(64)))
                : List.of();
          }
        };
    var transport =
        new CustomerSync.Transport() {
          public String source() {
            return "trusted-cloud";
          }

          public Instant serverTime() {
            return NOW;
          }

          public List<CustomerSync.Change> changes(int branch, String cursor, int limit) {
            assertEquals(1, branch);
            return List.of(change);
          }

          public CustomerSync.Snapshot lookup(String phone) {
            assertEquals("+27821234567", phone);
            return change.snapshot();
          }

          public CustomerSync.Ack send(UUID id, String digest, Map<String, Object> data) {
            return new CustomerSync.Ack(id, digest, "ack-1", change.snapshot().globalId(), 7L);
          }
        };
    var adapter = new CustomerSync.Adapter(transport, repo, 1);
    adapter.exchange(NOW);
    adapter.exchange(NOW);
    assertEquals(1, received.size());
    assertEquals(1, acknowledgements.size());
    assertEquals(1, adapter.lookup("0821234567", NOW).preferredBranch());
    assertThrows(IllegalStateException.class, () -> adapter.exchange(NOW.plusSeconds(121)));
    received.put(change.eventId(), "changed");
    assertThrows(IllegalStateException.class, () -> adapter.exchange(NOW));
  }
}
