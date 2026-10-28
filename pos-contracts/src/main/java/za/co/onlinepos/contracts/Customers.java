package za.co.onlinepos.contracts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Customers {
  private Customers() {}

  public static final Set<String> PERMISSIONS =
      Set.of(
          "customers.view",
          "customers.manage",
          "customers.archive",
          "customers.ban",
          "customers.resolve");

  public record Edit(
      UUID commandId, UUID customerId, long expectedRevision, Map<String, String> fields) {}

  public record Status(UUID commandId, long expectedRevision, boolean value, String reason) {}

  public record Resolution(
      UUID commandId,
      long expectedRevision,
      String action,
      UUID targetCustomerId,
      Map<String, String> correctedFields,
      Long customerRevision) {
    public Resolution(
        UUID commandId,
        long expectedRevision,
        String action,
        UUID targetCustomerId,
        Map<String, String> correctedFields) {
      this(commandId, expectedRevision, action, targetCustomerId, correctedFields, null);
    }
  }

  public static String phone(String input, boolean required) {
    String value = input == null ? "" : input.strip().replaceAll("[\\s().-]", "");
    if (value.isEmpty() && !required) return "";
    if (value.startsWith("00")) value = "+" + value.substring(2);
    if (value.matches("0[0-9]{9}")) value = "+27" + value.substring(1);
    if (!value.matches("\\+[1-9][0-9]{7,14}"))
      throw new CustomerProfile.FieldException(
          "phone1",
          "Enter a South African number beginning with 0, or an international number beginning with +.");
    return value;
  }

  public static Map<String, String> validate(Map<String, String> fields) {
    if (fields == null
        || !CustomerProfile.FIELDS.keySet().containsAll(fields.keySet())
        || fields.values().stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalArgumentException("Unsupported customer fields");
    var data = new LinkedHashMap<>(fields);
    var profile = CustomerProfile.from(data, null);
    if (profile.firstName().isBlank() && profile.lastName().isBlank())
      throw new CustomerProfile.FieldException("first_name", "Enter a first or last name.");
    data = new LinkedHashMap<>(profile.values());
    data.put("phone1", phone(profile.phone1(), true));
    try {
      data.put("phone2", phone(profile.phone2(), false));
    } catch (CustomerProfile.FieldException e) {
      throw new CustomerProfile.FieldException("phone2", e.getMessage());
    }
    if (!profile.email().isBlank() && !profile.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw new CustomerProfile.FieldException("email", "Enter a valid email address.");
    return data;
  }
}
