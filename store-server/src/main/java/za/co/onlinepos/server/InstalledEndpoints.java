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

  @GetMapping("/api/local/setup/bootstrap")
  public Pairing.Bootstrap bootstrap() throws Exception {
    return registry.bootstrap();
  }

  @GetMapping("/api/local/setup/store")
  public Map<String, Object> setupStore() throws Exception {
    registry.bootstrap(); // Same active-main-computer requirement as bootstrap.
    var rows =
        jdbc.queryForList(
            "SELECT store_name,business_name,store_code FROM pos.store_identity WHERE branch_id=?",
            identity.branchId());
    var profile = identity.storeProfile();
    if (!rows.isEmpty()) {
      var row = rows.getFirst();
      if (row.get("store_name") instanceof String name
          && !name.isBlank()
          && row.get("business_name") instanceof String business
          && !business.isBlank()
          && row.get("store_code") instanceof String code
          && !code.isBlank())
        profile = new za.co.onlinepos.installation.StoreProfile(name, business, code);
    }
    return Map.of(
        "clusterId",
        identity.clusterId(),
        "branchId",
        identity.branchId(),
        "profile",
        profile == null ? Map.of() : profile);
  }

  @PostMapping("/api/local/setup/challenge")
  public Pairing.Challenge challenge(@RequestBody Pairing.JoinRequest request) throws Exception {
    try {
      return registry.challenge(request);
    } catch (SecurityException
        | IllegalArgumentException
        | java.security.GeneralSecurityException e) {
      throw refused();
    }
  }

  @PostMapping("/api/local/setup/enrol")
  public Pairing.Reply enrol(
      @RequestBody Pairing.Enrolment request, jakarta.servlet.http.HttpServletRequest http)
      throws Exception {
    try {
      return registry.enrol(request, http.getRemoteAddr());
    } catch (SecurityException
        | IllegalArgumentException
        | java.security.GeneralSecurityException e) {
      throw refused();
    }
  }

  @GetMapping("/api/local/terminal/heartbeat")
  public Map<String, Boolean> heartbeat() {
    return Map.of("connected", true);
  }

  private org.springframework.web.server.ResponseStatusException refused() {
    return new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.FORBIDDEN, "Pairing refused");
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
