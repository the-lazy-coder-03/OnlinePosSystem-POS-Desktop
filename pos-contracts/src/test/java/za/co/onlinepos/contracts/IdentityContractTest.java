package za.co.onlinepos.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityContractTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void retryRoundTripPreservesOriginalCommandAndOrderIdentities() throws Exception {
    CommandIdentity original =
        new CommandIdentity(
            UUID.randomUUID(), UUID.randomUUID(), OrderIdentity.create(Branch.KENRIDGE), 7);
    String payload = json.writeValueAsString(original);
    assertTrue(payload.contains("\"sync_id\""));
    assertEquals(original, json.readValue(payload, CommandIdentity.class));
  }

  @Test
  void unrelatedOfflineOrdersDoNotUseNumericDatabaseIdentities() {
    OrderIdentity first = OrderIdentity.create(Branch.KENRIDGE);
    OrderIdentity second = OrderIdentity.create(Branch.KENRIDGE);
    assertNotEquals(first.syncId(), second.syncId());
    assertEquals(4, first.syncId().version());
  }

  @Test
  void invalidBranchNilIdentityAndNegativeRevisionAreRejected() {
    assertEquals(3, Branch.fromId(3).id());
    assertThrows(IllegalArgumentException.class, () -> Branch.fromId(0));
    assertThrows(IllegalArgumentException.class, () -> new OrderIdentity(new UUID(0, 0), 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CommandIdentity(
                UUID.randomUUID(), UUID.randomUUID(), OrderIdentity.create(Branch.UITZICHT), -1));
  }

  @Test
  void seedBranchIdentityAndHealthWireShapeRemainExplicit() throws Exception {
    assertEquals(1, Branch.KENRIDGE.id());
    assertEquals(Branch.UITZICHT, Branch.fromId(2));
    var payload = json.readTree(json.writeValueAsString(HealthResponse.foundation()));
    assertEquals(1, payload.path("protocolVersion").asInt());
    assertEquals("UNCONFIGURED", payload.path("role").asText());
    assertEquals(false, payload.path("acceptingSales").asBoolean());
  }
}
