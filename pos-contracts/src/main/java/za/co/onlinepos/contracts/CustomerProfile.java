package za.co.onlinepos.contracts;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Customer contact/address fields from public.customers and rls-v5-address.sql. */
public record CustomerProfile(
    String firstName,
    String lastName,
    String phone1,
    String phone2,
    String email,
    String houseNumber,
    String street,
    String area,
    String complexName,
    String preferredStore,
    String city,
    String postalCode,
    String googlePlaceId,
    String formattedAddress,
    BigDecimal latitude,
    BigDecimal longitude,
    String province,
    String country,
    LocalDateTime lastOrderedAt) {
  public static final Map<String, String> FIELDS;

  static {
    var fields = new LinkedHashMap<String, String>();
    fields.put("first_name", "First name");
    fields.put("last_name", "Last name");
    fields.put("phone1", "Primary phone");
    fields.put("phone2", "Secondary phone");
    fields.put("email", "Email");
    fields.put("house_number", "House number");
    fields.put("street", "Street");
    fields.put("area", "Area");
    fields.put("complex_name", "Complex name");
    fields.put("preferred_store", "Preferred store");
    fields.put("city", "City");
    fields.put("postal_code", "Postal code");
    fields.put("google_place_id", "Google place ID");
    fields.put("formatted_address", "Formatted address");
    fields.put("latitude", "Latitude");
    fields.put("longitude", "Longitude");
    fields.put("province", "Province");
    fields.put("country", "Country");
    FIELDS = java.util.Collections.unmodifiableMap(fields);
  }

  public Map<String, String> values() {
    String[] values = {
      firstName,
      lastName,
      phone1,
      phone2,
      email,
      houseNumber,
      street,
      area,
      complexName,
      preferredStore,
      city,
      postalCode,
      googlePlaceId,
      formattedAddress,
      latitude == null ? "" : latitude.toPlainString(),
      longitude == null ? "" : longitude.toPlainString(),
      province,
      country
    };
    var result = new LinkedHashMap<String, String>();
    int i = 0;
    for (String key : FIELDS.keySet()) {
      String value = values[i++];
      result.put(key, value == null ? "" : value);
    }
    return result;
  }

  public static CustomerProfile from(Map<String, String> input, LocalDateTime ordered) {
    var v = new LinkedHashMap<String, String>();
    for (String key : FIELDS.keySet()) {
      String value = input.getOrDefault(key, "").strip();
      int max =
          switch (key) {
            case "phone1", "phone2" -> 20;
            case "house_number" -> 50;
            case "province", "country" -> 120;
            case "preferred_store", "formatted_address" -> Integer.MAX_VALUE;
            default -> 255;
          };
      if (value.codePointCount(0, value.length()) > max)
        throw new FieldException(key, "Maximum " + max + " characters.");
      v.put(key, value);
    }
    return new CustomerProfile(
        v.get("first_name"),
        v.get("last_name"),
        v.get("phone1"),
        v.get("phone2"),
        v.get("email").toLowerCase(java.util.Locale.ROOT),
        v.get("house_number"),
        v.get("street"),
        v.get("area"),
        v.get("complex_name"),
        v.get("preferred_store"),
        v.get("city"),
        v.get("postal_code"),
        v.get("google_place_id"),
        v.get("formatted_address"),
        coordinate(v.get("latitude"), "latitude", 90),
        coordinate(v.get("longitude"), "longitude", 180),
        v.get("province"),
        v.get("country"),
        ordered);
  }

  private static BigDecimal coordinate(String text, String field, int max) {
    if (text.isEmpty()) return null;
    try {
      var value = new BigDecimal(text).setScale(6, java.math.RoundingMode.UNNECESSARY);
      if (value.abs().compareTo(BigDecimal.valueOf(max)) > 0) throw new ArithmeticException();
      return value;
    } catch (NumberFormatException | ArithmeticException e) {
      throw new FieldException(
          field,
          "Enter a coordinate between -" + max + " and " + max + " with up to six decimals.");
    }
  }

  public static final class FieldException extends IllegalArgumentException {
    private final String field;

    public FieldException(String field, String message) {
      super(message);
      this.field = field;
    }

    public String field() {
      return field;
    }
  }
}
