package za.co.onlinepos.desktop.customers;

import java.util.UUID;
import za.co.onlinepos.contracts.CustomerProfile;

public record Customer(
    UUID id,
    Long databaseId,
    CustomerProfile profile,
    String role,
    int accessLevel,
    boolean environmentAdmin) {
  public String name() {
    return (profile.firstName() + " " + profile.lastName()).strip();
  }

  public String phone() {
    return profile.phone1();
  }

  public String email() {
    return profile.email();
  }

  public String address() {
    if (!profile.formattedAddress().isBlank()) return profile.formattedAddress();
    return java.util.stream.Stream.of(
            profile.houseNumber(),
            profile.street(),
            profile.complexName(),
            profile.area(),
            profile.city(),
            profile.postalCode())
        .filter(s -> !s.isBlank())
        .collect(java.util.stream.Collectors.joining(", "));
  }
}
