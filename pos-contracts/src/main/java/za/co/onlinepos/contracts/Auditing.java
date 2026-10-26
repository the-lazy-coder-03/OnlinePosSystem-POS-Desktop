package za.co.onlinepos.contracts;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Wire contracts; owner PINs are never printable or part of command identity. */
public final class Auditing {
  private Auditing() {}

  public enum EventType {
    DRAFT_CREATED("Orders", true),
    DRAFT_CANCELLED("Orders", true),
    PRICE_CHANGED("Catalogue", true),
    CATALOGUE_CHANGED("Catalogue", true),
    STAFF_LOGIN("Staff", true),
    STAFF_LOGOUT("Staff", true),
    CATALOGUE_BOOTSTRAPPED("System", false),
    STAFF_BOOTSTRAPPED("Security", false),
    STAFF_CHANGED("Security", false),
    ADMIN_STAFF_CHANGED("Security", false),
    ADMIN_ROLE_CHANGED("Security", false),
    ADMIN_PIN_CHANGED("Security", false),
    RBAC_SET_ROLE("Security", false),
    RBAC_ASSIGN_ROLES("Security", false),
    RBAC_SET_PIN("Security", false),
    RBAC_SET_OWNER("Security", false),
    AUDIT_SETTING_CHANGED("Security", false),
    OWNER_PIN_SUCCEEDED("Security", false),
    OWNER_PIN_FAILED("Security", false),
    AUDIT_ACCESS_REFUSED("Security", false),
    AUDIT_INTEGRITY_FAILED("Security", false),
    DEVELOPER_RECOVERY_ENROLLED("Security", false),
    DEVELOPER_RECOVERY_ROTATED("Security", false),
    DEVELOPER_RECOVERY_DISABLED("Security", false),
    DEVELOPER_RECOVERY_FAILED("Security", false),
    OWNER_PIN_RECOVERED("Security", false);
    private final String category;
    private final boolean optional;

    EventType(String category, boolean optional) {
      this.category = category;
      this.optional = optional;
    }

    public String category() {
      return category;
    }

    public boolean optional() {
      return optional;
    }
  }

  public record SettingsEdit(
      UUID commandId, long expectedRevision, Map<String, Boolean> settings, String ownerPin) {
    @Override
    public String toString() {
      return "SettingsEdit[REDACTED]";
    }
  }

  /** Future recovery boundary only; no ingest endpoint or local queue is enabled. */
  public record OfflineEvent(
      UUID eventId,
      UUID commandId,
      UUID transactionId,
      String terminal,
      UUID staffId,
      Instant occurredAt,
      EventType eventType,
      String payloadDigest) {
    public OfflineEvent {
      if (eventId == null
          || eventId.equals(new UUID(0, 0))
          || commandId == null
          || staffId == null
          || occurredAt == null
          || eventType == null
          || terminal == null
          || terminal.isBlank()
          || terminal.length() > 512
          || payloadDigest == null
          || !payloadDigest.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid offline audit identity");
    }

    public void verifyReplay(OfflineEvent candidate) {
      if (!equals(candidate))
        throw new IllegalArgumentException("Offline event identity/content conflict");
    }
  }

  /** Future producers must register a classified, allowlisted event type before recording. */
  public enum PlannedIntegration {
    PAYMENTS,
    CASH_DRAWER,
    FAILOVER,
    OFFLINE_RECOVERY
  }
}
