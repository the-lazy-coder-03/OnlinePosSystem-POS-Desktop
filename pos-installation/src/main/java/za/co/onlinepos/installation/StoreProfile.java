package za.co.onlinepos.installation;

/** Initial display details, carried by protected installation configuration. */
public record StoreProfile(String storeName, String businessName, String code) {
  public StoreProfile {
    storeName = label(storeName, 200);
    businessName = label(businessName, 200);
    code = label(code, 40);
  }

  private static String label(String value, int limit) {
    if (value == null
        || value.isBlank()
        || value.strip().length() > limit
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Enter valid store details");
    return value.strip();
  }
}
