package za.co.onlinepos.installation;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/** Explicit first-use trust, confined to a single user-selected private IP. */
public final class LanPairingClient {
  private static final String UNUSED_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  private LanPairingClient() {}

  public static String origin(String ip) {
    if (!LanPolicy.privateAddress(ip))
      throw new IllegalArgumentException("Enter the Master's private LAN IPv4 address");
    return "https://" + ip + ":8443";
  }

  static Pairing.Invitation trust(Pairing.Bootstrap bootstrap, String origin) {
    return new Pairing.Invitation(
        1,
        bootstrap.clusterId(),
        bootstrap.branchId(),
        origin,
        bootstrap.caCertificate(),
        bootstrap.caFingerprint(),
        UNUSED_TOKEN,
        Long.MAX_VALUE);
  }

  public static Pairing.Invitation at(Pairing.Invitation trust, String origin) {
    return new Pairing.Invitation(
        1,
        trust.clusterId(),
        trust.branchId(),
        origin,
        trust.caCertificate(),
        trust.caFingerprint(),
        trust.token(),
        trust.expiresAt());
  }

  static byte[] exchange(
      java.net.http.HttpClient client, String origin, String path, Object payload)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(origin).resolve(path)).timeout(Duration.ofSeconds(10));
    if (payload == null) builder.GET();
    else
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofByteArray(JsonFiles.JSON.writeValueAsBytes(payload)));
    var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    try (var input = response.body()) {
      if (response.statusCode() != 200)
        throw new SecurityException("LAN pairing refused (" + response.statusCode() + ")");
      byte[] bytes = input.readNBytes(32769);
      if (bytes.length > 32768) throw new SecurityException("Oversized pairing response");
      return bytes;
    }
  }

  public static MasterSelection inspect(String origin, LanPolicy network) throws Exception {
    var uri = URI.create(origin);
    if (!"https".equals(uri.getScheme())
        || !LanPolicy.privateAddress(uri.getHost())
        || network == null
        || !network.contains(uri.getHost())
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !uri.getPath().isEmpty()
        || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)))
      throw new SecurityException("Choose a main computer on your store network");
    var trust = firstUse(origin, 0);
    try (var http = new TrustedStoreClient().client(trust, null, false)) {
      var info =
          JsonFiles.JSON.readValue(
              exchange(http, origin, "/api/local/setup/bootstrap", null), Pairing.Bootstrap.class);
      check(info, trust.branchId());
      if (!info.clusterId().equals(trust.clusterId())
          || !info.caFingerprint().equals(trust.caFingerprint()))
        throw new SecurityException("Main computer identity changed");
      StoreProfile profile = null;
      try {
        var metadata =
            JsonFiles.JSON.readTree(exchange(http, origin, "/api/local/setup/store", null));
        if (metadata.path("branchId").asInt() != info.branchId()
            || !info.clusterId().toString().equals(metadata.path("clusterId").asText()))
          throw new SecurityException("Store identity does not match the main computer");
        if (metadata.path("profile").has("storeName"))
          profile = JsonFiles.JSON.treeToValue(metadata.path("profile"), StoreProfile.class);
      } catch (SecurityException legacy) {
        if (!"LAN pairing refused (404)".equals(legacy.getMessage())
            && !"LAN pairing refused (403)".equals(legacy.getMessage())) throw legacy;
      }
      return new MasterSelection(
          origin, info.branchId(), info.clusterId(), info.caFingerprint(), profile);
    }
  }

  public static Pairing.Identity connectSelected(
      MasterSelection expected, UUID terminal, String name, SecretStore secrets) throws Exception {
    Pairing.Invitation pinned;
    try {
      pinned = JsonFiles.JSON.readValue(secrets.get("pairing-trust"), Pairing.Invitation.class);
    } catch (java.nio.file.NoSuchFileException missing) {
      pinned = firstUse(expected.origin(), expected.branchId());
      if (!pinned.clusterId().equals(expected.clusterId())
          || !pinned.caFingerprint().equals(expected.caFingerprint()))
        throw new SecurityException("Main computer changed since selection; check it again");
      secrets.put("pairing-trust", JsonFiles.JSON.writeValueAsBytes(pinned));
    }
    if (pinned.branchId() != expected.branchId()
        || !pinned.clusterId().equals(expected.clusterId())
        || !pinned.caFingerprint().equals(expected.caFingerprint()))
      throw new SecurityException("Cannot replace existing terminal trust");
    return connectAt(expected.origin(), expected.branchId(), terminal, name, secrets);
  }

  public static Pairing.Identity connect(
      String ip, int branch, UUID terminal, String name, SecretStore secrets) throws Exception {
    return connectAt(origin(ip), branch, terminal, name, secrets);
  }

  static Pairing.Identity connectAt(
      String origin, int branch, UUID terminal, String name, SecretStore secrets) throws Exception {
    Pairing.Invitation trust;
    try {
      trust = JsonFiles.JSON.readValue(secrets.get("pairing-trust"), Pairing.Invitation.class);
      if (trust.branchId() != branch)
        throw new SecurityException("Saved trust belongs to another branch");
      trust = at(trust, origin);
    } catch (java.nio.file.NoSuchFileException missing) {
      trust = firstUse(origin, branch);
      // Commit the first-use decision before enrolment, including interrupted attempts.
      secrets.put("pairing-trust", JsonFiles.JSON.writeValueAsBytes(trust));
    }
    var client = new TrustedStoreClient();
    try {
      return client.identityAt(trust, secrets);
    } catch (java.nio.file.NoSuchFileException missing) {
      /* registration is not complete */
    }
    KeyPair key;
    try {
      var privateKey = Certificates.privateKey(secrets.get("enrolment-key"));
      var publicKey =
          KeyFactory.getInstance("RSA")
              .generatePublic(new X509EncodedKeySpec(secrets.get("enrolment-public")));
      key = new KeyPair(publicKey, privateKey);
    } catch (java.nio.file.NoSuchFileException missing) {
      key = Certificates.keyPair();
      secrets.put("enrolment-public", key.getPublic().getEncoded());
      secrets.put("enrolment-key", key.getPrivate().getEncoded());
    }
    var request =
        new Pairing.JoinRequest(
            trust.clusterId(),
            branch,
            terminal,
            name,
            Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));
    Pairing.Reply reply;
    try (var http = client.client(trust, secrets, false)) {
      // An existing pin must still match the active Master before registration.
      var info =
          JsonFiles.JSON.readValue(
              exchange(http, origin, "/api/local/setup/bootstrap", null), Pairing.Bootstrap.class);
      check(info, branch);
      if (!info.clusterId().equals(trust.clusterId())
          || !info.caFingerprint().equals(trust.caFingerprint()))
        throw new SecurityException("Master identity changed");
      var challenge =
          JsonFiles.JSON.readValue(
              exchange(http, origin, "/api/local/setup/challenge", request),
              Pairing.Challenge.class);
      if (challenge.expiresAt() <= System.currentTimeMillis())
        throw new SecurityException("Pairing challenge expired");
      var proof = Signature.getInstance("SHA256withRSA");
      proof.initSign(key.getPrivate());
      proof.update(Pairing.proof(request, challenge.nonce()).getBytes(StandardCharsets.UTF_8));
      reply =
          JsonFiles.JSON.readValue(
              exchange(
                  http,
                  origin,
                  "/api/local/setup/enrol",
                  new Pairing.Enrolment(
                      request,
                      challenge.nonce(),
                      Base64.getEncoder().encodeToString(proof.sign()))),
              Pairing.Reply.class);
    }
    if (!reply.clusterId().equals(trust.clusterId())
        || reply.branchId() != branch
        || !reply.terminalId().equals(terminal))
      throw new SecurityException("Registration identity mismatch");
    var cert = Certificates.parse(reply.clientCertificate());
    cert.checkValidity();
    cert.verify(Certificates.parse(trust.caCertificate()).getPublicKey());
    if (!java.security.MessageDigest.isEqual(
        cert.getPublicKey().getEncoded(), key.getPublic().getEncoded()))
      throw new SecurityException("Terminal key mismatch");
    String password = UUID.randomUUID().toString();
    secrets.put("terminal-password", password.getBytes(StandardCharsets.US_ASCII));
    secrets.put(
        "terminal-p12",
        Certificates.store(
            key.getPrivate(),
            cert,
            Certificates.parse(trust.caCertificate()),
            password.toCharArray()));
    return client.identityAt(trust, secrets);
  }

  private static void check(Pairing.Bootstrap info, int branch) {
    if (info.version() != 1
        || info.branchId() <= 0
        || (branch != 0 && info.branchId() != branch)
        || info.clusterId() == null
        || info.installationId() == null
        || !"MASTER".equals(info.role())
        || !"INSTALLED".equals(info.state()))
      throw new SecurityException("Selected branch needs an active Master");
  }

  static Pairing.Invitation firstUse(String origin, int branch) throws Exception {
    var manager = new FirstUseTrust();
    var context = SSLContext.getInstance("TLS");
    context.init(null, new TrustManager[] {manager}, null);
    try (var http =
        java.net.http.HttpClient.newBuilder()
            .sslContext(context)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build()) {
      var info =
          JsonFiles.JSON.readValue(
              exchange(http, origin, "/api/local/setup/bootstrap", null), Pairing.Bootstrap.class);
      check(info, branch);
      if (manager.ca == null
          || !Certificates.fingerprint(manager.ca).equals(info.caFingerprint())
          || !Certificates.encode(manager.ca).equals(info.caCertificate()))
        throw new SecurityException("Bootstrap CA mismatch");
      return trust(info, origin);
    }
  }

  /** Validate PKIX and standard HTTPS hostname checks against the presented self-signed CA. */
  private static final class FirstUseTrust extends X509ExtendedTrustManager {
    volatile X509Certificate ca;

    private X509ExtendedTrustManager verifier(X509Certificate[] chain)
        throws java.security.cert.CertificateException {
      try {
        if (chain == null || chain.length < 2 || chain.length > 4)
          throw new SecurityException("Missing cluster CA");
        var root = chain[chain.length - 1];
        root.checkValidity();
        root.verify(root.getPublicKey());
        if (root.getBasicConstraints() < 0) throw new SecurityException("Invalid cluster CA");
        if (ca != null && !ca.equals(root))
          throw new SecurityException("CA changed during bootstrap");
        var keys = java.security.KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setCertificateEntry("ca", root);
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keys);
        ca = root;
        return (X509ExtendedTrustManager) factory.getTrustManagers()[0];
      } catch (Exception e) {
        throw new java.security.cert.CertificateException("First-use certificate invalid", e);
      }
    }

    public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e)
        throws java.security.cert.CertificateException {
      verifier(c).checkServerTrusted(c, a, e);
    }

    public void checkServerTrusted(X509Certificate[] c, String a, java.net.Socket s)
        throws java.security.cert.CertificateException {
      verifier(c).checkServerTrusted(c, a, s);
    }

    public void checkServerTrusted(X509Certificate[] c, String a)
        throws java.security.cert.CertificateException {
      throw new java.security.cert.CertificateException("HTTPS peer context required");
    }

    public void checkClientTrusted(X509Certificate[] c, String a)
        throws java.security.cert.CertificateException {
      throw new java.security.cert.CertificateException("Client trust unsupported");
    }

    public void checkClientTrusted(X509Certificate[] c, String a, java.net.Socket s)
        throws java.security.cert.CertificateException {
      checkClientTrusted(c, a);
    }

    public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e)
        throws java.security.cert.CertificateException {
      checkClientTrusted(c, a);
    }

    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
}
