package za.co.onlinepos.server.local;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
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
import za.co.onlinepos.contracts.Administration;

@RestController
@Profile({"installed", "local-store"})
@RequestMapping("/api/local/admin")
public class AdministrationEndpoints {
  private final AdministrationService service;

  public AdministrationEndpoints(AdministrationService service) {
    this.service = service;
  }

  private String token(HttpServletRequest request) {
    return request.getHeader("Authorization").substring(7);
  }

  private String terminal(HttpServletRequest request) {
    return (String) request.getAttribute("pos.terminal");
  }

  @GetMapping("/overview")
  public Map<String, Object> overview(HttpServletRequest request) {
    return service.overview(token(request), terminal(request));
  }

  @GetMapping("/staff")
  public Map<String, Object> staff(
      HttpServletRequest request,
      @RequestParam(name = "search", defaultValue = "") String search,
      @RequestParam(name = "page", defaultValue = "0") int page) {
    return service.staff(token(request), terminal(request), search, page);
  }

  @GetMapping("/staff/options")
  public List<Map<String, Object>> options(HttpServletRequest request) {
    return service.roles(token(request), terminal(request), true);
  }

  @GetMapping("/roles")
  public List<Map<String, Object>> roles(HttpServletRequest request) {
    return service.roles(token(request), terminal(request), false);
  }

  @PutMapping("/staff")
  public JsonNode staff(HttpServletRequest request, @RequestBody Administration.StaffEdit edit) {
    return service.editStaff(token(request), terminal(request), edit);
  }

  @PutMapping("/roles")
  public JsonNode role(HttpServletRequest request, @RequestBody Administration.RoleEdit edit) {
    return service.editRole(token(request), terminal(request), edit);
  }

  @PutMapping("/staff/{id}/pin")
  public JsonNode pin(
      HttpServletRequest request,
      @PathVariable("id") UUID id,
      @RequestBody Administration.PinEdit edit) {
    return service.editPin(token(request), terminal(request), id, edit);
  }

  @GetMapping("/audit")
  public Map<String, Object> audit(
      HttpServletRequest request,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "actor", required = false) String actor,
      @RequestParam(name = "action", required = false) String action,
      @RequestParam(name = "from", required = false) Instant from,
      @RequestParam(name = "to", required = false) Instant to) {
    return service.audit(token(request), terminal(request), page, actor, action, from, to);
  }
}
