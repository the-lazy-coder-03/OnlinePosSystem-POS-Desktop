package za.co.onlinepos.installation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import za.co.onlinepos.contracts.InstallationRole;

class LanTlsTest {
  @TempDir Path root;

  static final class Server implements AutoCloseable {
    final HttpsServer server;
    final TerminalRegistry registry;
    final String origin;
    volatile boolean legacyMetadata;

    Server(Path root, List<String> names) throws Exception {
      this(root, names, "127.0.0.1");
    }

    Server(Path root, List<String> names, String address) throws Exception {
      this(root, names, address, 1, null);
    }

    Server(Path root, List<String> names, String address, int branch, StoreProfile profile)
        throws Exception {
      var secrets = new LanEnrolmentTest.Secrets();
      var caKey = Certificates.keyPair();
      var ca =
          Certificates.issue(
              "Test CA", caKey.getPublic(), null, caKey.getPrivate(), true, false, List.of());
      secrets.put("ca-key", caKey.getPrivate().getEncoded());
      secrets.put("ca-certificate", Certificates.encode(ca).getBytes(StandardCharsets.US_ASCII));
      var identity =
          new Installation(
              1,
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              branch,
              InstallationRole.MASTER,
              "Master",
              "INSTALLED",
              profile);
      registry = new TerminalRegistry(root, Clock.systemUTC(), identity, secrets);
      var key = Certificates.keyPair();
      var cert =
          Certificates.issue(
              "Server", key.getPublic(), ca, caKey.getPrivate(), false, false, names);
      var store = KeyStore.getInstance("PKCS12");
      char[] password = UUID.randomUUID().toString().toCharArray();
      store.load(
          new java.io.ByteArrayInputStream(
              Certificates.store(key.getPrivate(), cert, ca, password)),
          password);
      var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      km.init(store, password);
      var trust = KeyStore.getInstance("PKCS12");
      trust.load(null, null);
      trust.setCertificateEntry("ca", ca);
      var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tm.init(trust);
      var tls = SSLContext.getInstance("TLS");
      tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
      server = HttpsServer.create(new InetSocketAddress(address, 0), 0);
      server.setHttpsConfigurator(
          new HttpsConfigurator(tls) {
            public void configure(HttpsParameters parameters) {
              var settings = tls.getDefaultSSLParameters();
              settings.setWantClientAuth(true);
              parameters.setSSLParameters(settings);
            }
          });
      server.createContext(
          "/api/local/",
          exchange -> {
            try {
              Object result =
                  switch (exchange.getRequestURI().getPath()) {
                    case "/api/local/setup/bootstrap" -> registry.bootstrap();
                    case "/api/local/setup/store" -> {
                      if (legacyMetadata)
                        throw new SecurityException("Legacy endpoint unavailable");
                      yield java.util.Map.of(
                          "branchId",
                          identity.branchId(),
                          "clusterId",
                          identity.clusterId(),
                          "profile",
                          profile == null ? java.util.Map.of() : profile);
                    }
                    case "/api/local/setup/challenge" ->
                        registry.challenge(
                            JsonFiles.JSON.readValue(
                                exchange.getRequestBody(), Pairing.JoinRequest.class));
                    case "/api/local/setup/enrol" ->
                        registry.enrol(
                            JsonFiles.JSON.readValue(
                                exchange.getRequestBody(), Pairing.Enrolment.class),
                            "127.0.0.1");
                    case "/api/local/identity" -> {
                      var peer = ((HttpsExchange) exchange).getSSLSession().getPeerCertificates();
                      if (!registry.authorized((java.security.cert.X509Certificate) peer[0]))
                        throw new SecurityException("Revoked");
                      yield new Pairing.Identity(
                          identity.installationId(),
                          identity.clusterId(),
                          identity.branchId(),
                          "MASTER",
                          "INSTALLED");
                    }
                    default -> throw new SecurityException("Unexpected endpoint");
                  };
              var bytes = JsonFiles.JSON.writeValueAsBytes(result);
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            } catch (Exception e) {
              exchange.sendResponseHeaders(403, -1);
            } finally {
              exchange.close();
            }
          });
      server.start();
      origin = "https://" + address + ":" + server.getAddress().getPort();
    }

    public void close() {
      server.stop(0);
    }
  }

