package za.co.onlinepos.contracts;

/** Cloud seed identities; bootstrap must still verify them against its snapshot. */
public enum Branch {
  KENRIDGE(1, "Kenridge"),
  UITZICHT(2, "Uitzicht");

  private final int id;
  private final String displayName;

  Branch(int id, String displayName) {
    this.id = id;
    this.displayName = displayName;
  }

  public int id() {
    return id;
  }

  public String displayName() {
    return displayName;
  }

  public static Branch fromId(int id) {
    for (Branch branch : values()) {
      if (branch.id == id) {
        return branch;
      }
    }
    throw new IllegalArgumentException("Unknown branch ID: " + id);
  }
}
