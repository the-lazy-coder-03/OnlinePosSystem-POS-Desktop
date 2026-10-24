package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import za.co.onlinepos.contracts.Branch;
import za.co.onlinepos.contracts.InstallationRole;

class SetupSelectionTest {
  @Test
  void ordinaryFirstRunCanSelectEitherBranchAndMasterOrSlave() {
    for (Branch branch : Branch.values()) {
      for (InstallationRole role :
          new InstallationRole[] {InstallationRole.MASTER, InstallationRole.SLAVE}) {
        SetupSelection selection = new SetupSelection(branch, role, "  Front counter 1  ");
        assertEquals(branch, selection.branch());
        assertEquals(role, selection.role());
        assertEquals("Front counter 1", selection.terminalName());
      }
    }
  }

  @Test
  void backupRoleCannotBeSelectedAsAnOrdinaryFirstRunInstallation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SetupSelection(Branch.KENRIDGE, InstallationRole.BACKUP_MASTER, "Backup"));
  }

  @Test
  void blankOversizedAndControlCharacterTerminalNamesAreRejected() {
    for (String name : new String[] {" ", "A".repeat(65), "Front\nCounter"}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new SetupSelection(Branch.KENRIDGE, InstallationRole.SLAVE, name));
    }
  }
}
