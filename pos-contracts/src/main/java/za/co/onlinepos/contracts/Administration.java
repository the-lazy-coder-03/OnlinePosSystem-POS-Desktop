package za.co.onlinepos.contracts;

import java.util.Set;
import java.util.UUID;

public final class Administration {
  private Administration() {}

  public record StaffEdit(
      UUID commandId,
      UUID staffId,
      String username,
      boolean enabled,
      Set<String> roles,
      long expectedRevision) {}

  public record RoleEdit(
      UUID commandId, String name, Set<String> permissions, long expectedRevision) {}

  public record PinEdit(UUID commandId, long expectedRevision, String pin) {
    @Override
    public String toString() {
      return "PinEdit[REDACTED]";
    }
  }
}
