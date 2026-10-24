package za.co.onlinepos.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import za.co.onlinepos.contracts.InstallationRole;

class TrustedHttpsTest {
  @TempDir Path root;

  @Test
  void realTlsPairingRequiresHostnameTrustAndNonRevokedClientIdentity() throws Exception {
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
    var caKey = Certificates.keyPair();
    var ca =
        Certificates.issue(
            "Test CA", caKey.getPublic(), null, caKey.getPrivate(), true, false, List.of());
    var key = Certificates.keyPair();
    var cert =
        Certificates.issue(
            "localhost",
            key.getPublic(),
            ca,
            caKey.getPrivate(),
            false,
            false,
            List.of("localhost"));
    var serverSecrets = new InstallationSecurityTest.MemorySecrets();
    serverSecrets.put("ca-key", caKey.getPrivate().getEncoded());
    serverSecrets.put(
        "ca-certificate", Certificates.encode(ca).getBytes(StandardCharsets.US_ASCII));
    var registry = new TerminalRegistry(root, Clock.systemUTC(), identity, serverSecrets);
    char[] storePassword = UUID.randomUUID().toString().toCharArray();
    KeyStore keys = KeyStore.getInstance("PKCS12");
    keys.load(
        new java.io.ByteArrayInputStream(
            Certificates.store(key.getPrivate(), cert, ca, storePassword)),
        storePassword);
    KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(keys, storePassword);
    TrustManagerFactory tm =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tm.init(keys);
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(
        new HttpsConfigurator(tls) {
          @Override
          public void configure(HttpsParameters parameters) {
            var ssl = tls.getDefaultSSLParameters();
            ssl.setWantClientAuth(true);
            parameters.setSSLParameters(ssl);
          }
        });
    server.createContext(
        "/api/local/setup/pair",
        exchange -> {
          try {
            var request =
                JsonFiles.JSON.readValue(exchange.getRequestBody(), Pairing.Request.class);
            byte[] body = JsonFiles.JSON.writeValueAsBytes(registry.redeem(request));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } catch (Exception exception) {
            exchange.sendResponseHeaders(403, -1);
          } finally {
            exchange.close();
          }
        });
    server.createContext(
        "/api/local/identity",
        exchange -> {
          try {
            var peer =
                (X509Certificate)
                    ((com.sun.net.httpserver.HttpsExchange) exchange)
                        .getSSLSession()
                        .getPeerCertificates()[0];
            if (!registry.authorized(peer)) {
              exchange.sendResponseHeaders(403, -1);
              return;
            }
            byte[] body =
                JsonFiles.JSON.writeValueAsBytes(
                    new Pairing.Identity(
                        identity.installationId(), identity.clusterId(), 1, "MASTER", "INSTALLED"));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } catch (Exception exception) {
            exchange.sendResponseHeaders(403, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      String endpoint = "https://localhost:" + server.getAddress().getPort();
      var invitation = registry.invite(endpoint);
      var client = new TrustedStoreClient();
      var terminalSecrets = new InstallationSecurityTest.MemorySecrets();
      UUID terminal = UUID.randomUUID();
      assertEquals(
          identity.clusterId(), client.pair(invitation, terminal, terminalSecrets).clusterId());
      var wrongHost =
          new Pairing.Invitation(
              1,
              identity.clusterId(),
              1,
              "https://127.0.0.1:" + server.getAddress().getPort(),
              invitation.caCertificate(),
              invitation.caFingerprint(),
              invitation.token(),
              invitation.expiresAt());
      assertThrows(
          javax.net.ssl.SSLHandshakeException.class,
          () -> client.identity(wrongHost, terminalSecrets));
      registry.revoke(terminal);
      assertThrows(SecurityException.class, () -> client.identity(invitation, terminalSecrets));
    } finally {
      server.stop(0);
    }
  }
}
