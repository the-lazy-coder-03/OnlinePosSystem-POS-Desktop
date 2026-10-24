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
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;

  public InstalledEndpoints(
      Installation identity,
      TerminalRegistry registry,
      org.springframework.jdbc.core.JdbcTemplate jdbc) {
    this.identity = identity;
    this.registry = registry;
    this.jdbc = jdbc;
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
    String database;
    try {
      database = jdbc.queryForObject("SELECT 1", Integer.class) == 1 ? "READY" : "UNAVAILABLE";
    } catch (org.springframework.dao.DataAccessException exception) {
      database = "UNAVAILABLE";
    }
    return Map.of(
        "database",
        database,
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
