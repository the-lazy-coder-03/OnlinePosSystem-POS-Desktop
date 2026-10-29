package za.co.onlinepos.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import za.co.onlinepos.installation.LanPolicy;

class LanBoundaryTest {
  @ParameterizedTest
  @ValueSource(strings = {"/api/local/setup/bootstrap", "/api/local/setup/store"})
  void socketPeerAndBoundInterfaceAreRequiredRegardlessOfForwardedHeaders(String path)
      throws Exception {
    var policy = new LanPolicy("adapter", "Ethernet", "192.168.1.5", 24);
    var filter = new TerminalAuthenticationFilter(null, policy);
    for (String[] pair :
        new String[][] {
          {"8.8.8.8", "192.168.1.5"},
          {"10.0.0.2", "192.168.1.5"},
          {"192.168.1.20", "10.0.0.5"},
          {"::1", "::1"}
        }) {
      var request = request(pair[0], pair[1], path);
      request.addHeader("X-Forwarded-For", "192.168.1.20");
      request.addHeader("Forwarded", "for=192.168.1.20");
      var response = new MockHttpServletResponse();
      filter.doFilter(
          request,
          response,
          (r, s) -> {
            throw new AssertionError("External interface admitted");
          });
      assertEquals(403, response.getStatus());
    }
    var reached = new AtomicBoolean();
    filter.doFilter(
        request("192.168.1.20", "192.168.1.5", path),
        new MockHttpServletResponse(),
        (r, s) -> reached.set(true));
    assertTrue(reached.get());
    var oversized = request("192.168.1.20", "192.168.1.5", path);
    oversized.setContent(new byte[16385]);
    var refused = new MockHttpServletResponse();
    filter.doFilter(
        oversized,
        refused,
        (r, s) -> {
          throw new AssertionError("Oversized bootstrap request admitted");
        });
    assertEquals(413, refused.getStatus());
  }

  private MockHttpServletRequest request(String peer, String local, String path) {
    var request = new MockHttpServletRequest("GET", path);
    request.setSecure(true);
    request.setRemoteAddr(peer);
    request.setLocalAddr(local);
    return request;
  }
}
