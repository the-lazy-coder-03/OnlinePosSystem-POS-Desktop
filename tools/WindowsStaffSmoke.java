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
    if(staffPassword!=null && staffPassword.startsWith("\uFEFF")) staffPassword=staffPassword.substring(1);
    try (var client = HttpClient.newBuilder().sslContext(tls).connectTimeout(java.time.Duration.ofSeconds(10)).build()) {
      URI base = URI.create("https://localhost:8443");
      var login = HttpRequest.newBuilder(base.resolve("/api/local/auth/login")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JsonFiles.JSON.writeValueAsString(Map.of("username", "ci.manager", "password", staffPassword)))).build();
      var response = client.send(login, HttpResponse.BodyHandlers.ofString());
      check(response.statusCode() == 200, "Staff login HTTP " + response.statusCode());
      String token = JsonFiles.JSON.readTree(response.body()).get("token").asText();
      get(client, base, "/api/local/menu", token, 200);
      get(client, base, "/api/local/branches/2/menu", token, 403);
      get(client, base, "/api/local/orders", token, 403);
      get(client, base, "/api/local/menu", null, 401);
      if (args.length > 0) {
        var snapshot = JsonFiles.JSON.readTree(java.nio.file.Files.readString(Path.of(args[0])));
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical(snapshot).toString().getBytes(StandardCharsets.UTF_8)));
        String endpoint = "/api/local/branches/1/catalogue/bootstrap";
        var command = Map.of("commandId", java.util.UUID.randomUUID().toString(), "expectedSha256", hash, "snapshot", snapshot);
        String content = JsonFiles.JSON.writeValueAsString(command);
        if (args.length == 1) {
          post(client,base,endpoint,token,content,200);
          post(client,base,endpoint,token,content,200);
          post(client,base,"/api/local/branches/2/catalogue/bootstrap",token,content,403);
          post(client,base,endpoint,null,content,401);
        }
        var catalogueRequest = HttpRequest.newBuilder(base.resolve("/api/local/branches/1/catalogue"))
            .header("Authorization", "Bearer " + token).build();
        var catalogue = client.send(catalogueRequest,HttpResponse.BodyHandlers.ofString());
        check(catalogue.statusCode()==200,"Installed catalogue read");
        var saved=JsonFiles.JSON.readTree(catalogue.body());
        check(hash.equals(saved.path("sha256").asText()),"Catalogue hash survived import/restart");
        check(canonical(snapshot).equals(canonical(saved.path("snapshot"))),"Full catalogue survived import/restart");
        check(saved.path("effectivePrices").size()==241,"Installed branch baseline price count");
        get(client,base,"/api/local/branches/2/catalogue",token,403);
        System.out.println(args.length==1 ? "WINDOWS_STAGE4_CATALOGUE_TLS_IMPORT_REPLAY_OK" : "WINDOWS_STAGE4_CATALOGUE_RESTART_PERSISTENCE_OK");
      }
      var logout = HttpRequest.newBuilder(base.resolve("/api/local/auth/logout")).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
      check(client.send(logout, HttpResponse.BodyHandlers.discarding()).statusCode() == 200, "Logout");
      get(client, base, "/api/local/menu", token, 401);
    }
    System.out.println("WINDOWS_STAGE3_STAFF_TLS_API_OK");
  }
  private static com.fasterxml.jackson.databind.JsonNode canonical(com.fasterxml.jackson.databind.JsonNode value) {
    if(value.isNumber()) return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
    if(value.isObject()) {
      var sorted=new java.util.TreeMap<String,com.fasterxml.jackson.databind.JsonNode>();
      value.properties().forEach(e -> sorted.put(e.getKey(),canonical(e.getValue())));
      var result=JsonFiles.JSON.createObjectNode();sorted.forEach(result::set);return result;
    }
    if(value.isArray()) {var result=JsonFiles.JSON.createArrayNode();value.forEach(v -> result.add(canonical(v)));return result;}
    return value;
  }
  private static void post(HttpClient client,URI base,String path,String token,String body,int expected) throws Exception {
    var request=HttpRequest.newBuilder(base.resolve(path)).header("Content-Type","application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if(token!=null) request.header("Authorization","Bearer "+token);
    var response=client.send(request.build(),HttpResponse.BodyHandlers.discarding());
    check(response.statusCode()==expected,path+" HTTP "+response.statusCode());
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
