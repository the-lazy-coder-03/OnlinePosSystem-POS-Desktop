package za.co.onlinepos.installation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import za.co.onlinepos.contracts.InstallationRole;

class PairingSecurityTest {
  @TempDir Path root;

  private TerminalRegistry registry(Clock clock, Installation identity, SecretStore secrets)
      throws Exception {
    var key = Certificates.keyPair();
    var ca =
        Certificates.issue(
            "Test CA", key.getPublic(), null, key.getPrivate(), true, false, List.of());
    secrets.put("ca-key", key.getPrivate().getEncoded());
    secrets.put("ca-certificate", Certificates.encode(ca).getBytes(StandardCharsets.US_ASCII));
    return new TerminalRegistry(root, clock, identity, secrets);
  }

  @Test
  void invitationIsSingleUseAndRevocationSurvivesRegistryReload() throws Exception {
    var identity =
        new Installation(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            InstallationRole.MASTER,
            "Master",
            "INSTALLED");
    var secrets = new InstallationSecurityTest.MemorySecrets();
    var registry = registry(Clock.systemUTC(), identity, secrets);
    var invitation = registry.invite("https://localhost:8443");
    var key = Certificates.keyPair();
    UUID terminal = UUID.randomUUID();
    var proof = Signature.getInstance("SHA256withRSA");
    proof.initSign(key.getPrivate());
    proof.update((invitation.token() + ":" + terminal).getBytes(StandardCharsets.UTF_8));
    var request =
        new Pairing.Request(
            identity.clusterId(),
            1,
            terminal,
            invitation.token(),
            Base64.getEncoder().encodeToString(key.getPublic().getEncoded()),
            Base64.getEncoder().encodeToString(proof.sign()));
    var reply = registry.redeem(request);
    var certificate = Certificates.parse(reply.clientCertificate());
    assertTrue(registry.authorized(certificate));
    assertThrows(SecurityException.class, () -> registry.redeem(request));
    registry.revoke(terminal);
    assertFalse(
        new TerminalRegistry(root, Clock.systemUTC(), identity, secrets).authorized(certificate));
    assertFalse(invitation.toString().contains(invitation.token()));
  }

  @Test
  void expiredWrongBranchAndUntrustedInvitationsAreRejected() throws Exception {
    var identity =
        new Installation(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            2,
            InstallationRole.MASTER,
            "Master",
            "INSTALLED");
    var secrets = new InstallationSecurityTest.MemorySecrets();
    var registry = registry(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), identity, secrets);
    var invitation = registry.invite("https://localhost:8443");
    var later =
        new TerminalRegistry(
            root, Clock.fixed(Instant.ofEpochMilli(600001), ZoneOffset.UTC), identity, secrets);
    assertThrows(
        SecurityException.class,
        () ->
            later.redeem(
                new Pairing.Request(
                    identity.clusterId(), 2, UUID.randomUUID(), invitation.token(), "", "")));
    assertThrows(
        SecurityException.class,
        () ->
            registry.redeem(
                new Pairing.Request(
                    identity.clusterId(), 1, UUID.randomUUID(), invitation.token(), "", "")));
    assertThrows(IllegalArgumentException.class, () -> registry.invite("http://localhost:8443"));
    var bad =
        new Pairing.Invitation(
            1,
            identity.clusterId(),
            2,
            "https://localhost:8443",
            invitation.caCertificate(),
            "wrong",
            invitation.token(),
            Long.MAX_VALUE);
    assertThrows(
        SecurityException.class,
        () -> new TrustedStoreClient().pair(bad, UUID.randomUUID(), secrets));
  }
}
