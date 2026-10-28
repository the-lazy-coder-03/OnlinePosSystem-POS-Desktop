package za.co.onlinepos.desktop.customers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import za.co.onlinepos.contracts.CustomerProfile;

class AccountServiceTest {
  private final AccountService service = new AccountService();
  private final Customer customer = service.save(null, " Alex ", "082123", "Alex@example.test", "");

  @Test
  void searchAndEditPreserveIdentity() {
    assertEquals(1, service.search("aLEX").size());
    assertEquals(1, service.search("082").size());
    assertEquals(1, service.search("EXAMPLE.TEST").size());
    assertTrue(service.search("missing").isEmpty());
    var updated = service.save(customer.id(), "New name", "083", "", "Road");
    assertEquals(customer.id(), updated.id());
    assertEquals(1, service.search("").size());
  }

  @Test
  void requiredContactFields() {
    assertThrows(
        CustomerProfile.FieldException.class, () -> service.save(null, " ", "082", "", ""));
    assertThrows(
        CustomerProfile.FieldException.class, () -> service.save(null, "Name", " ", "", ""));
  }

  @Test
  void sqlProfileFieldsRoundTripAndSecondaryPhoneSearch() {
    var values = new java.util.HashMap<String, String>();
    values.put("first_name", "Taylor");
    values.put("last_name", "Smith");
    values.put("phone1", "0215550100");
    values.put("phone2", "0725550199");
    values.put("email", "TAYLOR@example.test");
    values.put("house_number", "12A");
    values.put("street", "Example Street");
    values.put("area", "Kenridge");
    values.put("complex_name", "Sample Court");
    values.put("city", "Cape Town");
    values.put("province", "Western Cape");
    values.put("country", "South Africa");
    values.put("postal_code", "7550");
    values.put("preferred_store", "Kenridge");
    values.put("google_place_id", "sample-place");
    values.put("latitude", "-33.900001");
    values.put("longitude", "18.600001");
    var ordered = java.time.LocalDateTime.of(2026, 10, 20, 12, 30);
    var profile = CustomerProfile.from(values, ordered);
    var saved = service.save(null, profile);
    assertEquals("Taylor Smith", saved.name());
    assertEquals("taylor@example.test", saved.email());
    assertEquals("12A", saved.profile().houseNumber());
    assertEquals("sample-place", saved.profile().googlePlaceId());
    assertEquals(new BigDecimal("-33.900001"), saved.profile().latitude());
    assertEquals(ordered, saved.profile().lastOrderedAt());
    assertEquals(saved, service.search("0725550199").getFirst());
    assertEquals("USER", saved.role());
    assertEquals(0, saved.accessLevel());
    assertFalse(saved.environmentAdmin());
    assertNull(saved.databaseId());
    assertEquals(profile, CustomerProfile.from(profile.values(), ordered));
  }

  @Test
  void sqlLengthsCoordinatesAndUniqueContacts() {
    var fields = new java.util.HashMap<>(customer.profile().values());
    fields.put("phone1", "1".repeat(21));
    assertThrows(CustomerProfile.FieldException.class, () -> CustomerProfile.from(fields, null));
    fields.put("phone1", "999");
    fields.put("latitude", "90.000001");
    assertThrows(CustomerProfile.FieldException.class, () -> CustomerProfile.from(fields, null));
    fields.put("latitude", "0.1234567");
    assertThrows(CustomerProfile.FieldException.class, () -> CustomerProfile.from(fields, null));
    fields.put("latitude", "0");
    fields.put("longitude", "181");
    assertThrows(CustomerProfile.FieldException.class, () -> CustomerProfile.from(fields, null));
    assertThrows(
        CustomerProfile.FieldException.class, () -> service.save(null, "Other", "082123", "", ""));
    assertThrows(
        CustomerProfile.FieldException.class,
        () -> service.save(null, "Other", "999", "ALEX@EXAMPLE.TEST", ""));
    service.save(null, "Other", "999", "", "");
    service.save(null, "Third", "888", "", "");
  }
}
