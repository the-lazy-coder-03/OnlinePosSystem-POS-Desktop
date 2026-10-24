package za.co.onlinepos.installation;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class TrustedStoreClient implements PairingClient, StoreApiClient {
  private HttpClient client(
      Pairing.Invitation invitation, SecretStore secrets, boolean authenticate) throws Exception {
    var ca = Certificates.parse(invitation.caCertificate());
    ca.checkValidity();
    if (!Certificates.fingerprint(ca).equals(invitation.caFingerprint()))
      throw new SecurityException("CA fingerprint mismatch");
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("ca", ca);
    TrustManagerFactory tm =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tm.init(trust);
    KeyManagerFactory km = null;
    if (authenticate) {
      char[] password =
          new String(secrets.get("terminal-password"), java.nio.charset.StandardCharsets.US_ASCII)
              .toCharArray();
      KeyStore keys = KeyStore.getInstance("PKCS12");
      keys.load(new ByteArrayInputStream(secrets.get("terminal-p12")), password);
      km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      km.init(keys, password);
      java.util.Arrays.fill(password, '\0');
    }
    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(km == null ? null : km.getKeyManagers(), tm.getTrustManagers(), null);
    return HttpClient.newBuilder()
        .sslContext(tls)
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  private URI endpoint(Pairing.Invitation invitation, String path) {
    return URI.create(invitation.endpoint()).resolve(path);
  }

  public Pairing.Identity pair(Pairing.Invitation invitation, UUID terminalId, SecretStore secrets)
      throws Exception {
    if (invitation.expiresAt() <= System.currentTimeMillis())
      throw new SecurityException("Invitation expired");
    var key = Certificates.keyPair();
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(key.getPrivate());
    signature.update(
        (invitation.token() + ":" + terminalId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var request =
        new Pairing.Request(
            invitation.clusterId(),
            invitation.branchId(),
            terminalId,
            invitation.token(),
            Base64.getEncoder().encodeToString(key.getPublic().getEncoded()),
            Base64.getEncoder().encodeToString(signature.sign()));
    var response =
        client(invitation, secrets, false)
            .send(
                HttpRequest.newBuilder(endpoint(invitation, "/api/local/setup/pair"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofByteArray(
                            JsonFiles.JSON.writeValueAsBytes(request)))
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200)
      throw new SecurityException("Pairing refused: " + response.statusCode());
    var reply = JsonFiles.JSON.readValue(response.body(), Pairing.Reply.class);
    if (!reply.clusterId().equals(invitation.clusterId())
        || reply.branchId() != invitation.branchId()
        || !reply.terminalId().equals(terminalId))
      throw new SecurityException("Pairing identity mismatch");
    var cert = Certificates.parse(reply.clientCertificate());
    cert.verify(Certificates.parse(invitation.caCertificate()).getPublicKey());
    if (!MessageDigest.isEqual(cert.getPublicKey().getEncoded(), key.getPublic().getEncoded()))
      throw new SecurityException("Terminal certificate key mismatch");
    String password = UUID.randomUUID().toString();
    secrets.put("terminal-password", password.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    secrets.put(
        "terminal-p12",
        Certificates.store(
            key.getPrivate(),
            cert,
            Certificates.parse(invitation.caCertificate()),
            password.toCharArray()));
    secrets.put(
        "pairing-trust",
        JsonFiles.JSON.writeValueAsBytes(
            new Pairing.Invitation(
                1,
                invitation.clusterId(),
                invitation.branchId(),
                invitation.endpoint(),
                invitation.caCertificate(),
                invitation.caFingerprint(),
                invitation.token(),
                invitation.expiresAt())));
    return identity(invitation, secrets);
  }

  public Pairing.Identity identity(Pairing.Invitation invitation, SecretStore secrets)
      throws Exception {
    var response =
        client(invitation, secrets, true)
            .send(
                HttpRequest.newBuilder(endpoint(invitation, "/api/local/identity"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200)
      throw new SecurityException("Trusted identity refused: " + response.statusCode());
    var identity = JsonFiles.JSON.readValue(response.body(), Pairing.Identity.class);
    if (!identity.clusterId().equals(invitation.clusterId())
        || identity.branchId() != invitation.branchId())
      throw new SecurityException("Wrong branch or cluster");
    return identity;
  }
}
