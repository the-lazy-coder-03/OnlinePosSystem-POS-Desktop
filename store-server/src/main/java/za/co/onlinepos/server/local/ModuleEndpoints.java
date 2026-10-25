package za.co.onlinepos.server.local;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"installed", "local-store"})
public class ModuleEndpoints {
  private final ModuleAccess access;

  public ModuleEndpoints(ModuleAccess access) {
    this.access = access;
  }

  @PostMapping("/api/local/modules/enter")
  public Map<String, Object> enter(
      @RequestBody ModuleAccess.Entry entry, HttpServletRequest request) {
    return access.enter(entry, (String) request.getAttribute("pos.terminal"));
  }

  @GetMapping("/api/local/modules/me")
  public ModuleAccess.Session me(HttpServletRequest request) {
    return (ModuleAccess.Session) request.getAttribute("pos.module");
  }

  @PostMapping("/api/local/modules/activity")
  public ModuleAccess.Session activity(HttpServletRequest request) {
    return access.authenticate(
        request.getHeader("Authorization").substring(7),
        (String) request.getAttribute("pos.terminal"),
        true);
  }

  @PostMapping("/api/local/modules/logout")
  public Map<String, Boolean> logout(HttpServletRequest request) {
    access.logout(request.getHeader("Authorization").substring(7));
    return Map.of("loggedOut", true);
  }
}
