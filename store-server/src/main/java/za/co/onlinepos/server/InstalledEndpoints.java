package za.co.onlinepos.server;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.installation.Installation;
import za.co.onlinepos.installation.Pairing;
import za.co.onlinepos.installation.TerminalRegistry;

@Profile("installed")
@RestController
public class InstalledEndpoints {
  private final Installation identity;
  private final TerminalRegistry registry;

  public InstalledEndpoints(Installation identity, TerminalRegistry registry) {
    this.identity = identity;
    this.registry = registry;
  }

  @GetMapping("/api/local/identity")
  public Pairing.Identity identity() {
    return new Pairing.Identity(
        identity.installationId(),
        identity.clusterId(),
        identity.branchId(),
        identity.role().name(),
        identity.state());
  }

  @GetMapping("/api/local/diagnostics")
  public Map<String, String> diagnostics() {
    return Map.of(
        "installation",
        identity.state(),
        "role",
        identity.role().name(),
        "sales",
        "UNAVAILABLE",
        "automaticPromotion",
        "DISABLED");
  }

  @PostMapping("/api/local/setup/pair")
  public Pairing.Reply pair(@RequestBody Pairing.Request request) throws Exception {
    try {
      return registry.redeem(request);
    } catch (SecurityException | IllegalArgumentException exception) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "Pairing refused");
    }
  }
}
