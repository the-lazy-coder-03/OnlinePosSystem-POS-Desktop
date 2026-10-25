package za.co.onlinepos.server.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.CatalogueSnapshot;
import za.co.onlinepos.server.local.StaffService;

@RestController
@Profile({"installed", "local-store"})
public class CatalogueEndpoints {
  private final CatalogueImport importer;

  public CatalogueEndpoints(CatalogueImport importer) {
    this.importer = importer;
  }

  @PostMapping("/api/local/branches/{branch}/catalogue/bootstrap")
  public JsonNode bootstrap(
      @PathVariable("branch") int branch,
      @RequestBody CatalogueSnapshot.Import command,
      HttpServletRequest request) {
    return importer.apply((StaffService.Actor) request.getAttribute("pos.staff"), branch, command);
  }

  @GetMapping("/api/local/branches/{branch}/catalogue")
  public JsonNode catalogue(@PathVariable("branch") int branch) {
    return importer.catalogue(branch);
  }
}
