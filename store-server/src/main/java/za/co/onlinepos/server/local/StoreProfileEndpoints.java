package za.co.onlinepos.server.local;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"installed", "local-store"})
@RequestMapping("/api/local/admin/store")
public class StoreProfileEndpoints {
  private final StoreProfileService service;

  public StoreProfileEndpoints(StoreProfileService service) {
    this.service = service;
  }

  private String token(HttpServletRequest request) {
    return request.getHeader("Authorization").substring(7);
  }

  private String terminal(HttpServletRequest request) {
    return (String) request.getAttribute("pos.terminal");
  }

  @GetMapping
  public Object get(HttpServletRequest request) {
    return service.state(token(request), terminal(request));
  }

  @PostMapping
  public Object setup(HttpServletRequest request, @RequestBody JsonNode input) {
    return service.save(token(request), terminal(request), input);
  }

  @PutMapping
  public Object put(HttpServletRequest request, @RequestBody JsonNode input) {
    return service.save(token(request), terminal(request), input);
  }
}
