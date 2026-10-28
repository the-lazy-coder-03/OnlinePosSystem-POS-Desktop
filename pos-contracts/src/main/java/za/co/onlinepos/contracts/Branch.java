package za.co.onlinepos.contracts;

/**
 * Persisted branch identity. Legacy constants are aliases, not an allowlist or provisioning seed.
 */
public record Branch(int id, String displayName) {
  public static final Branch KENRIDGE = new Branch(1, "Kenridge");
  public static final Branch UITZICHT = new Branch(2, "Uitzicht");

  public Branch {
    if (id <= 0) throw new IllegalArgumentException("Positive store ID required");
    if (displayName == null || displayName.isBlank() || displayName.length() > 200)
      throw new IllegalArgumentException("Store name required");
  }

  /** Identity validation only; names must be loaded from the store repository for presentation. */
  public static Branch fromId(int id) {
    if (id == 1) return KENRIDGE;
    if (id == 2) return UITZICHT;
    return new Branch(id, "Store " + id);
  }

  /** Compatibility for legacy callers; never use this as the available-store directory. */
  @Deprecated
  public static Branch[] values() {
    return new Branch[] {KENRIDGE, UITZICHT};
  }

  @Override
  public String toString() {
    return displayName;
  }
}
