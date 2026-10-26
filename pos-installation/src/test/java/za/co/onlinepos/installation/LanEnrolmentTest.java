package za.co.onlinepos.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import za.co.onlinepos.contracts.InstallationRole;

class LanEnrolmentTest {
  @TempDir Path root;

  static final class Secrets implements SecretStore {
    private final Map<String, byte[]> values = new HashMap<>();

    public void put(String name, byte[] value) {
      values.put(name, value.clone());
    }

    public byte[] get(String name) throws java.io.IOException {
      if (!values.containsKey(name)) throw new java.nio.file.NoSuchFileException(name);
      return values.get(name).clone();
    }
  }

  final Installation identity =
      new Installation(
          1,
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          1,
          InstallationRole.MASTER,
          "Master",
          "INSTALLED");

  TerminalRegistry registry(Secrets secrets, Clock clock) throws Exception {
    var caKey = Certificates.keyPair();
    var ca =
        Certificates.issue(
            "Test CA", caKey.getPublic(), null, caKey.getPrivate(), true, false, List.of());
    secrets.put("ca-key", caKey.getPrivate().getEncoded());
    secrets.put("ca-certificate", Certificates.encode(ca).getBytes(StandardCharsets.US_ASCII));
    return new TerminalRegistry(root, clock, identity, secrets);
  }

  Pairing.Enrolment signed(
      Pairing.JoinRequest request, Pairing.Challenge challenge, java.security.KeyPair key)
      throws Exception {
    var proof = Signature.getInstance("SHA256withRSA");
    proof.initSign(key.getPrivate());
    proof.update(Pairing.proof(request, challenge.nonce()).getBytes(StandardCharsets.UTF_8));
    return new Pairing.Enrolment(
        request, challenge.nonce(), Base64.getEncoder().encodeToString(proof.sign()));
  }

  @Test
  void challengeReplayRetryConflictAndRevocation() throws Exception {
    var secrets = new Secrets();
    var registry = registry(secrets, Clock.systemUTC());
    var key = Certificates.keyPair();
    var request =
        new Pairing.JoinRequest(
            identity.clusterId(),
            1,
            UUID.randomUUID(),
            "Front counter",
            Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
    var enrolled = signed(request, registry.challenge(request), key);
    var reply = registry.enrol(enrolled, "192.168.1.12");
    assertTrue(registry.authorized(Certificates.parse(reply.clientCertificate())));
    assertThrows(SecurityException.class, () -> registry.enrol(enrolled, "192.168.1.12"));
    // Lost acknowledgement: a new proof for the same identity returns the exact certificate.
    var retry = registry.enrol(signed(request, registry.challenge(request), key), "192.168.1.12");
    assertEquals(reply, retry);
    var wrong =
        new Pairing.JoinRequest(
            identity.clusterId(), 2, request.terminalId(), "Other", request.publicKey());
    assertThrows(SecurityException.class, () -> registry.challenge(wrong));
    var otherKey = Certificates.keyPair();
    var conflict =
        new Pairing.JoinRequest(
            identity.clusterId(),
            1,
            request.terminalId(),
            request.terminalName(),
            Base64.getEncoder().encodeToString(otherKey.getPublic().getEncoded()));
    assertThrows(SecurityException.class, () -> registry.challenge(conflict));
    registry.contact(
        Certificates.fingerprint(Certificates.parse(reply.clientCertificate())), "192.168.1.13");
    assertTrue(registry.terminals().getFirst().online());
    assertEquals("192.168.1.13", registry.terminals().getFirst().address());
    registry.rename(request.terminalId(), "Counter 2");
    registry.revoke(request.terminalId());
    var reloaded = new TerminalRegistry(root, Clock.systemUTC(), identity, secrets);
    assertEquals("Counter 2", reloaded.terminals().getFirst().name());
    assertFalse(reloaded.authorized(Certificates.parse(reply.clientCertificate())));
    assertFalse(reloaded.terminals().getFirst().online());
    assertThrows(SecurityException.class, () -> reloaded.challenge(request));
  }

  @Test
  void expiredTamperedAndConcurrentChallenges() throws Exception {
    var secrets = new Secrets();
    var registry = registry(secrets, Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC));
    var key = Certificates.keyPair();
    var request =
        new Pairing.JoinRequest(
            identity.clusterId(),
            1,
            UUID.randomUUID(),
            "Till",
            Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
    var challenge = registry.challenge(request);
    var bad =
        new Pairing.JoinRequest(
            identity.clusterId(), 1, request.terminalId(), "Changed", request.publicKey());
    assertThrows(
        SecurityException.class, () -> registry.enrol(signed(bad, challenge, key), "192.168.1.12"));
    var expired =
        new TerminalRegistry(
            root, Clock.fixed(Instant.ofEpochMilli(122000), ZoneOffset.UTC), identity, secrets);
    assertThrows(
        SecurityException.class,
        () -> expired.enrol(signed(request, challenge, key), "192.168.1.12"));
    var proof = signed(request, challenge, key);
    try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var count = new java.util.concurrent.atomic.AtomicInteger();
      var actions =
          java.util.List.<java.util.concurrent.Callable<Void>>of(
              () -> {
                try {
                  registry.enrol(proof, "192.168.1.12");
                  count.incrementAndGet();
                } catch (SecurityException ignored) {
                }
                return null;
              },
              () -> {
                try {
                  registry.enrol(proof, "192.168.1.12");
                  count.incrementAndGet();
                } catch (SecurityException ignored) {
                }
                return null;
              });
      for (var result : workers.invokeAll(actions)) result.get();
      assertEquals(1, count.get());
    }
  }

  @Test
  void legacyRegistryIsReadableAndRetainsRevocations() throws Exception {
    var secrets = new Secrets();
    var registry = registry(secrets, Clock.systemUTC());
    UUID terminal = UUID.randomUUID();
    JsonFiles.write(
        root.resolve("pairing/registry.json"),
        Map.of(
            "invitations",
            Map.of(),
            "terminals",
            Map.of(terminal, Map.of("certificateFingerprint", "legacy", "revoked", true))));
    assertEquals(1, registry.terminals().size());
    assertTrue(registry.terminals().getFirst().revoked());
    assertThrows(
        SecurityException.class,
        () ->
            new TerminalRegistry(
                    root,
                    Clock.systemUTC(),
                    new Installation(
                        1,
                        identity.installationId(),
                        identity.clusterId(),
                        identity.terminalId(),
                        1,
                        InstallationRole.BACKUP_MASTER,
                        "Backup",
                        "STANDBY_UNSEEDED"),
                    secrets)
                .bootstrap());
  }

  @Test
  void exactSubnetInterfaceAndIpv4Only() {
    var policy = new LanPolicy("adapter", "Ethernet", "192.168.10.4", 24);
    assertEquals("192.168.10.0/24", policy.subnet());
    assertTrue(policy.permits("192.168.10.8", "192.168.10.4"));
    assertTrue(policy.permits("127.0.0.1", "127.0.0.1"));
    assertFalse(policy.permits("192.168.11.8", "192.168.10.4"));
    assertFalse(policy.permits("192.168.10.8", "10.1.1.4"));
    assertFalse(policy.permits("::ffff:192.168.10.8", "192.168.10.4"));
    assertFalse(policy.permits("8.8.8.8", "192.168.10.4"));
    assertThrows(
        IllegalArgumentException.class, () -> LanPairingClient.origin("https://192.168.10.4:1234"));
    assertThrows(IllegalArgumentException.class, () -> LanPairingClient.origin("127.0.0.1"));
  }
}
