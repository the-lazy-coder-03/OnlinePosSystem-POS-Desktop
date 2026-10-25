package za.co.onlinepos.server.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.CatalogueManagement;

@RestController
@Profile({"installed", "local-store"})
@RequestMapping("/api/local/catalogue")
public class CatalogueManagementEndpoints {
  private final CatalogueManagementService service;

  public CatalogueManagementEndpoints(CatalogueManagementService service) {
    this.service = service;
  }

  @GetMapping("/state")
  public JsonNode state(HttpServletRequest request) {
    return service.state(
        request.getHeader("Authorization").substring(7),
        (String) request.getAttribute("pos.terminal"));
  }

  @PutMapping("/records")
  public JsonNode edit(HttpServletRequest request, @RequestBody CatalogueManagement.Edit edit) {
    return service.edit(
        request.getHeader("Authorization").substring(7),
        (String) request.getAttribute("pos.terminal"),
        edit);
  }
}
