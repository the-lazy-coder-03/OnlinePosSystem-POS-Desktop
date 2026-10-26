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

  public record Terminal(
      String certificateFingerprint,
      boolean revoked,
      String publicKey,
      String certificate,
      String name,
      long registeredAt,
      String address,
      long lastSeen) {
    public Terminal {
      if (name == null) name = "Terminal";
    }

    public Terminal(String certificateFingerprint, boolean revoked) {
      this(certificateFingerprint, revoked, null, null, "Terminal", 0, null, 0);
    }
  }

  public record Pending(Pairing.JoinRequest terminal, long expiresAt) {}

  public record TerminalView(
      UUID terminalId,
      String name,
      String address,
      long registeredAt,
      long lastSeen,
      boolean online,
      boolean revoked) {}

  public record State(
      Map<String, InvitationRecord> invitations,
      Map<UUID, Terminal> terminals,
      Map<String, Pending> challenges,
      int version) {
    public State(Map<String, InvitationRecord> invitations, Map<UUID, Terminal> terminals) {
      this(invitations, terminals, new HashMap<>(), 1);
    }

    public State {
      if (version == 0) version = 1;
      if (version != 1) throw new IllegalArgumentException("Unsupported terminal registry version");
      if (challenges == null) challenges = new HashMap<>();
    }
  }

  private final Path file, lock;
  private final Clock clock;
  private final Installation installation;
  private final SecretStore secrets;

  public TerminalRegistry(Path root, Clock clock, Installation installation, SecretStore secrets) {
    file = root.resolve("pairing").resolve("registry.json");
    lock = root.resolve("pairing").resolve("registry.lock");
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
      if (state.terminals().containsKey(request.terminalId())
          && !state.terminals().get(request.terminalId()).revoked())
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

  public Pairing.Bootstrap bootstrap() throws Exception {
    requireMaster();
    var ca =
        Certificates.parse(
            new String(secrets.get("ca-certificate"), java.nio.charset.StandardCharsets.US_ASCII));
    return new Pairing.Bootstrap(
        1,
        installation.installationId(),
        installation.clusterId(),
        installation.branchId(),
        installation.role().name(),
        installation.state(),
        Certificates.encode(ca),
        Certificates.fingerprint(ca));
  }

  private void requireMaster() {
    if (installation.role() != za.co.onlinepos.contracts.InstallationRole.MASTER
        || !installation.state().equals("INSTALLED"))
      throw new SecurityException("Active Master required");
  }

  private void validateJoin(Pairing.JoinRequest request) throws Exception {
    requireMaster();
    if (request == null
        || !installation.clusterId().equals(request.clusterId())
        || installation.branchId() != request.branchId()
        || request.terminalId() == null
        || request.terminalName() == null
        || request.terminalName().isBlank()
        || request.terminalName().length() > 64
        || request.terminalName().codePoints().anyMatch(Character::isISOControl)
        || request.publicKey() == null
        || request.publicKey().length() > 2048)
      throw new SecurityException("Invalid terminal identity");
    var key =
        KeyFactory.getInstance("RSA")
            .generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(request.publicKey())));
    if (((java.security.interfaces.RSAPublicKey) key).getModulus().bitLength() < 3072)
      throw new SecurityException("Weak key");
  }

  public synchronized Pairing.Challenge challenge(Pairing.JoinRequest request) throws Exception {
    validateJoin(request);
    Files.createDirectories(file.getParent());
    try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var held = channel.lock()) {
      var state = read();
      var old = state.terminals().get(request.terminalId());
      if (old != null && (old.revoked() || !request.publicKey().equals(old.publicKey())))
        throw new SecurityException("Terminal requires protected repair");
      state.challenges().entrySet().removeIf(e -> e.getValue().expiresAt() <= clock.millis());
      if (state.challenges().size() >= 256) throw new SecurityException("Pairing busy");
      byte[] bytes = new byte[32];
      new SecureRandom().nextBytes(bytes);
      String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      long expires = clock.millis() + 120000;
      state.challenges().put(digest(nonce), new Pending(request, expires));
      JsonFiles.write(file, state);
      return new Pairing.Challenge(nonce, expires);
    }
  }

  public synchronized Pairing.Reply enrol(Pairing.Enrolment request, String address)
      throws Exception {
    validateJoin(request.terminal());
    if (request.nonce() == null
        || !request.nonce().matches("[A-Za-z0-9_-]{43}")
        || request.signature() == null
        || request.signature().length() > 2048) throw new SecurityException("Invalid proof");
    try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var held = channel.lock()) {
      var state = read();
      var terminal = request.terminal();
      var pending = state.challenges().get(digest(request.nonce()));
      if (pending == null
          || pending.expiresAt() <= clock.millis()
          || !pending.terminal().equals(terminal))
        throw new SecurityException("Challenge expired or used");
      var key =
          KeyFactory.getInstance("RSA")
              .generatePublic(
                  new X509EncodedKeySpec(Base64.getDecoder().decode(terminal.publicKey())));
      var proof = Signature.getInstance("SHA256withRSA");
      proof.initVerify(key);
      proof.update(
          Pairing.proof(terminal, request.nonce())
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      if (!proof.verify(Base64.getDecoder().decode(request.signature())))
        throw new SecurityException("Key proof failed");
      var old = state.terminals().get(terminal.terminalId());
      if (old != null && (old.revoked() || !terminal.publicKey().equals(old.publicKey())))
        throw new SecurityException("Terminal conflict or revoked");
      String encoded;
      if (old != null) encoded = old.certificate();
      else {
        var ca =
            Certificates.parse(
                new String(
                    secrets.get("ca-certificate"), java.nio.charset.StandardCharsets.US_ASCII));
        var cert =
            Certificates.issue(
                terminal.terminalId().toString(),
                key,
                ca,
                Certificates.privateKey(secrets.get("ca-key")),
                false,
                true,
                java.util.List.of());
        encoded = Certificates.encode(cert);
        state
            .terminals()
            .put(
                terminal.terminalId(),
                new Terminal(
                    Certificates.fingerprint(cert),
                    false,
                    terminal.publicKey(),
                    encoded,
                    terminal.terminalName(),
                    clock.millis(),
                    address,
                    0));
      }
      state.challenges().remove(digest(request.nonce()));
      JsonFiles.write(file, state);
      return new Pairing.Reply(
          installation.clusterId(), installation.branchId(), terminal.terminalId(), encoded);
    }
  }

  public synchronized void contact(String fingerprint, String address) throws Exception {
    var snapshot = read();
    if (snapshot.terminals().values().stream()
        .noneMatch(
            t ->
                !t.revoked()
                    && t.certificateFingerprint().equals(fingerprint)
                    && (clock.millis() - t.lastSeen() >= 10000
                        || !java.util.Objects.equals(address, t.address())))) return;
    try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var held = channel.lock()) {
      var state = read();
      state
          .terminals()
          .replaceAll(
              (id, t) ->
                  !t.revoked() && t.certificateFingerprint().equals(fingerprint)
                      ? new Terminal(
                          t.certificateFingerprint(),
                          false,
                          t.publicKey(),
                          t.certificate(),
                          t.name(),
                          t.registeredAt(),
                          address,
                          clock.millis())
                      : t);
      JsonFiles.write(file, state);
    }
  }

  public synchronized java.util.List<TerminalView> terminals() throws Exception {
    return read().terminals().entrySet().stream()
        .map(
            e -> {
              var t = e.getValue();
              return new TerminalView(
                  e.getKey(),
                  t.name(),
                  t.address(),
                  t.registeredAt(),
                  t.lastSeen(),
                  !t.revoked() && t.lastSeen() > 0 && clock.millis() - t.lastSeen() < 30000,
                  t.revoked());
            })
        .sorted(java.util.Comparator.comparing(t -> t.terminalId().toString()))
        .toList();
  }

  public synchronized void rename(UUID id, String name) throws Exception {
    if (name == null
        || name.isBlank()
        || name.length() > 64
        || name.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid terminal name");
    try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var held = channel.lock()) {
      var state = read();
      var t = state.terminals().get(id);
      if (t == null) throw new IllegalArgumentException("Unknown terminal");
      state
          .terminals()
          .put(
              id,
              new Terminal(
                  t.certificateFingerprint(),
                  t.revoked(),
                  t.publicKey(),
                  t.certificate(),
                  name,
                  t.registeredAt(),
                  t.address(),
                  t.lastSeen()));
      JsonFiles.write(file, state);
    }
  }

  public synchronized boolean authorized(X509Certificate certificate) throws Exception {
    certificate.checkValidity();
    String fingerprint = Certificates.fingerprint(certificate);
    return read().terminals().values().stream()
        .anyMatch(t -> !t.revoked() && t.certificateFingerprint().equals(fingerprint));
  }

  public synchronized void revoke(UUID terminal) throws Exception {
    if (installation.terminalId().equals(terminal))
      throw new IllegalArgumentException("Cannot revoke the Master terminal");
    try (FileChannel channel =
            FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var held = channel.lock()) {
      State state = read();
      Terminal old = state.terminals().get(terminal);
      if (old == null) throw new IllegalArgumentException("Unknown terminal");
      state
          .terminals()
          .put(
              terminal,
              new Terminal(
                  old.certificateFingerprint(),
                  true,
                  old.publicKey(),
                  old.certificate(),
                  old.name(),
                  old.registeredAt(),
                  old.address(),
                  old.lastSeen()));
      JsonFiles.write(file, state);
    }
  }
}
