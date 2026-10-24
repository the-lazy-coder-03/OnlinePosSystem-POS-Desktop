package za.co.onlinepos.server.local;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.LocalCommands;

@RestController
@Profile({"installed", "local-store"})
public class LocalEndpoints {
  private final StaffService staff;
  private final LocalOperations operations;
  private final LocalDatabase.Identity identity;

  public LocalEndpoints(
      StaffService staff, LocalOperations operations, LocalDatabase.Identity identity) {
    this.staff = staff;
    this.operations = operations;
    this.identity = identity;
  }

  private StaffService.Actor actor(HttpServletRequest request) {
    return (StaffService.Actor) request.getAttribute("pos.staff");
  }

  @PostMapping("/api/local/auth/login")
  public Map<String, Object> login(
      @RequestBody StaffService.Login login, HttpServletRequest request) {
    return staff.login(login, (String) request.getAttribute("pos.terminal"));
  }

  @PostMapping("/api/local/auth/logout")
  public Map<String, Boolean> logout(HttpServletRequest request) {
    staff.logout(request.getHeader("Authorization").substring(7));
    return Map.of("loggedOut", true);
  }

  @GetMapping("/api/local/auth/me")
  public StaffService.Actor me(HttpServletRequest request) {
    return actor(request);
  }

  @GetMapping("/api/local/menu")
  public List<Map<String, Object>> boundMenu() {
    return operations.menu();
  }

  @GetMapping("/api/local/branches/{branch}/menu")
  public List<Map<String, Object>> menu(@PathVariable("branch") int branch) {
    operations.branch(branch);
    return operations.menu();
  }

  @PatchMapping("/api/local/branches/{branch}/prices/{id}")
  public JsonNode price(
      @PathVariable("branch") int branch,
      @PathVariable("id") UUID id,
      @RequestBody LocalCommands.PriceEdit edit,
      HttpServletRequest request) {
    operations.branch(branch);
    return operations.price(actor(request), id, edit);
  }

  @PostMapping("/api/local/branches/{branch}/drafts")
  public JsonNode create(
      @PathVariable("branch") int branch,
      @RequestBody LocalCommands.CreateDraft draft,
      HttpServletRequest request) {
    operations.branch(branch);
    return operations.createDraft(actor(request), draft);
  }

  @GetMapping("/api/local/branches/{branch}/drafts/{id}")
  public JsonNode draft(@PathVariable("branch") int branch, @PathVariable("id") UUID id) {
    operations.branch(branch);
    return operations.draft(id);
  }

  @PostMapping("/api/local/branches/{branch}/drafts/{id}/cancel")
  public JsonNode cancel(
      @PathVariable("branch") int branch,
      @PathVariable("id") UUID id,
      @RequestBody LocalCommands.CancelDraft edit,
      HttpServletRequest request) {
    operations.branch(branch);
    return operations.cancel(actor(request), id, edit);
  }

  @GetMapping("/api/local/staff")
  public List<Map<String, Object>> staff(HttpServletRequest request) {
    return staff.list(actor(request));
  }

  @PutMapping("/api/local/staff")
  public JsonNode edit(@RequestBody StaffService.StaffEdit edit, HttpServletRequest request) {
    return staff.edit(actor(request), edit);
  }

  @GetMapping("/api/local/printer")
  public Map<String, Object> printer(HttpServletRequest request) {
    return operations.printer(actor(request));
  }

  @GetMapping("/api/local/configuration")
  public Map<String, Object> configuration() {
    return Map.of(
        "branchId",
        identity.branchId(),
        "clusterId",
        identity.clusterId(),
        "acceptingSales",
        false,
        "catalogueImportImplemented",
        false,
        "cloudSyncEnabled",
        false,
        "automaticPromotionEnabled",
        false);
  }
}
