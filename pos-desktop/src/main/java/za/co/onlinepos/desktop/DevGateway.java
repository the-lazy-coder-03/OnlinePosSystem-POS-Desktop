package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import za.co.onlinepos.installation.JsonFiles;

/** Development-only HTTP adapter. No database driver or credential is used by JavaFX. */
class DevGateway extends ModuleGateway {
  private final String token;
  private final String instance;
  private final String origin;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  static final class Failure extends Exception {
    final int status;
    final String field;
    final String code;
    String section = "";

    Failure(int status, String field, String code, String message) {
      super(message);
      this.status = status;
      this.field = field;
      this.code = code;
    }
  }

  DevGateway(String token, String instance, int port) {
    super(Path.of("."));
    if (token == null || token.length() < 32 || instance == null || port < 1 || port > 65535)
      throw new IllegalArgumentException("Development backend identity required");
    this.token = token;
    this.instance = instance;
    origin = "http://127.0.0.1:" + port;
  }

  @Override
  public void check() throws Exception {
    status();
  }

  @Override
  public String localLanAddress() {
    return "";
  }

  @Override
  public boolean ownerRecoveryAvailable() {
    return false;
  }

  JsonNode status() throws Exception {
    var status = call("/status", "GET", null);
    if (!"dev".equals(status.path("profile").asText())
        || !"H2".equals(status.path("database").asText())
        || !instance.equals(status.path("instanceId").asText()))
      throw new IllegalStateException("Unexpected development backend identity");
    return status;
  }

  JsonNode call(String path, String method, Object value) throws Exception {
    if (!path.startsWith("/") || path.contains("..") || path.contains("#"))
      throw new IllegalArgumentException("Invalid API path");
    byte[] bytes = value == null ? new byte[0] : JsonFiles.JSON.writeValueAsBytes(value);
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(URI.create(origin + "/api/local/dev/data-manager" + path))
                  .timeout(Duration.ofSeconds(10))
                  .header("Authorization", "Bearer " + token)
                  .header("Content-Type", "application/json")
                  .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      JsonNode body;
      try {
        body = JsonFiles.JSON.readTree(response.body());
      } catch (Exception invalid) {
        body = JsonFiles.JSON.createObjectNode();
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        String code = body.path("code").asText("REQUEST_FAILED");
        String message =
            switch (code) {
              case "DUPLICATE" -> "A category with this name already exists.";
              case "REVISION_CONFLICT" ->
                  "This category changed. Refresh and review before saving.";
              case "COMMAND_REUSED" ->
                  "This request conflicts with a previous save. Refresh before continuing.";
              case "VALIDATION" -> "Check the highlighted field.";
              default -> "The backend did not confirm this operation. Your edits are retained.";
            };
        var failure =
            new Failure(
                response.statusCode(),
                body.path("field").asText(),
                code,
                code.equals("PRODUCT_VALIDATION") ? body.path("message").asText() : message);
        failure.section = body.path("section").asText();
        throw failure;
      }
      return body;
    } finally {
      java.util.Arrays.fill(bytes, (byte) 0);
    }
  }

  private JsonNode module(String path, String method, Object value) throws Exception {
    try {
      return call("/grid/" + path, method, value);
    } catch (Failure refused) {
      throw new za.co.onlinepos.installation.TrustedStoreClient.ModuleFailure(
          refused.status, refused.section, refused.field, refused.getMessage());
    }
  }

  @Override
  public JsonNode catalogue(String path, String method, Object payload, String unused)
      throws Exception {
    return module("catalogue/" + path, method, payload);
  }

  @Override
  public JsonNode customers(String path, String method, Object payload, String unused)
      throws Exception {
    return module("customers" + path, method, payload);
  }

  @Override
  public JsonNode admin(String path, String method, Object payload, String unused)
      throws Exception {
    return module("admin/" + path, method, payload);
  }

  @Override
  public JsonNode request(String endpoint, String method, byte[] body, String unused)
      throws Exception {
    return module("context", "GET", null);
  }
}
