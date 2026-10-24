package za.co.onlinepos.installation;

import java.util.Objects;
import java.util.UUID;
import za.co.onlinepos.contracts.Branch;
import za.co.onlinepos.contracts.InstallationRole;

public record Installation(
    int version,
    UUID installationId,
    UUID clusterId,
    UUID terminalId,
    int branchId,
    InstallationRole role,
    String terminalName,
    String state) {
  public Installation {
    if (version != 1) throw new IllegalArgumentException("Unsupported installation version");
    Objects.requireNonNull(installationId);
    Objects.requireNonNull(clusterId);
    Objects.requireNonNull(terminalId);
    Branch.fromId(branchId);
    Objects.requireNonNull(role);
    Objects.requireNonNull(state);
    if (terminalName == null
        || terminalName.isBlank()
        || terminalName.length() > 64
        || terminalName.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid terminal name");
  }

  public Installation withState(String next) {
    return new Installation(
        version, installationId, clusterId, terminalId, branchId, role, terminalName, next);
  }
}
