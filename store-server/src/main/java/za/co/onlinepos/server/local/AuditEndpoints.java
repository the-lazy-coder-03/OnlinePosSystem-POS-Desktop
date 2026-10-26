package za.co.onlinepos.server.local;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.Auditing;

@RestController
@Profile({"installed", "local-store"})
@RequestMapping("/api/local/admin/audit")
public class AuditEndpoints {
  private final AuditService audit;
  private final AuditPolicyService policy;

  public AuditEndpoints(AuditService audit, AuditPolicyService policy) {
    this.audit = audit;
    this.policy = policy;
  }

  private String token(HttpServletRequest r) {
    return r.getHeader("Authorization").substring(7);
  }

  private String terminal(HttpServletRequest r) {
    return (String) r.getAttribute("pos.terminal");
  }

  @GetMapping("/types")
  public Object types(HttpServletRequest r) {
    policy.require(token(r), terminal(r), false);
    return Arrays.stream(Auditing.EventType.values())
        .map(t -> Map.of("action", t.name(), "category", t.category(), "optional", t.optional()))
        .toList();
  }

  @GetMapping("/settings")
  public Object settings(HttpServletRequest r) {
    return policy.settings(token(r), terminal(r));
  }

  @PutMapping("/settings")
  public Object save(HttpServletRequest r, @RequestBody Auditing.SettingsEdit edit) {
    return policy.save(token(r), terminal(r), edit);
  }

  @GetMapping("/events/{id}")
  public Object detail(HttpServletRequest r, @PathVariable("id") UUID id) {
    policy.require(token(r), terminal(r), true);
    return audit.detail(id);
  }

  @GetMapping("/events")
  public Object history(
      HttpServletRequest r,
      @RequestParam(name = "from", required = false) Instant from,
      @RequestParam(name = "to", required = false) Instant to,
      @RequestParam(name = "terminal", defaultValue = "") String terminal,
      @RequestParam(name = "staff", defaultValue = "") String staff,
      @RequestParam(name = "transaction", required = false) UUID transaction,
      @RequestParam(name = "category", defaultValue = "") String category,
      @RequestParam(name = "action", defaultValue = "") String action,
      @RequestParam(name = "cursor", defaultValue = "") String cursor) {
    policy.require(token(r), terminal(r), true);
    return audit.history(
        new AuditService.Search(from, to, terminal, staff, transaction, category, action, cursor));
  }
}
