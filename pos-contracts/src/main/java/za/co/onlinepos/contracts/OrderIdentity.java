package za.co.onlinepos.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.UUID;

/** No database-local numeric key or human receipt number is a synchronisation identity. */
public record OrderIdentity(@JsonProperty("sync_id") UUID syncId, int branchId) {
  public OrderIdentity {
    Objects.requireNonNull(syncId, "syncId");
    if (syncId.equals(new UUID(0, 0))) {
      throw new IllegalArgumentException("syncId must not be nil");
    }
    Branch.fromId(branchId);
  }

  public static OrderIdentity create(Branch branch) {
    return new OrderIdentity(UUID.randomUUID(), branch.id());
  }
}
