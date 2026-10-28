package za.co.onlinepos.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigurationSyncTest {
  @Test
  void rollbackAndRemoteObservationPreserveCausalOrder() {
    UUID source = UUID.randomUUID();
    var first = ConfigurationSync.tick(1000, null, null, source, UUID.randomUUID());
    var rollback = ConfigurationSync.tick(1, first, null, source, UUID.randomUUID());
    assertTrue(rollback.compareTo(first) > 0);
    var remote = ConfigurationSync.tick(2000, null, null, UUID.randomUUID(), UUID.randomUUID());
    var received = ConfigurationSync.tick(2, rollback, remote, source, UUID.randomUUID());
    assertTrue(received.compareTo(remote) > 0);
    assertEquals(2000, received.utcMillis());
  }

  @Test
  void deterministicTieAndSecurityFieldsAreRejected() {
    UUID source = new UUID(0, 1), a = new UUID(0, 2), b = new UUID(0, 3);
    var older = new ConfigurationSync.Version(1000, 0, source, a);
    var newer = new ConfigurationSync.Version(1000, 0, source, b);
    assertTrue(newer.compareTo(older) > 0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ConfigurationSync.Change(
                a,
                new ConfigurationSync.Scope(source, b),
                ConfigurationSync.Entity.PRODUCT,
                a,
                ConfigurationSync.Operation.UPDATE,
                Map.of("role", "OWNER"),
                Map.of("role", newer)));
  }
}
