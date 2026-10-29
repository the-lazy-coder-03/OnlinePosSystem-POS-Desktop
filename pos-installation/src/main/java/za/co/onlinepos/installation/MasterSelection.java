package za.co.onlinepos.installation;

import java.net.URI;
import java.util.UUID;

/** A previewed server identity; provisioning verifies it again before joining. */
public record MasterSelection(
    String origin, int branchId, UUID clusterId, String caFingerprint, StoreProfile profile) {
  public MasterSelection {
    var uri = URI.create(origin);
    if (!"https".equals(uri.getScheme())
        || !LanPolicy.privateAddress(uri.getHost())
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !uri.getPath().isEmpty()
        || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))
        || branchId <= 0
        || clusterId == null
        || caFingerprint == null
        || caFingerprint.isBlank())
      throw new IllegalArgumentException("Select a valid main computer");
  }

  @Override
  public String toString() {
    return (profile == null ? "Store " + branchId : profile.storeName())
        + " · "
        + URI.create(origin).getHost();
  }
}
