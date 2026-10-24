package za.co.onlinepos.server;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.HealthResponse;

@RestController
public class HealthController {
  private final org.springframework.beans.factory.ObjectProvider<
          za.co.onlinepos.installation.Installation>
      installation;

  public HealthController(
      org.springframework.beans.factory.ObjectProvider<za.co.onlinepos.installation.Installation>
          installation) {
    this.installation = installation;
  }

  @GetMapping("/api/local/health")
  public ResponseEntity<HealthResponse> health() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            installation.getIfAvailable() == null
                ? HealthResponse.foundation()
                : new HealthResponse(
                    1,
                    "onlinepos-store-server",
                    "UP",
                    installation.getObject().role().name(),
                    false,
                    false,
                    false));
  }
}
