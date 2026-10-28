package za.co.onlinepos.server.dev;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
@RequestMapping("/api/local/dev/data-manager/grid/store")
public class DevStoreEndpoints {
  private final DevStoreService stores;

  public DevStoreEndpoints(DevStoreService stores) {
    this.stores = stores;
  }

  public record Adopt(UUID commandId, int branchId, String businessName) {}

  @GetMapping
  public Object state() {
    return stores.state();
  }

  @PostMapping
  public Object setup(@RequestBody DevStoreService.Setup request) {
    return stores.setup(request);
  }

  @PutMapping
  public Object edit(@RequestBody DevStoreService.Edit request) {
    return stores.edit(request);
  }

  @PostMapping("/adopt")
  public Object adopt(@RequestBody Adopt request) {
    return stores.adopt(request.commandId(), request.branchId(), request.businessName());
  }
}
