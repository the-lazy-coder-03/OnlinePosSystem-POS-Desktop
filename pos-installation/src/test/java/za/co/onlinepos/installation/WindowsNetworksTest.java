package za.co.onlinepos.installation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Hardware acceptance against an explicitly supplied PC LAN; never changes network settings. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "POS_TEST_EXPECT_LAN_ADDRESS", matches = ".+")
class WindowsNetworksTest {
  @Test
  void detectsTheApprovedLanAndExcludesInternalAndHostOnlyInterfaces() throws Exception {
    var networks = WindowsNetworks.available();
    assertTrue(
        networks.stream()
            .anyMatch(n -> n.address().equals(System.getenv("POS_TEST_EXPECT_LAN_ADDRESS"))),
        "The PC's private physical/external-switch LAN must be offered");
    for (String excluded :
        System.getenv().getOrDefault("POS_TEST_EXCLUDE_LAN_ADDRESSES", "").split(","))
      if (!excluded.isBlank())
        assertFalse(
            networks.stream().anyMatch(n -> n.address().equals(excluded)),
            "Internal-switch and host-only adapters must not be offered");
  }
}
