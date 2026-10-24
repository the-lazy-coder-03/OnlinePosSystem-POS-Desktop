package za.co.onlinepos.desktop;

import java.util.Objects;
import za.co.onlinepos.contracts.Branch;
import za.co.onlinepos.contracts.InstallationRole;

/** In-memory preview, not an installation record or role-change authorization. */
public record SetupSelection(Branch branch, InstallationRole role, String terminalName) {
  public SetupSelection {
    Objects.requireNonNull(branch, "Select a branch");
    Objects.requireNonNull(role, "Select a role");
    if (role == InstallationRole.BACKUP_MASTER) {
      throw new IllegalArgumentException("Backup Master requires managed provisioning");
    }
    terminalName = Objects.requireNonNull(terminalName, "Enter a terminal name").strip();
    if (terminalName.isEmpty() || terminalName.length() > 64) {
      throw new IllegalArgumentException("Terminal name must contain 1–64 characters");
    }
    if (terminalName.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Terminal name must not contain control characters");
    }
  }
}
