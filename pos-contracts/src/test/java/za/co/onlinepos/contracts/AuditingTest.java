package za.co.onlinepos.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditingTest {
  @Test
  void onlyImplementedNonessentialActivitiesAreOptionalAndSecretsAreNotPrintable() {
    assertEquals(
        10,
        java.util.Arrays.stream(Auditing.EventType.values())
            .filter(Auditing.EventType::optional)
            .count());
    assertFalse(Auditing.EventType.AUDIT_SETTING_CHANGED.optional());
    assertFalse(Auditing.EventType.OWNER_PIN_FAILED.optional());
    assertFalse(
        new Auditing.SettingsEdit(UUID.randomUUID(), 0, Map.of(), "012345")
            .toString()
            .contains("012345"));
  }

  @Test
  void offlineIdentityRejectsContentConflictsAndInvalidDigests() {
    UUID event = UUID.randomUUID(), command = UUID.randomUUID(), staff = UUID.randomUUID();
    Instant at = Instant.now();
    var original =
        new Auditing.OfflineEvent(
            event,
            command,
            null,
            "POS02",
            staff,
            at,
            Auditing.EventType.DRAFT_CREATED,
            "a".repeat(64));
    original.verifyReplay(original);
    var changed =
        new Auditing.OfflineEvent(
            event,
            command,
            null,
            "POS02",
            staff,
            at,
            Auditing.EventType.DRAFT_CREATED,
            "b".repeat(64));
    assertThrows(IllegalArgumentException.class, () -> original.verifyReplay(changed));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Auditing.OfflineEvent(
                event,
                command,
                null,
                "POS02",
                staff,
                at,
                Auditing.EventType.DRAFT_CREATED,
                "invalid"));
  }
}
