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
          if (Files.exists(root.resolve("administrator.json"))) administrator.verify(password);
          else administrator.initialize(password);
          SetupRequest request = envelope.request();
          var previous = repository.load();
          Installation identity =
              previous.orElseGet(
                  () ->
                      new Installation(
                          1,
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          request.branchId(),
                          request.role(),
                          request.terminalName(),
                          "CONFIGURING"));
          if (previous.isPresent()
              && (identity.branchId() != request.branchId() || identity.role() != request.role())) {
            if (identity.role() == InstallationRole.MASTER
                || Files.exists(root.resolve("postgres-data"))
                || Files.exists(root.resolve("pending-orders")))
              throw new SecurityException(
                  "Role change requires a separately verified data handover");
            identity =
                new Installation(
                    1,
                    identity.installationId(),
                    request.role() == InstallationRole.MASTER
                        ? UUID.randomUUID()
                        : identity.clusterId(),
                    identity.terminalId(),
                    request.branchId(),
                    request.role(),
                    request.terminalName(),
                    "CONFIGURING");
          }
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
            var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements())
              for (var address :
                  java.util.Collections.list(interfaces.nextElement().getInetAddresses()))
                if (address instanceof java.net.Inet4Address) hosts.add(address.getHostAddress());
            var server =
                Certificates.issue(
                    host, serverKey.getPublic(), ca, caKey.getPrivate(), false, false, hosts);
            String pkiPassword = UUID.randomUUID().toString();
            secrets.put("ca-key", caKey.getPrivate().getEncoded());
            secrets.put(
                "ca-certificate", Certificates.encode(ca).getBytes(StandardCharsets.US_ASCII));
            secrets.put("pki-password", pkiPassword.getBytes(StandardCharsets.US_ASCII));
            secrets.put(
                "server-p12",
                Certificates.store(serverKey.getPrivate(), server, ca, pkiPassword.toCharArray()));
            secrets.put("trust-p12", Certificates.store(null, null, ca, pkiPassword.toCharArray()));
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
          if (request.role() == InstallationRole.SLAVE) {
            Pairing.Invitation invitation =
                JsonFiles.JSON.readValue(request.invitation(), Pairing.Invitation.class);
            if (invitation.branchId() != request.branchId())
              throw new SecurityException("Invitation belongs to another branch");
            var reply = new TrustedStoreClient().pair(invitation, identity.terminalId(), secrets);
            identity =
                new Installation(
                    1,
                    identity.installationId(),
                    reply.clusterId(),
                    identity.terminalId(),
                    identity.branchId(),
                    identity.role(),
                    request.terminalName(),
                    "CONFIGURING");
            repository.save(identity);
          }
          System.out.println("PREPARED " + request.role().name());
        } finally {
          Arrays.fill(password, '\0');
        }
      } else if (args[0].equals("pair-local")) {
        Installation identity = repository.load().orElseThrow();
        if (identity.role() != InstallationRole.MASTER)
          throw new SecurityException("Master required");
        if (!Files.exists(root.resolve("secrets/terminal-p12.dpapi"))) {
          var registry = new TerminalRegistry(root, Clock.systemUTC(), identity, secrets);
          var invitation =
              registry.invite("https://localhost:" + NetworkSettings.load(root).httpsPort());
          Exception failure = null;
          for (int attempt = 0; attempt < 15; attempt++)
            try {
              new TrustedStoreClient().pair(invitation, identity.terminalId(), secrets);
              failure = null;
              break;
            } catch (java.net.ConnectException exception) {
              failure = exception;
              Thread.sleep(1000);
            }
          if (failure != null) throw failure;
        }
        var trust =
            JsonFiles.JSON.readValue(secrets.get("pairing-trust"), Pairing.Invitation.class);
        Exception readinessFailure = null;
        for (int attempt = 0; attempt < 15; attempt++)
          try {
            new TrustedStoreClient().identity(trust, secrets);
            readinessFailure = null;
            break;
          } catch (java.net.ConnectException exception) {
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
