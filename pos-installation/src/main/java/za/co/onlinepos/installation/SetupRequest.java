package za.co.onlinepos.installation;

import java.util.Objects;
import za.co.onlinepos.contracts.Branch;
import za.co.onlinepos.contracts.InstallationRole;

public record SetupRequest(
    int branchId,
    InstallationRole role,
    String terminalName,
    String invitation,
    String masterAddress) {
  public SetupRequest(int branchId, InstallationRole role, String terminalName, String invitation) {
    this(branchId, role, terminalName, invitation, "");
  }

  public SetupRequest {
    masterAddress = masterAddress == null ? "" : masterAddress.strip();
    Branch.fromId(branchId);
    Objects.requireNonNull(role);
    if (terminalName == null
        || terminalName.isBlank()
        || terminalName.length() > 64
        || terminalName.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid terminal name");
  }
}
