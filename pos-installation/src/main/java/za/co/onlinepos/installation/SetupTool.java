package za.co.onlinepos.installation;

import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import za.co.onlinepos.contracts.InstallationRole;

public final class SetupTool {
  public record Envelope(SetupRequest request, String administratorPassword) {
    @Override
    public String toString() {
      return "SetupEnvelope[REDACTED]";
    }
  }

  private SetupTool() {}

  private static void refreshCertificate(
      Installation identity, LanPolicy network, SecretStore secrets) throws Exception {
    var ca =
        Certificates.parse(new String(secrets.get("ca-certificate"), StandardCharsets.US_ASCII));
    var key = Certificates.keyPair();
    String password = new String(secrets.get("pki-password"), StandardCharsets.US_ASCII);
    var certificate =
        Certificates.server(
            identity.clusterId(),
            key.getPublic(),
            ca,
            Certificates.privateKey(secrets.get("ca-key")),
            List.of("localhost", "127.0.0.1", network.address()));
    secrets.put(
        "server-p12",
        Certificates.store(key.getPrivate(), certificate, ca, password.toCharArray()));
  }

  public static void main(String[] args) throws Exception {
    Path root =
        Path.of(System.getenv().getOrDefault("POS_DATA_ROOT", "C:\\ProgramData\\OnlinePOS"));
    Files.createDirectories(root);
    try (var lock =
            java.nio.channels.FileChannel.open(
                root.resolve("management.lock"),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE);
        var held = lock.lock()) {
      var repository = new FileInstallationRepository(root);
      var secrets = new WindowsSecretStore(root);
      var administrator = new Administrator(root, Clock.systemUTC());
      if (args[0].equals("prepare")) {
        byte[] plain = Crypt32Util.cryptUnprotectData(Files.readAllBytes(Path.of(args[1])));
        Envelope envelope;
        try {
          envelope = JsonFiles.JSON.readValue(plain, Envelope.class);
        } finally {
          Arrays.fill(plain, (byte) 0);
        }
        char[] password = envelope.administratorPassword().toCharArray();
        try {
          SetupRequest request = envelope.request();
          var existing = repository.load();
          Installation identity = SetupIdentity.resolve(existing, request);
          boolean slaveRepair =
              request.role() == InstallationRole.SLAVE
                  && (existing.isEmpty()
                      || (existing.get().role() == InstallationRole.SLAVE
                          && existing.get().branchId() == request.branchId()));
          if (!slaveRepair) {
            if (Files.exists(root.resolve("administrator.json"))) administrator.verify(password);
            else administrator.initialize(password);
          }
          var networks = WindowsNetworks.available();
          LanPolicy selected = request.network();
          if (selected == null && Files.exists(root.resolve("lan-policy.json"))) {
            String id = LanPolicy.load(root).adapterId();
            selected =
                networks.stream()
                    .filter(n -> n.adapterId().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new SecurityException("Approved store network unavailable"));
          }
          if (selected == null && networks.size() == 1) selected = networks.getFirst();
          if (selected == null || !networks.contains(selected))
            throw new SecurityException("Select the active private store network");
          JsonFiles.write(root.resolve("lan-policy.json"), selected);
          var oldNetwork = NetworkSettings.load(root);
          JsonFiles.write(
              root.resolve("network.json"),
              new NetworkSettings(
                  oldNetwork.httpsPort(), oldNetwork.postgresPort(), selected.subnet()));
          NetworkSettings.load(root);
          repository.save(identity.withState("CONFIGURING"));
          if (request.role() == InstallationRole.MASTER
              && !Files.exists(root.resolve("secrets/ca-key.dpapi"))) {
            var caKey = Certificates.keyPair();
            var ca =
                Certificates.issue(
                    "OnlinePOS-" + identity.clusterId(),
                    caKey.getPublic(),
                    null,
                    caKey.getPrivate(),
                    true,
                    false,
                    List.of());
            var serverKey = Certificates.keyPair();
            String host = java.net.InetAddress.getLocalHost().getHostName();
            List<String> hosts = new java.util.ArrayList<>(List.of(host, "localhost", "127.0.0.1"));
            hosts.add(java.net.InetAddress.getLocalHost().getCanonicalHostName());
            if (!host.endsWith(".local")) hosts.add(host + ".local");
            hosts.add(selected.address());
            var server =
                Certificates.server(
                    identity.clusterId(), serverKey.getPublic(), ca, caKey.getPrivate(), hosts);
            String pkiPassword = UUID.randomUUID().toString();
            secrets.put(
                "ca-certificate", Certificates.encode(ca).getBytes(StandardCharsets.US_ASCII));
            secrets.put("pki-password", pkiPassword.getBytes(StandardCharsets.US_ASCII));
            secrets.put(
                "server-p12",
                Certificates.store(serverKey.getPrivate(), server, ca, pkiPassword.toCharArray()));
            secrets.put("trust-p12", Certificates.store(null, null, ca, pkiPassword.toCharArray()));
            // Last write commits this bundle; interrupted first setup can safely regenerate it.
            secrets.put("ca-key", caKey.getPrivate().getEncoded());
          }
          if (request.role() == InstallationRole.MASTER
              && !Files.exists(root.resolve("secrets/database-bundle.dpapi")))
            secrets.put(
                "database-bundle",
                JsonFiles.JSON.writeValueAsBytes(
                    Map.of(
                        "bootstrap",
                        UUID.randomUUID().toString(),
                        "owner",
                        UUID.randomUUID().toString(),
                        "runtime",
                        UUID.randomUUID().toString())));
          if (request.role() == InstallationRole.MASTER)
            refreshCertificate(identity, selected, secrets);
          if (request.role() == InstallationRole.SLAVE) {
            var client = new TrustedStoreClient();
            Pairing.Identity reply;
            if (request.invitation() == null || request.invitation().isBlank()) {
              if (!selected.contains(request.masterAddress()))
                throw new SecurityException("Master must be on the approved store subnet");
              reply =
                  request.selectedMaster() != null
                      ? LanPairingClient.connectSelected(
                          request.selectedMaster(),
                          identity.terminalId(),
                          request.terminalName(),
                          secrets)
                      : LanPairingClient.connect(
                          request.masterAddress(),
                          request.branchId(),
                          identity.terminalId(),
                          request.terminalName(),
                          secrets);
            } else {
              // Existing protected invitation tooling remains compatible during upgrades.
              var invitation =
                  JsonFiles.JSON.readValue(request.invitation(), Pairing.Invitation.class);
              final LanPolicy approvedNetwork = selected;
              var source = java.net.URI.create(invitation.endpoint());
              var address =
                  java.util.Arrays.stream(java.net.InetAddress.getAllByName(source.getHost()))
                      .filter(
                          ip ->
                              ip instanceof java.net.Inet4Address
                                  && approvedNetwork.contains(ip.getHostAddress()))
                      .findFirst()
                      .orElseThrow(
                          () ->
                              new SecurityException(
                                  "Invitation Master is outside the approved store LAN"));
              invitation =
                  LanPairingClient.at(
                      invitation,
                      "https://"
                          + address.getHostAddress()
                          + ":"
                          + (source.getPort() < 0 ? 443 : source.getPort()));
              if (invitation.branchId() != request.branchId())
                throw new SecurityException("Wrong branch");
              if (Files.exists(root.resolve("secrets/pairing-trust.dpapi"))) {
                var saved =
                    JsonFiles.JSON.readValue(
                        secrets.get("pairing-trust"), Pairing.Invitation.class);
                if (!saved.clusterId().equals(invitation.clusterId())
                    || !saved.caFingerprint().equals(invitation.caFingerprint()))
                  throw new SecurityException("Cannot replace existing terminal trust");
              }
              reply =
                  Files.exists(root.resolve("secrets/terminal-p12.dpapi"))
                      ? client.identity(invitation, secrets)
                      : client.pair(invitation, identity.terminalId(), secrets);
            }
            identity =
                new Installation(
                    1,
                    identity.installationId(),
                    reply.clusterId(),
                    identity.terminalId(),
                    reply.branchId(),
                    identity.role(),
                    request.terminalName(),
                    "CONFIGURING",
                    request.selectedMaster() == null
                        ? identity.storeProfile()
                        : request.selectedMaster().profile());
            repository.save(identity);
          }
          System.out.println("PREPARED " + request.role().name());
        } finally {
          Arrays.fill(password, '\0');
        }
      } else if (args[0].equals("refresh-network")) {
        var identity = repository.load().orElseThrow();
        if (identity.role() != InstallationRole.MASTER)
          throw new SecurityException("Master required");
        var old = LanPolicy.load(root);
        var current =
            WindowsNetworks.available().stream()
                .filter(n -> n.adapterId().equals(old.adapterId()))
                .findFirst()
                .orElseThrow(() -> new SecurityException("Approved private adapter unavailable"));
        if (!current.subnet().equals(old.subnet()))
          throw new SecurityException("Store subnet change requires protected setup");
        JsonFiles.write(root.resolve("lan-policy.json"), current);
        refreshCertificate(identity, current, secrets);
        System.out.println("NETWORK_REFRESHED");
      } else if (args[0].equals("pair-local")) {
        Installation identity = repository.load().orElseThrow();
        if (identity.role() != InstallationRole.MASTER)
          throw new SecurityException("Master required");
        boolean needsPairing = !Files.exists(root.resolve("secrets/terminal-p12.dpapi"));
        if (!needsPairing) {
          var priorTrust =
              JsonFiles.JSON.readValue(secrets.get("pairing-trust"), Pairing.Invitation.class);
          needsPairing = !priorTrust.clusterId().equals(identity.clusterId());
        }
        if (needsPairing) {
          var registry = new TerminalRegistry(root, Clock.systemUTC(), identity, secrets);
          var invitation =
              registry.invite("https://127.0.0.1:" + NetworkSettings.load(root).httpsPort());
          Exception failure = null;
          for (long deadline =
                  System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
              System.nanoTime() < deadline; )
            try {
              new TrustedStoreClient().pair(invitation, identity.terminalId(), secrets);
              failure = null;
              break;
            } catch (java.net.ConnectException
                | java.net.http.HttpConnectTimeoutException exception) {
              failure = exception;
              Thread.sleep(1000);
            }
          if (failure != null) throw failure;
        }
        var trust =
            JsonFiles.JSON.readValue(secrets.get("pairing-trust"), Pairing.Invitation.class);
        Exception readinessFailure = null;
        for (long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
            System.nanoTime() < deadline; )
          try {
            new TrustedStoreClient().identity(trust, secrets);
            readinessFailure = null;
            break;
          } catch (java.net.ConnectException
              | java.net.http.HttpConnectTimeoutException exception) {
            readinessFailure = exception;
            Thread.sleep(1000);
          }
        if (readinessFailure != null) throw readinessFailure;
      } else if (args[0].equals("complete")) {
        Installation identity = repository.load().orElseThrow();
        repository.save(
            identity.withState(
                identity.role() == InstallationRole.BACKUP_MASTER
                    ? "STANDBY_UNSEEDED"
                    : "INSTALLED"));
        System.out.println("INSTALLATION_RECORDED_SALES_UNAVAILABLE");
      } else {
        char[] password =
            System.console() != null
                ? System.console().readPassword("Installation administrator: ")
                : new java.io.BufferedReader(
                        new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8))
                    .readLine()
                    .toCharArray();
        try {
          administrator.verify(password);
        } finally {
          Arrays.fill(password, '\0');
        }
        Installation identity = repository.load().orElseThrow();
        if (identity.role() != InstallationRole.MASTER || !identity.state().equals("INSTALLED"))
          throw new SecurityException("Requires configured Master");
        var registry = new TerminalRegistry(root, Clock.systemUTC(), identity, secrets);
        if (args[0].equals("invite")) {
          Path destination = Path.of(args[2]);
          if (Files.exists(destination))
            throw new SecurityException("Refusing to overwrite an invitation");
          JsonFiles.write(destination, registry.invite(args[1]));
          System.out.println("Invitation written to protected file; expires in ten minutes");
        } else if (args[0].equals("revoke")) {
          registry.revoke(UUID.fromString(args[1]));
          System.out.println("Terminal revoked");
        } else throw new IllegalArgumentException("Supported: prepare, complete, invite, revoke");
      }
    }
  }
}
