package za.co.onlinepos.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.onlinepos.contracts.InstallationRole;

class GuidedSetupTest {
  @Test
  void namedStoresAndInterruptedSetupKeepIdentityAndProfile() throws Exception {
    var profile = new StoreProfile("Oak Street Pizza", "Pizza Company", "STORE-OAK");
    var request =
        new SetupRequest(123, InstallationRole.MASTER, "Counter", "", "", null, profile, null);
    var first = SetupIdentity.resolve(Optional.empty(), request);
    var otherStore = SetupIdentity.resolve(Optional.empty(), request);
    assertEquals(123, first.branchId());
    assertNotEquals(first.clusterId(), otherStore.clusterId());
    assertEquals(profile, first.storeProfile());
    var persisted =
        JsonFiles.JSON.readValue(JsonFiles.JSON.writeValueAsBytes(first), Installation.class);
    assertEquals(first, SetupIdentity.resolve(Optional.of(persisted), request));
    assertEquals(profile, first.withState("INSTALLED").storeProfile());
    var rename =
        new SetupRequest(
            123,
            InstallationRole.MASTER,
            "Counter",
            "",
            "",
            null,
            new StoreProfile("Different", "Different", "NEW"),
            null);
    assertThrows(SecurityException.class, () -> SetupIdentity.resolve(Optional.of(first), rename));
    assertThrows(
        SecurityException.class,
        () ->
            SetupIdentity.resolve(
                Optional.of(first), new SetupRequest(456, InstallationRole.MASTER, "Counter", "")));
    assertThrows(
        SecurityException.class,
        () ->
            SetupIdentity.resolve(
                Optional.of(first),
                new SetupRequest(123, InstallationRole.SLAVE, "Counter", "", "192.168.1.20")));
  }

  @Test
  void legacyFilesAndRequestsStillLoadWithoutInventingNames() throws Exception {
    var legacy =
        new Installation(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            987,
            InstallationRole.MASTER,
            "Existing",
            "CONFIGURING");
    var json = JsonFiles.JSON.valueToTree(legacy);
    ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("storeProfile");
    var restored = JsonFiles.JSON.treeToValue(json, Installation.class);
    assertNull(restored.storeProfile());
    var requestJson =
        JsonFiles.JSON.valueToTree(new SetupRequest(987, InstallationRole.MASTER, "Existing", ""));
    ((com.fasterxml.jackson.databind.node.ObjectNode) requestJson)
        .remove(java.util.List.of("storeProfile", "selectedMaster"));
    var request = JsonFiles.JSON.treeToValue(requestJson, SetupRequest.class);
    assertEquals(restored, SetupIdentity.resolve(Optional.of(restored), request));
  }

  @Test
  void tillSelectionCannotChangeAnInstalledClusterOrEscapeThePrivateLan() {
    var saved =
        new Installation(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            345,
            InstallationRole.SLAVE,
            "Till",
            "INSTALLED");
    var other =
        new MasterSelection(
            "https://192.168.1.20:8443", 345, UUID.randomUUID(), "fingerprint", null);
    var request =
        new SetupRequest(
            345, InstallationRole.SLAVE, "Till", "", "192.168.1.20", null, null, other);
    assertThrows(SecurityException.class, () -> SetupIdentity.resolve(Optional.of(saved), request));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MasterSelection(
                "https://8.8.8.8:8443", 345, UUID.randomUUID(), "fingerprint", null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SetupRequest(
                1, InstallationRole.SLAVE, "Till", "", "192.168.1.20", null, null, other));
    assertThrows(IllegalArgumentException.class, () -> new StoreProfile(" ", "Business", "CODE"));
  }
}
