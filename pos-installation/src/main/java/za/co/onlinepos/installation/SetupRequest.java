package za.co.onlinepos.installation;

import java.util.Objects;
import za.co.onlinepos.contracts.Branch;
import za.co.onlinepos.contracts.InstallationRole;

public record SetupRequest(
    int branchId,
    InstallationRole role,
    String terminalName,
    String invitation,
    String masterAddress,
    LanPolicy network,
    StoreProfile storeProfile,
    MasterSelection selectedMaster) {
  public SetupRequest(
      int branchId,
      InstallationRole role,
      String terminalName,
      String invitation,
      String masterAddress,
      LanPolicy network) {
    this(branchId, role, terminalName, invitation, masterAddress, network, null, null);
  }

  public SetupRequest(
      int branchId,
      InstallationRole role,
      String terminalName,
      String invitation,
      String masterAddress) {
    this(branchId, role, terminalName, invitation, masterAddress, null);
  }

  public SetupRequest(int branchId, InstallationRole role, String terminalName, String invitation) {
    this(branchId, role, terminalName, invitation, "");
  }

  @Override
  public String toString() {
    return "SetupRequest[branch=" + branchId + ", role=" + role + ", invitation=REDACTED]";
  }

  public SetupRequest {
    invitation = invitation == null ? "" : invitation;
    masterAddress = masterAddress == null ? "" : masterAddress.strip();
    Branch.fromId(branchId);
    Objects.requireNonNull(role);
    if (selectedMaster != null
        && (role != InstallationRole.SLAVE
            || selectedMaster.branchId() != branchId
            || !java.net.URI.create(selectedMaster.origin()).getHost().equals(masterAddress)))
      throw new IllegalArgumentException("Main computer does not match the selected store");
    if (storeProfile != null && role != InstallationRole.MASTER)
      throw new IllegalArgumentException("Additional tills inherit store details");
    if (terminalName == null
        || terminalName.isBlank()
        || terminalName.length() > 64
        || terminalName.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid terminal name");
  }
}
