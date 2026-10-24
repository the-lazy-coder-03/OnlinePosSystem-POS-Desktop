import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.WindowsSecretStore;

/** Disposable Windows CI client, compiled outside the installed distribution. Never prints secrets. */
public class WindowsStaffSmoke {
  public static void main(String[] args) throws Exception {
    var secrets = new WindowsSecretStore(Path.of(System.getenv("POS_DATA_ROOT")));
    var password = new String(secrets.get("terminal-password"), StandardCharsets.US_ASCII).toCharArray();
    var keys = KeyStore.getInstance("PKCS12");
    keys.load(new ByteArrayInputStream(secrets.get("terminal-p12")), password);
    var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(keys, password);
    java.util.Arrays.fill(password, '\0');
    var trust = KeyStore.getInstance("PKCS12");
    var trustPassword = new String(secrets.get("pki-password"), StandardCharsets.US_ASCII).toCharArray();
    trust.load(new ByteArrayInputStream(secrets.get("trust-p12")), trustPassword);
    java.util.Arrays.fill(trustPassword, '\0');
    var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tm.init(trust);
    var tls = SSLContext.getInstance("TLS");
    tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    String staffPassword = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
    try (var client = HttpClient.newBuilder().sslContext(tls).connectTimeout(java.time.Duration.ofSeconds(10)).build()) {
      URI base = URI.create("https://localhost:8443");
      var login = HttpRequest.newBuilder(base.resolve("/api/local/auth/login")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JsonFiles.JSON.writeValueAsString(Map.of("username", "ci.manager", "password", staffPassword)))).build();
      var response = client.send(login, HttpResponse.BodyHandlers.ofString());
      check(response.statusCode() == 200, "Staff login");
      String token = JsonFiles.JSON.readTree(response.body()).get("token").asText();
      get(client, base, "/api/local/menu", token, 200);
      get(client, base, "/api/local/branches/2/menu", token, 403);
      get(client, base, "/api/local/orders", token, 403);
      get(client, base, "/api/local/menu", null, 401);
      var logout = HttpRequest.newBuilder(base.resolve("/api/local/auth/logout")).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
      check(client.send(logout, HttpResponse.BodyHandlers.discarding()).statusCode() == 200, "Logout");
      get(client, base, "/api/local/menu", token, 401);
    }
    System.out.println("WINDOWS_STAGE3_STAFF_TLS_API_OK");
  }
  private static void get(HttpClient client, URI base, String path, String token, int expected) throws Exception {
    var request = HttpRequest.newBuilder(base.resolve(path));
    if (token != null) request.header("Authorization", "Bearer " + token);
    check(client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode() == expected, path);
  }
  private static void check(boolean success, String label) {
    if (!success) throw new IllegalStateException("Windows staff API check failed: " + label);
  }
}
