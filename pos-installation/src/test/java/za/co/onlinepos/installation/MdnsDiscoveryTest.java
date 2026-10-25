package za.co.onlinepos.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class MdnsDiscoveryTest {
  @Test
  void advertisedAddressPrecedesGeneratedAliasAbsentFromMasterCertificate() throws Exception {
    var address = (Inet4Address) InetAddress.getByName("192.168.1.16");
    assertEquals(
        List.of("https://192.168.1.16:8443", "https://192-168-1-16.local:8443"),
        MdnsDiscovery.endpoints(
            "192-168-1-16.local.", 8443, new Inet4Address[] {address, address}));
  }

  @Test
  void malformedNamesCannotHideUsableAddressesOrInjectAnAuthority() throws Exception {
    var address = (Inet4Address) InetAddress.getByName("192.168.1.16");
    assertEquals(
        List.of("https://192.168.1.16:8443"),
        MdnsDiscovery.endpoints("user@untrusted.local/path", 8443, new Inet4Address[] {address}));
    assertEquals(List.of(), MdnsDiscovery.endpoints("master.local.", 65536, new Inet4Address[0]));
  }

  @Test
  void validHostnameRemainsAvailableWhenAddressRecordsHaveNotResolved() {
    assertEquals(
        List.of("https://master.local:8443"),
        MdnsDiscovery.endpoints("master.local.", 8443, new Inet4Address[0]));
  }
}
