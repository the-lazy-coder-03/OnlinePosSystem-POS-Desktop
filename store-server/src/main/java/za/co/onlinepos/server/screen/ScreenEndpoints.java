package za.co.onlinepos.server.screen;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.screen.SalesScreens;
import za.co.onlinepos.server.local.LocalFailure;

@RestController
@Profile({"installed", "local-store", "dev"})
@RequestMapping({"/api/local/sales-screens", "/api/local/dev/data-manager/screens"})
public class ScreenEndpoints {
  private final ScreenService service;

  public ScreenEndpoints(ScreenService service) {
    this.service = service;
  }

  private String token(HttpServletRequest r) {
    String header = r.getHeader("X-Screen-Session");
    if (header != null) return header;
    header = r.getHeader("Authorization");
    return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
  }

  private String terminal(HttpServletRequest r) {
    var terminal = r.getAttribute("pos.terminal");
    return terminal == null ? r.getHeader("X-Screen-Terminal") : terminal.toString();
  }

  public record Access(String pin, UUID staffId) {
    @Override
    public String toString() {
      return "ScreenAccess[REDACTED]";
    }
  }

  @PostMapping("/access")
  public Object access(HttpServletRequest r, @RequestBody Access access) {
    return service.access(access.pin(), access.staffId(), terminal(r));
  }

  @GetMapping("/context")
  public Object context(HttpServletRequest r) {
    return service.context(token(r), terminal(r));
  }

  @GetMapping("/revisions/{id}/{revision}")
  public Object revision(HttpServletRequest r, @PathVariable UUID id, @PathVariable long revision) {
    return service.authorRevision(token(r), terminal(r), id, revision);
  }

  @GetMapping("/history/{id}")
  public Object history(HttpServletRequest r, @PathVariable UUID id) {
    return service.history(token(r), terminal(r), id);
  }

  @PostMapping("/save")
  public Object save(HttpServletRequest r, @RequestBody SalesScreens.Save save) {
    return service.save(token(r), terminal(r), save);
  }

  @PostMapping("/preview")
  public Object preview(HttpServletRequest r, @RequestBody SalesScreens.Document doc) {
    return service.preview(token(r), terminal(r), doc);
  }

  @PostMapping("/publish")
  public Object publish(HttpServletRequest r, @RequestBody SalesScreens.Publish publish) {
    return service.publish(token(r), terminal(r), publish, false);
  }

  @PostMapping("/rollback")
  public Object rollback(HttpServletRequest r, @RequestBody SalesScreens.Publish publish) {
    return service.publish(token(r), terminal(r), publish, true);
  }

  @PostMapping("/workflows/approve")
  public Object workflow(HttpServletRequest r, @RequestBody SalesScreens.WorkflowRequest request) {
    return service.approvedWorkflow(token(r), terminal(r), request);
  }

  @GetMapping("/assets/{hash}")
  public Object asset(@PathVariable String hash) {
    return service.asset(hash);
  }

  @PostMapping("/assets")
  public Object upload(HttpServletRequest r, @RequestBody SalesScreens.Asset asset) {
    return service.upload(token(r), terminal(r), asset);
  }

  @GetMapping("/publication")
  public Object publication() {
    return service.published();
  }

  @GetMapping("/events")
  public Object events(@RequestParam(defaultValue = "0") long after) {
    return service.events(after);
  }

  @ExceptionHandler(LocalFailure.class)
  public ResponseEntity<?> denied(LocalFailure e) {
    return ResponseEntity.status(e.status()).body(java.util.Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<?> invalid(IllegalArgumentException e) {
    return ResponseEntity.badRequest()
        .body(
            java.util.Map.of("error", e.getMessage() == null ? "Invalid screen" : e.getMessage()));
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public ResponseEntity<?> database(org.springframework.dao.DataAccessException e) {
    return ResponseEntity.status(409)
        .body(
            java.util.Map.of(
                "error",
                "Screen operation not confirmed. Keep the draft and retry with the same command."));
  }
}
