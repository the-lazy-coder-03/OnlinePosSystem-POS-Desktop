package za.co.onlinepos.server;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.HealthResponse;

@RestController
public class HealthController {
  @GetMapping("/api/local/health")
  public ResponseEntity<HealthResponse> health() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(HealthResponse.foundation());
  }
}
