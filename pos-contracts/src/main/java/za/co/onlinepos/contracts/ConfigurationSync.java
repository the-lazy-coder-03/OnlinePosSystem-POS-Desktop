package za.co.onlinepos.contracts;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned optional configuration protocol. Credentials and financial events are not
 * configuration.
 */
public final class ConfigurationSync {
  private ConfigurationSync() {}

  public enum Trigger {
    MANUAL,
    CASH_UP_STARTED,
    RECONNECT,
    RETRY
  }

  public enum Operation {
    CREATE,
    UPDATE,
    DELETE
  }

  public enum AccountType {
    LOCAL,
    CLOUD
  }

  public enum Entity {
    BUSINESS,
    STORE,
    CATEGORY,
    PRODUCT,
    VARIANT,
    EXTRA,
    OPTION_GROUP,
    COMBO,
    PRICE,
    AVAILABILITY
  }

  public record Scope(UUID businessId, UUID storeId) {
    public Scope {
      required(businessId);
      if (storeId != null) required(storeId);
    }
  }

  public record Version(long utcMillis, long counter, UUID sourceId, UUID operationId)
      implements Comparable<Version> {
    public Version {
      if (utcMillis < 0 || counter < 0) throw new IllegalArgumentException("Invalid logical clock");
      required(sourceId);
      required(operationId);
    }

    @Override
    public int compareTo(Version other) {
      int result = Long.compare(utcMillis, other.utcMillis);
      if (result == 0) result = Long.compare(counter, other.counter);
      if (result == 0) result = sourceId.toString().compareTo(other.sourceId.toString());
      if (result == 0) result = operationId.toString().compareTo(other.operationId.toString());
      return result;
    }
  }

  /** Persist the returned version in the same transaction as its mutation/outbox record. */
  public static Version tick(
      long observedUtcMillis, Version last, Version received, UUID source, UUID operation) {
    long physical =
        Math.max(
            Math.max(0, observedUtcMillis),
            Math.max(
                last == null ? 0 : last.utcMillis(), received == null ? 0 : received.utcMillis()));
    long logical = 0;
    if (last != null && last.utcMillis() == physical)
      logical = Math.max(logical, Math.addExact(last.counter(), 1));
    if (received != null && received.utcMillis() == physical)
      logical = Math.max(logical, Math.addExact(received.counter(), 1));
    return new Version(physical, logical, source, operation);
  }

  public record Change(
      UUID changeId,
      Scope scope,
      Entity entity,
      UUID entityId,
      Operation operation,
      Map<String, Object> fields,
      Map<String, Version> versions) {
    public Change {
      required(changeId);
      required(entityId);
      if (scope == null
          || entity == null
          || operation == null
          || fields == null
          || versions == null
          || !fields.keySet().equals(versions.keySet())
          || fields.size() > 200)
        throw new IllegalArgumentException("Invalid configuration change");
      if (operation == Operation.DELETE
          && (!Boolean.TRUE.equals(fields.get("deleted")) || !versions.containsKey("deleted")))
        throw new IllegalArgumentException("A tombstone requires its own logical version");
      for (String field : fields.keySet()) {
        if (field == null
            || !field.matches("[a-z][a-zA-Z0-9_]{0,99}")
            || versions.get(field) == null)
          throw new IllegalArgumentException("Invalid field version");
        if (!allowed(entity).contains(field) && !field.equals("deleted"))
          throw new IllegalArgumentException("Unsupported configuration field");
        if (Set.of(
                "password",
                "password_hash",
                "pin",
                "pin_hash",
                "credentials",
                "permissions",
                "role",
                "owner",
                "credentialVersion")
            .contains(field))
          throw new IllegalArgumentException("Security updates are not LWW configuration");
      }
      fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
      versions = Map.copyOf(versions);
    }
  }

  public record CashUpStarted(UUID eventId, Scope scope, UUID cashUpId, Instant occurredAt) {
    public CashUpStarted {
      required(eventId);
      required(cashUpId);
      if (scope == null || scope.storeId() == null || occurredAt == null)
        throw new IllegalArgumentException("Store-scoped trigger required");
    }
  }

  private static Set<String> allowed(Entity entity) {
    return switch (entity) {
      case BUSINESS -> Set.of("name", "address", "phone", "email", "configuration");
      case STORE ->
          Set.of(
              "name",
              "code",
              "address",
              "phone",
              "email",
              "active",
              "archived",
              "openingHours",
              "configuration");
      case CATEGORY -> Set.of("name", "parentId", "sortOrder", "active", "archived");
      case PRODUCT -> Set.of("name", "description", "categoryId", "active", "archived", "kind");
      case VARIANT -> Set.of("productId", "name", "attributes", "active", "archived");
      case EXTRA ->
          Set.of("name", "price", "included", "removable", "swapGroup", "active", "archived");
      case OPTION_GROUP ->
          Set.of(
              "name",
              "minimum",
              "maximum",
              "required",
              "choices",
              "defaults",
              "active",
              "archived");
      case COMBO -> Set.of("name", "pricingMode", "price", "slots", "enabled", "archived");
      case PRICE -> Set.of("productId", "variantId", "amount", "currency", "active", "archived");
      case AVAILABILITY -> Set.of("productId", "variantId", "available", "archived");
    };
  }

  public static void required(UUID value) {
    if (value == null || value.equals(new UUID(0, 0)))
      throw new IllegalArgumentException("Nonzero UUID required");
  }
}
