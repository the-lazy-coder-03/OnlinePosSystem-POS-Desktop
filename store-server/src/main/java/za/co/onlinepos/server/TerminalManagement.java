package za.co.onlinepos.server;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.PosModule;
import za.co.onlinepos.installation.TerminalRegistry;
import za.co.onlinepos.server.local.AdministrationService;
import za.co.onlinepos.server.local.LocalFailure;
import za.co.onlinepos.server.local.ModuleAccess;

@RestController
@Profile("installed")
@RequestMapping("/api/local/admin/terminals")
public class TerminalManagement {
  private final TerminalRegistry registry;
  private final za.co.onlinepos.installation.Installation installation;
  private final ModuleAccess modules;
  private final AdministrationService administration;

  public TerminalManagement(
      TerminalRegistry registry,
      ModuleAccess modules,
      AdministrationService administration,
      za.co.onlinepos.installation.Installation installation) {
    this.installation = installation;
    this.registry = registry;
    this.modules = modules;
    this.administration = administration;
  }

  private void require(HttpServletRequest request) {
    var header = request.getHeader("Authorization");
    var actor =
        modules.authenticate(
            header != null && header.startsWith("Bearer ") ? header.substring(7) : null,
            (String) request.getAttribute("pos.terminal"),
            false);
    if (actor.module() != PosModule.ADMINISTRATION
        || (!administration.owner(actor.staffId()) && !actor.permissions().contains("rbac.manage")))
      throw new LocalFailure(403, "Terminal management permission required");
  }

  @GetMapping
  public List<TerminalRegistry.TerminalView> list(HttpServletRequest request) throws Exception {
    require(request);
    return registry.terminals().stream()
        .filter(t -> !t.terminalId().equals(installation.terminalId()))
        .toList();
  }

  public record Edit(String name, boolean revoke) {}

  @PutMapping("/{id}")
  public Map<String, Boolean> edit(
      @PathVariable("id") UUID id, @RequestBody Edit edit, HttpServletRequest request)
      throws Exception {
    require(request);
    if (edit.revoke()) registry.revoke(id);
    else registry.rename(id, edit.name());
    return Map.of("updated", true);
  }
}
