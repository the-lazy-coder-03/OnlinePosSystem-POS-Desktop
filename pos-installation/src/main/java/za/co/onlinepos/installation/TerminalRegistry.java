package za.co.onlinepos.installation;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TerminalRegistry {
  public record InvitationRecord(String digest, long expiresAt) {}

  public record Terminal(String certificateFingerprint, boolean revoked) {}

  public record State(Map<String, InvitationRecord> invitations, Map<UUID, Terminal> terminals) {}

  private final Path file, lock;
  private final Clock clock;
  private final Installation installation;
  private final SecretStore secrets;

  public TerminalRegistry(Path root, Clock clock, Installation installation, SecretStore secrets) {
    file = root.resolve("registry.json");
    lock = root.resolve("registry.lock");
    this.clock = clock;
    this.installation = installation;
    this.secrets = secrets;
  }

  private State read() throws Exception {
    return Files.exists(file)
        ? JsonFiles.JSON.readValue(file.toFile(), State.class)
        : new State(new HashMap<>(), new HashMap<>());
  }

  private String digest(String token) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
  }

  public synchronized Pairing.Invitation invite(String endpoint) throws Exception {
    Files.createDirectories(file.getParent());
    try (FileChannel channel =
            FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var held = channel.lock()) {
      State state = read();
      byte[] bytes = new byte[32];
      new SecureRandom().nextBytes(bytes);
      String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      long expires = clock.millis() + 600000;
      state.invitations().entrySet().removeIf(e -> e.getValue().expiresAt() < clock.millis());
      state.invitations().put(digest(token), new InvitationRecord(digest(token), expires));
      X509Certificate ca =
          Certificates.parse(
              new String(
                  secrets.get("ca-certificate"), java.nio.charset.StandardCharsets.US_ASCII));
      Pairing.Invitation invitation =
          new Pairing.Invitation(
              1,
              installation.clusterId(),
              installation.branchId(),
              endpoint,
              Certificates.encode(ca),
              Certificates.fingerprint(ca),
              token,
              expires);
      JsonFiles.write(file, state);
      return invitation;
    }
  }

  public synchronized Pairing.Reply redeem(Pairing.Request request) throws Exception {
    Files.createDirectories(file.getParent());
    try (FileChannel channel =
            FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var held = channel.lock()) {
      if (!installation.clusterId().equals(request.clusterId())
          || installation.branchId() != request.branchId()
          || request.terminalId() == null)
        throw new SecurityException("Invitation branch or cluster mismatch");
      State state = read();
      String tokenDigest = digest(request.token());
      InvitationRecord invitation = state.invitations().get(tokenDigest);
      if (invitation == null || invitation.expiresAt() <= clock.millis())
        throw new SecurityException("Invitation expired or already used");
      if (state.terminals().containsKey(request.terminalId()))
        throw new SecurityException("Terminal already registered");
      var publicKey =
          KeyFactory.getInstance("RSA")
              .generatePublic(
                  new X509EncodedKeySpec(Base64.getDecoder().decode(request.publicKey())));
      if (((java.security.interfaces.RSAPublicKey) publicKey).getModulus().bitLength() < 3072)
        throw new SecurityException("Terminal key too weak");
      Signature proof = Signature.getInstance("SHA256withRSA");
      proof.initVerify(publicKey);
      proof.update(
          (request.token() + ":" + request.terminalId())
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      if (!proof.verify(Base64.getDecoder().decode(request.signature())))
        throw new SecurityException("Terminal key proof failed");
      X509Certificate ca =
          Certificates.parse(
              new String(
                  secrets.get("ca-certificate"), java.nio.charset.StandardCharsets.US_ASCII));
      X509Certificate certificate =
          Certificates.issue(
              request.terminalId().toString(),
              publicKey,
              ca,
              Certificates.privateKey(secrets.get("ca-key")),
              false,
              true,
              java.util.List.of());
      state
          .terminals()
          .put(request.terminalId(), new Terminal(Certificates.fingerprint(certificate), false));
      state.invitations().remove(tokenDigest);
      JsonFiles.write(file, state);
      return new Pairing.Reply(
          installation.clusterId(),
          installation.branchId(),
          request.terminalId(),
          Certificates.encode(certificate));
    }
  }

  public synchronized boolean authorized(X509Certificate certificate) throws Exception {
    certificate.checkValidity();
    String fingerprint = Certificates.fingerprint(certificate);
    return read().terminals().values().stream()
        .anyMatch(t -> !t.revoked() && t.certificateFingerprint().equals(fingerprint));
  }

  public synchronized void revoke(UUID terminal) throws Exception {
    try (FileChannel channel =
            FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var held = channel.lock()) {
      State state = read();
      Terminal old = state.terminals().get(terminal);
      if (old == null) throw new IllegalArgumentException("Unknown terminal");
      state.terminals().put(terminal, new Terminal(old.certificateFingerprint(), true));
      JsonFiles.write(file, state);
    }
  }
}
