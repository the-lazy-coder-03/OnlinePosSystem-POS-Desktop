package za.co.onlinepos.desktop.customers;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import za.co.onlinepos.contracts.CustomerProfile;

/** Session-only customer profile store. */
public final class AccountService {
  private final Map<UUID, Customer> customers = new LinkedHashMap<>();

  public List<Customer> search(String query) {
    String term = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    return customers.values().stream()
        .filter(
            c ->
                c.name().toLowerCase(Locale.ROOT).contains(term)
                    || c.phone().toLowerCase(Locale.ROOT).contains(term)
                    || c.profile().phone2().toLowerCase(Locale.ROOT).contains(term)
                    || c.email().toLowerCase(Locale.ROOT).contains(term))
        .sorted(Comparator.comparing(Customer::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  public Customer save(UUID id, String name, String phone, String email, String address) {
    var values = new HashMap<String, String>();
    String[] names = clean(name).split(" ", 2);
    values.put("first_name", names[0]);
    values.put("last_name", names.length > 1 ? names[1] : "");
    values.put("phone1", clean(phone));
    values.put("email", clean(email));
    values.put("formatted_address", clean(address));
    return save(
        id,
        CustomerProfile.from(values, id == null ? null : customer(id).profile().lastOrderedAt()));
  }

  public Customer save(UUID id, CustomerProfile profile) {
    profile = CustomerProfile.from(profile.values(), profile.lastOrderedAt());
    if (profile.firstName().isBlank() && profile.lastName().isBlank())
      throw new CustomerProfile.FieldException("first_name", "Enter a first or last name.");
    if (profile.phone1().isBlank())
      throw new CustomerProfile.FieldException("phone1", "Primary phone is required.");
    for (Customer existing : customers.values()) {
      if (existing.id().equals(id)) continue;
      if (existing.phone().equals(profile.phone1()))
        throw new CustomerProfile.FieldException("phone1", "Primary phone is already in use.");
      if (!profile.email().isBlank() && existing.email().equalsIgnoreCase(profile.email()))
        throw new CustomerProfile.FieldException("email", "Email is already in use.");
    }
    Customer previous = id == null ? null : customer(id);
    UUID key = id == null ? UUID.randomUUID() : id;
    Customer customer =
        new Customer(
            key,
            previous == null ? null : previous.databaseId(),
            profile,
            previous == null ? "USER" : previous.role(),
            previous == null ? 0 : previous.accessLevel(),
            previous != null && previous.environmentAdmin());
    customers.put(key, customer);
    return customer;
  }

  private Customer customer(UUID id) {
    Customer c = customers.get(id);
    if (c == null) throw new IllegalArgumentException("Customer does not exist.");
    return c;
  }

  private static String clean(String value) {
    return value == null ? "" : value.strip();
  }

  public static AccountService sample() {
    var service = new AccountService();
    service.save(
        null, "Alex Morgan", "082 555 0101", "alex@example.test", "14 Sample Lane, Kenridge");
    service.save(
        null, "Jordan Daniels", "083 555 0102", "jordan@example.test", "8 Example Road, Uitzicht");
    service.save(null, "Sam Williams", "084 555 0103", "", "");
    return service;
  }
}
