package za.co.onlinepos.server.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.onlinepos.server.local.Passwords;

class PinIdentitySelectionTest {
  @Test
  void ambiguousIdentitiesRequireExactSelectionRegardlessOfOrdering() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    Map<String, Object> first = Map.of("staff_id", a, "enabled", true);
    Map<String, Object> second = Map.of("staff_id", b, "enabled", true);
    assertNull(PinIdentitySelection.select(List.of(first, second), null));
    assertNull(PinIdentitySelection.select(List.of(second, first), null));
    assertEquals(second, PinIdentitySelection.select(List.of(first, second), b));
    assertNull(PinIdentitySelection.select(List.of(first, second), UUID.randomUUID()));
    assertEquals(
        first,
        PinIdentitySelection.select(List.of(first, Map.of("staff_id", b, "enabled", false)), null));
    assertNull(PinIdentitySelection.select(List.of(first, first), a));
  }

  @Test
  void pinProviderVerifiesExistingHashesWithoutAcceptingOtherEvidence() {
    var provider = new PinAuthenticationProvider();
    char[] pin = "012345".toCharArray();
    String hash = Passwords.hashPin(pin);
    UUID staff = UUID.randomUUID();
    assertTrue(provider.verify(staff, pin, hash));
    assertFalse(provider.verify(staff, "999999".toCharArray(), hash));
    assertFalse(provider.verify(staff, "card-uid".toCharArray(), hash));
    assertFalse(provider.verify(staff, pin, "unsupported-verifier"));
    assertArrayEquals("012345".toCharArray(), pin);
  }
}