  @Test
  void rediscoveryValidatesPinnedIdentityAndCachesOnlyTheNewLocation() throws Exception {
    String address =
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).stream()
            .flatMap(n -> java.util.Collections.list(n.getInetAddresses()).stream())
            .map(java.net.InetAddress::getHostAddress)
            .filter(LanPolicy::privateAddress)
            .findFirst()
            .orElse(null);
    org.junit.jupiter.api.Assumptions.assumeTrue(
        address != null, "Private IPv4 interface required for relocation integration test");
    var secrets = new LanEnrolmentTest.Secrets();
    try (var server = new Server(root.resolve("relocate"), List.of(address), address)) {
      var identity =
          LanPairingClient.connectAt(server.origin, 1, UUID.randomUUID(), "Counter", secrets);
      var pin = JsonFiles.JSON.readValue(secrets.get("pairing-trust"), Pairing.Invitation.class);
      // Simulate an obsolete location using an unused local port; discovery is just a hint.
      int deadPort;
      try (var socket = new java.net.ServerSocket(0)) {
        deadPort = socket.getLocalPort();
      }
      var obsolete = LanPairingClient.at(pin, "https://" + address + ":" + deadPort);
      var searches = new java.util.concurrent.atomic.AtomicInteger();
      var closed = new java.util.concurrent.atomic.AtomicInteger();
      var client =
          new TrustedStoreClient(
              () ->
                  new MasterDiscovery() {
                    public List<String> candidates() {
                      searches.incrementAndGet();
                      return List.of(server.origin);
                    }

                    public void close() {
                      closed.incrementAndGet();
                    }
                  });
      assertEquals(identity, client.identity(obsolete, secrets));
      assertEquals(identity, client.identity(obsolete, secrets));
      assertEquals(1, searches.get());
      assertEquals(1, closed.get());
      assertEquals(
          pin, JsonFiles.JSON.readValue(secrets.get("pairing-trust"), Pairing.Invitation.class));
    }
  }

  @Test
  void guidedSelectionReadsArbitraryStoreAndRejectsChangedIdentityWithoutReplacingTrust()
      throws Exception {
    String address =
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).stream()
            .flatMap(n -> java.util.Collections.list(n.getInetAddresses()).stream())
            .map(java.net.InetAddress::getHostAddress)
            .filter(LanPolicy::privateAddress)
            .findFirst()
            .orElse(null);
    org.junit.jupiter.api.Assumptions.assumeTrue(
        address != null, "Private IPv4 interface required");
    var network = new LanPolicy("test", "Test LAN", address, 24);
    var profile = new StoreProfile("Harbour Pizza", "Harbour Company", "HARBOUR");
    var client = new LanEnrolmentTest.Secrets();
    try (var server = new Server(root.resolve("guided"), List.of(address), address, 987, profile)) {
      var selected = LanPairingClient.inspect(server.origin, network);
      assertEquals(987, selected.branchId());
      assertEquals(profile, selected.profile());
      server.legacyMetadata = true;
      assertEquals(null, LanPairingClient.inspect(server.origin, network).profile());
      server.legacyMetadata = false;
      var terminal = UUID.randomUUID();
      var connected = LanPairingClient.connectSelected(selected, terminal, "Till", client);
      assertEquals(987, connected.branchId());
      assertEquals(connected, LanPairingClient.connectSelected(selected, terminal, "Till", client));
      byte[] pinned = client.get("pairing-trust");
      var changed =
          new MasterSelection(
              selected.origin(), 987, UUID.randomUUID(), selected.caFingerprint(), profile);
      assertThrows(
          SecurityException.class,
          () -> LanPairingClient.connectSelected(changed, terminal, "Till", client));
      assertArrayEquals(pinned, client.get("pairing-trust"));
      try (var replacement =
          new Server(root.resolve("guided-replacement"), List.of(address), address, 987, profile)) {
        var moved =
            new MasterSelection(
                replacement.origin,
                selected.branchId(),
                selected.clusterId(),
                selected.caFingerprint(),
                profile);
        assertThrows(
            Exception.class,
            () ->
                LanPairingClient.connectSelected(
                    moved, UUID.randomUUID(), "Other till", new LanEnrolmentTest.Secrets()));
      }
      assertThrows(
          SecurityException.class,
          () ->
              LanPairingClient.inspect(
                  server.origin, new LanPolicy("other", "Other LAN", "10.1.2.3", 24)));
    }
  }

  @Test
  void firstUsePinRegistrationRestartAndChangedAuthority() throws Exception {
    var clientSecrets = new LanEnrolmentTest.Secrets();
    UUID terminal = UUID.randomUUID();
    try (var server = new Server(root.resolve("master"), List.of("127.0.0.1"))) {
      var identity =
          LanPairingClient.connectAt(server.origin, 1, terminal, "Counter", clientSecrets);
      assertEquals(1, identity.branchId());
      var saved = clientSecrets.get("pairing-trust");
      assertEquals(
          identity,
          LanPairingClient.connectAt(server.origin, 1, terminal, "Counter", clientSecrets));
      assertArrayEquals(saved, clientSecrets.get("pairing-trust"));
      try (var replacement = new Server(root.resolve("replacement"), List.of("127.0.0.1"))) {
        assertThrows(
            Exception.class,
            () ->
                LanPairingClient.connectAt(
                    replacement.origin, 1, terminal, "Counter", clientSecrets));
        assertArrayEquals(saved, clientSecrets.get("pairing-trust"));
      }
      server.registry.revoke(terminal);
      assertThrows(
          SecurityException.class,
          () -> LanPairingClient.connectAt(server.origin, 1, terminal, "Counter", clientSecrets));
      assertArrayEquals(saved, clientSecrets.get("pairing-trust"));
    }
  }

  @Test
  void hostnameAndWrongBranchNeverCommitTrust() throws Exception {
    var secrets = new LanEnrolmentTest.Secrets();
    try (var wrongHost = new Server(root.resolve("wronghost"), List.of("wrong.example.invalid"))) {
      assertThrows(
          Exception.class,
          () ->
              LanPairingClient.connectAt(
                  wrongHost.origin, 1, UUID.randomUUID(), "Counter", secrets));
      assertThrows(java.nio.file.NoSuchFileException.class, () -> secrets.get("pairing-trust"));
    }
    try (var server = new Server(root.resolve("wrongbranch"), List.of("127.0.0.1"))) {
      assertThrows(
          SecurityException.class,
          () ->
              LanPairingClient.connectAt(server.origin, 2, UUID.randomUUID(), "Counter", secrets));
      assertThrows(java.nio.file.NoSuchFileException.class, () -> secrets.get("pairing-trust"));
    }
  }
}
