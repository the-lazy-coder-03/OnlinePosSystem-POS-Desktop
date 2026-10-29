package za.co.onlinepos.installation;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolve identity before provisioning writes; ordinary setup never reassigns an existing store.
 */
public final class SetupIdentity {
  private SetupIdentity() {}

  public static Installation resolve(Optional<Installation> previous, SetupRequest request) {
    if (previous.isEmpty())
      return new Installation(
          1,
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          request.branchId(),
          request.role(),
          request.terminalName(),
          "CONFIGURING",
          request.storeProfile());
    var saved = previous.get();
    if (saved.branchId() != request.branchId() || saved.role() != request.role())
      throw new SecurityException(
          "Changing this computer’s store or role requires protected administration");
    if (saved.state().equals("INSTALLED")
        && request.selectedMaster() != null
        && !saved.clusterId().equals(request.selectedMaster().clusterId()))
      throw new SecurityException("Cannot reassign an installed terminal to another store");
    if (request.storeProfile() != null && !request.storeProfile().equals(saved.storeProfile()))
      throw new SecurityException("Existing store details must be changed in Store Management");
    return saved;
  }
}
