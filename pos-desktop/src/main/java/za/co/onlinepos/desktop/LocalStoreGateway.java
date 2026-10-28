package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.TrustedStoreClient;

/** Explicit loopback development transport; all module/PIN authorization stays on the backend. */
final class LocalStoreGateway extends ModuleGateway {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final String ORIGIN = "http://127.0.0.1:18090";

  LocalStoreGateway() {
    super(Path.of("runtime/local-store"));
  }

  @Override
  public void check() throws Exception {
    send("/api/local/health", "GET", null, null);
  }

  @Override
  public String localLanAddress() {
    return "";
  }

  @Override
  public boolean ownerRecoveryAvailable() {
    return false;
  }

  @Override
  public JsonNode request(String endpoint, String method, byte[] body, String token)
      throws Exception {
    if (!java.util.Set.of("enter", "me", "activity", "logout").contains(endpoint))
      throw new IllegalArgumentException("Unknown module endpoint");
    return send("/api/local/modules/" + endpoint, method, body, token);
  }

  @Override
  public JsonNode admin(String path, String method, Object payload, String token) throws Exception {
    return json("/api/local/admin/" + path, method, payload, token);
  }

  @Override
  public JsonNode catalogue(String path, String method, Object payload, String token)
      throws Exception {
    return json("/api/local/catalogue/" + path, method, payload, token);
  }

  @Override
  public JsonNode customers(String path, String method, Object payload, String token)
      throws Exception {
    return json("/api/local/customers" + path, method, payload, token);
  }

  private JsonNode json(String path, String method, Object payload, String token) throws Exception {
    byte[] body = payload == null ? null : JsonFiles.JSON.writeValueAsBytes(payload);
    try {
      return send(path, method, body, token);
    } finally {
      if (body != null) Arrays.fill(body, (byte) 0);
    }
  }

  private JsonNode send(String path, String method, byte[] body, String token) throws Exception {
    URI uri = URI.create(ORIGIN + path);
    if (!uri.getHost().equals("127.0.0.1")
        || !uri.getPath().startsWith("/api/local/")
        || path.contains("..")
        || uri.getFragment() != null) throw new IllegalArgumentException("Local API path required");
    var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15));
    if (token != null) request.header("Authorization", "Bearer " + token);
    request.header("Content-Type", "application/json");
    var response =
        http.send(
            request
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.ofByteArray(new byte[0])
                        : HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() < 200 || response.statusCode() >= 300)
      throw new TrustedStoreClient.ModuleFailure(response.statusCode());
    return JsonFiles.JSON.readTree(response.body());
  }
}
