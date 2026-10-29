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
  private final java.util.function.Supplier<MasterDiscovery> discoveryFactory;

  public TrustedStoreClient() {
    this(MdnsDiscovery::new);
  }

  TrustedStoreClient(java.util.function.Supplier<MasterDiscovery> discoveryFactory) {
    this.discoveryFactory = discoveryFactory;
  }

  HttpClient client(Pairing.Invitation invitation, SecretStore secrets, boolean authenticate)
      throws Exception {
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
    HttpResponse<byte[]> response;
    try (var http = client(invitation, secrets, false)) {
      response =
          http.send(
              HttpRequest.newBuilder(endpoint(invitation, "/api/local/setup/pair"))
                  .timeout(Duration.ofSeconds(15))
                  .header("Content-Type", "application/json")
                  .POST(
                      HttpRequest.BodyPublishers.ofByteArray(
                          JsonFiles.JSON.writeValueAsBytes(request)))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
    }
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

  private record Cached(String requestedOrigin, Pairing.Invitation trust) {}

  private volatile Cached lastConnection;

  private record Connection(Pairing.Invitation trust, Pairing.Identity identity) {}

  public Pairing.Identity identity(Pairing.Invitation invitation, SecretStore secrets)
      throws Exception {
    return connect(invitation, secrets).identity();
  }

  private Connection connect(Pairing.Invitation invitation, SecretStore secrets) throws Exception {
    String requestedOrigin = invitation.endpoint();
    var cached = lastConnection;
    if (cached != null
        && cached.requestedOrigin().equals(requestedOrigin)
        && cached.trust().branchId() == invitation.branchId()
        && cached.trust().clusterId().equals(invitation.clusterId())
        && cached.trust().caFingerprint().equals(invitation.caFingerprint()))
      invitation = cached.trust();
    try {
      var result = identityAt(invitation, secrets);
      lastConnection = new Cached(requestedOrigin, invitation);
      return new Connection(invitation, result);
    } catch (java.io.IOException | SecurityException initial) {
      try (var discovery = discoveryFactory.get()) {
        int attempts = 0;
        for (var endpoint : discovery.candidates()) {
          if (++attempts > 8) break;
          var uri = URI.create(endpoint);
          if (!LanPolicy.privateAddress(uri.getHost())) continue;
          var candidate = LanPairingClient.at(invitation, endpoint);
          try {
            var result = identityAt(candidate, secrets);
            lastConnection = new Cached(requestedOrigin, candidate);
            return new Connection(candidate, result);
          } catch (java.io.IOException | SecurityException ignored) {
            /* never change trust */
          }
        }
      } catch (java.io.IOException ignored) {
        /* retain original failure and credentials */
      }
      throw initial;
    }
  }

  Pairing.Identity identityAt(Pairing.Invitation invitation, SecretStore secrets) throws Exception {
    HttpResponse<byte[]> response;
    try (var http = client(invitation, secrets, true)) {
      response =
          http.send(
              HttpRequest.newBuilder(endpoint(invitation, "/api/local/identity"))
                  .timeout(Duration.ofSeconds(10))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
    }
    if (response.statusCode() != 200)
      throw new SecurityException("Trusted identity refused: " + response.statusCode());
    var identity = JsonFiles.JSON.readValue(response.body(), Pairing.Identity.class);
    if (!identity.clusterId().equals(invitation.clusterId())
        || identity.branchId() != invitation.branchId()
        || !"MASTER".equals(identity.role())
        || !"INSTALLED".equals(identity.state()))
      throw new SecurityException("Wrong branch or cluster");
    return identity;
  }

  /** Uses enrolled terminal TLS material and pinned store identity, never redirects. */
  public byte[] moduleRequest(
      Pairing.Invitation invitation,
      SecretStore secrets,
      String path,
      String method,
      byte[] body,
      String token)
      throws Exception {
    if (!java.util.Set.of(
            "/api/local/modules/enter",
            "/api/local/modules/me",
            "/api/local/modules/activity",
            "/api/local/modules/logout")
        .contains(path)) throw new IllegalArgumentException("Unsupported module endpoint");
    return authenticatedRequest(invitation, secrets, path, method, body, token);
  }

  public byte[] adminRequest(
      Pairing.Invitation invitation,
      SecretStore secrets,
      String path,
      String method,
      byte[] body,
      String token)
      throws Exception {
    var uri = URI.create(path);
    if (uri.isAbsolute()
        || uri.getRawAuthority() != null
        || uri.getRawFragment() != null
        || path.length() > 2048
        || !uri.getRawPath()
            .matches(
                "/api/local/admin/(system-access/developer-recovery(/disable)?|overview|terminals|terminals/[a-f0-9-]{36}|staff|staff/options|roles|audit|audit/(types|settings|events|events/[a-f0-9-]{36})|staff/[a-f0-9-]{36}/pin)")
        || !java.util.Set.of("GET", "PUT").contains(method))
      throw new IllegalArgumentException("Unsupported administration endpoint");
    return authenticatedRequest(invitation, secrets, path, method, body, token);
  }

  public byte[] customerRequest(
      Pairing.Invitation invitation,
      SecretStore secrets,
      String path,
      String method,
      byte[] body,
      String token)
      throws Exception {
    var uri = URI.create(path);
    if (uri.isAbsolute()
        || uri.getRawAuthority() != null
        || uri.getRawFragment() != null
        || path.length() > 4096
        || !uri.getRawPath()
            .matches(
                "/api/local/customers(/(context|lookup|sync|conflicts|[a-f0-9-]{36})(/(history|ban|archive|[a-f0-9-]{36}))?)?")
        || !java.util.Set.of("GET", "PUT").contains(method))
      throw new IllegalArgumentException("Unsupported customer endpoint");
    return authenticatedRequest(invitation, secrets, path, method, body, token);
  }

  public byte[] catalogueRequest(
      Pairing.Invitation invitation,
      SecretStore secrets,
      String path,
      String method,
      byte[] body,
      String token)
      throws Exception {
    if (!(path.equals("/api/local/catalogue/state") && method.equals("GET")
        || path.equals("/api/local/catalogue/records") && method.equals("PUT")
        || path.equals("/api/local/catalogue/items") && method.equals("GET")
        || path.equals("/api/local/catalogue/items/rules") && method.equals("PUT")
        || java.util.Set.of("/api/local/catalogue/items/quote", "/api/local/catalogue/items/drafts")
                .contains(path)
            && method.equals("POST")))
      throw new IllegalArgumentException("Unsupported catalogue endpoint");
    return authenticatedRequest(invitation, secrets, path, method, body, token);
  }

  private byte[] authenticatedRequest(
      Pairing.Invitation invitation,
      SecretStore secrets,
      String path,
      String method,
      byte[] body,
      String token)
      throws Exception {
    invitation = connect(invitation, secrets).trust();
    try (var http = client(invitation, secrets, true)) {
      var builder =
          HttpRequest.newBuilder(endpoint(invitation, path))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json");
      if (token != null) builder.header("Authorization", "Bearer " + token);
      builder.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        if (response.statusCode() == 400) {
          try {
            var error = JsonFiles.JSON.readTree(response.body());
            if (error.path("code").asText().equals("PRODUCT_VALIDATION"))
              throw new ModuleFailure(
                  400,
                  error.path("section").asText(),
                  error.path("field").asText(),
                  error.path("message").asText());
          } catch (com.fasterxml.jackson.core.JacksonException ignored) {
            /* Status remains authoritative. */
          }
        }
        throw new ModuleFailure(response.statusCode());
      }
      return response.body();
    }
  }

  public static final class ModuleFailure extends Exception {
    private final int status;
    private final String section;
    private final String field;

    public ModuleFailure(int status) {
      this(status, "", "", "Module request refused");
    }

    public ModuleFailure(int status, String section, String field, String message) {
      super(message);
      this.status = status;
      this.section = section;
      this.field = field;
    }

    public String section() {
      return section;
    }

    public String field() {
      return field;
    }

    public int status() {
      return status;
    }
  }
}
