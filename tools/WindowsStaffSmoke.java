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
    var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8));
    String staffPassword = input.readLine();
    String staffPin=input.readLine();
    String cashierPin=input.readLine();
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
        var schema=JsonFiles.JSON.readTree(java.nio.file.Files.readString(Path.of(args[1])));
        String hash = fingerprint(snapshot,schema);
        String endpoint = "/api/local/branches/1/catalogue/bootstrap";
        var command = Map.of("commandId", java.util.UUID.randomUUID().toString(), "expectedSha256", hash, "snapshot", snapshot);
        String content = JsonFiles.JSON.writeValueAsString(command);
        if (args.length == 2) {
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
        System.out.println(args.length==2 ? "WINDOWS_STAGE4_CATALOGUE_TLS_IMPORT_REPLAY_OK" : "WINDOWS_STAGE4_CATALOGUE_RESTART_PERSISTENCE_OK");
      }
      if(staffPin!=null) {
        var entry=HttpRequest.newBuilder(base.resolve("/api/local/modules/enter")).header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString(JsonFiles.JSON.writeValueAsString(Map.of("pin",staffPin,"module","REPORTS")))).build();
        var moduleReply=client.send(entry,HttpResponse.BodyHandlers.ofString());
        check(moduleReply.statusCode()==200,"Module PIN entry");
        String moduleToken=JsonFiles.JSON.readTree(moduleReply.body()).path("token").asText();
        get(client,base,"/api/local/modules/me",moduleToken,200);
        get(client,base,"/api/local/menu",moduleToken,401);
        get(client,base,"/api/local/modules/me",token,401);
        post(client,base,"/api/local/modules/logout",moduleToken,"{}",200);
        get(client,base,"/api/local/modules/me",moduleToken,401);
        System.out.println("WINDOWS_STAGE5_MODULE_PIN_TLS_BOUNDARIES_OK");
        if(cashierPin!=null) administration(client,base,staffPin,cashierPin,args.length==2);
      }
      var logout = HttpRequest.newBuilder(base.resolve("/api/local/auth/logout")).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
      check(client.send(logout, HttpResponse.BodyHandlers.discarding()).statusCode() == 200, "Logout");
      get(client, base, "/api/local/menu", token, 401);
      if(staffPin!=null && cashierPin!=null) catalogueManagement(client,base,staffPin,cashierPin,args.length==2);
    }
    System.out.println("WINDOWS_STAGE3_STAFF_TLS_API_OK");
  }

  private static com.fasterxml.jackson.databind.JsonNode jsonRequest(HttpClient client,URI base,String path,String method,String token,Object payload,int expected) throws Exception {
    var request=HttpRequest.newBuilder(base.resolve(path)).header("Content-Type","application/json");
    if(token!=null)request.header("Authorization","Bearer "+token);
    request.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JsonFiles.JSON.writeValueAsString(payload)));
    var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    check(response.statusCode()==expected,"Administration HTTP "+response.statusCode()+" expected "+expected);
    return expected==200?JsonFiles.JSON.readTree(response.body()):JsonFiles.JSON.createObjectNode();
  }
  private static void administration(HttpClient client,URI base,String ownerPin,String cashierPin,boolean initial) throws Exception {
    String access=jsonRequest(client,base,"/api/local/modules/enter","POST",null,Map.of("pin",ownerPin,"module","ADMINISTRATION"),200).path("token").asText();
    var overview=jsonRequest(client,base,"/api/local/admin/overview","GET",access,null,200);
    check(overview.path("owner").asBoolean() && overview.path("ownerConfigured").asBoolean(),"Explicit owner designation");
    jsonRequest(client,base,"/api/local/admin/staff/options","GET",access,null,200);
    jsonRequest(client,base,"/api/local/admin/roles","GET",access,null,200);
    if(initial) {
      jsonRequest(client,base,"/api/local/admin/roles","PUT",access,Map.of("commandId",java.util.UUID.randomUUID(),"name","CI_ADMIN_VIEWER","permissions",java.util.List.of("module.administration","audit.view","module.catalogue"),"expectedRevision",-1),200);
      var id=java.util.UUID.randomUUID();
      var create=Map.of("commandId",java.util.UUID.randomUUID(),"staffId",id,"username","ci.admin.cashier","enabled",true,"roles",java.util.List.of("CASHIER","CI_ADMIN_VIEWER"),"expectedRevision",-1);
      jsonRequest(client,base,"/api/local/admin/staff","PUT",access,create,200);
      jsonRequest(client,base,"/api/local/admin/staff","PUT",access,create,200);
      jsonRequest(client,base,"/api/local/admin/staff/"+id+"/pin","PUT",access,Map.of("commandId",java.util.UUID.randomUUID(),"expectedRevision",0,"pin",cashierPin),200);
    }
    var listing=jsonRequest(client,base,"/api/local/admin/staff?search=ci.admin.cashier&page=0","GET",access,null,200);
    check(listing.path("items").size()==1,"PIN-only staff survived provisioning/restart");
    var staff=listing.path("items").get(0);
    check(staff.path("pinConfigured").asBoolean() && !staff.path("passwordLoginEnabled").asBoolean(),"PIN-only credentials");
    check(!listing.toString().contains(cashierPin) && !listing.toString().contains("lookup") && !listing.toString().contains("600000:"),"Safe staff response");
    String viewer=jsonRequest(client,base,"/api/local/modules/enter","POST",null,Map.of("pin",cashierPin,"module","ADMINISTRATION"),200).path("token").asText();
    jsonRequest(client,base,"/api/local/admin/staff","GET",viewer,null,403);
    jsonRequest(client,base,"/api/local/admin/roles","GET",viewer,null,403);
    jsonRequest(client,base,"/api/local/admin/staff/"+staff.path("staffId").asText()+"/pin","PUT",viewer,Map.of("commandId",java.util.UUID.randomUUID(),"expectedRevision",staff.path("revision").asLong(),"pin",cashierPin),403);
    var audit=jsonRequest(client,base,"/api/local/admin/audit?action=ADMIN_PIN_CHANGED&actor=ci.manager&page=0","GET",viewer,null,200);
    check(!audit.path("items").isEmpty() && !audit.toString().contains(cashierPin) && !audit.toString().contains("details"),"Read-only filtered audit");
    post(client,base,"/api/local/modules/logout",viewer,"{}",200);
    post(client,base,"/api/local/modules/logout",access,"{}",200);
    System.out.println("WINDOWS_ADMINISTRATION_OWNER_CRUD_AUDIT_TLS_OK");
  }
  private static void catalogueManagement(HttpClient client,URI base,String ownerPin,String viewerPin,boolean initial) throws Exception {
    String admin=jsonRequest(client,base,"/api/local/modules/enter","POST",null,Map.of("pin",ownerPin,"module","ADMINISTRATION"),200).path("token").asText();
    if(initial) {
      jsonRequest(client,base,"/api/local/admin/roles","PUT",admin,Map.of("commandId",java.util.UUID.randomUUID(),"name","CI_CATALOGUE_EDITOR","permissions",java.util.List.of("module.catalogue","catalogue.manage"),"expectedRevision",-1),200);
      var owner=jsonRequest(client,base,"/api/local/admin/staff?search=ci.manager&page=0","GET",admin,null,200).path("items").get(0);
      jsonRequest(client,base,"/api/local/admin/staff","PUT",admin,Map.of("commandId",java.util.UUID.randomUUID(),"staffId",owner.path("staffId").asText(),"username","ci.manager","enabled",true,"roles",java.util.List.of("MANAGER","CI_CATALOGUE_EDITOR"),"expectedRevision",owner.path("revision").asLong()),200);
      jsonRequest(client,base,"/api/local/admin/overview","GET",admin,null,401);
    } else post(client,base,"/api/local/modules/logout",admin,"{}",200);
    String access=jsonRequest(client,base,"/api/local/modules/enter","POST",null,Map.of("pin",ownerPin,"module","CATALOGUE"),200).path("token").asText();
    var state=jsonRequest(client,base,"/api/local/catalogue/state","GET",access,null,200);
    check(state.path("editable").asBoolean(),"Explicit catalogue permission");
    if(initial) {
      var category=state.path("tables").path("price_category").get(0).path("price_category_id").asLong();
      var change=Map.of("table","ingredient","values",Map.of("name","CI catalogue ingredient","price_category_id",category,"active",true,"seasonal",false),"remove",false);
      var command=Map.of("commandId",java.util.UUID.randomUUID(),"expectedRevision",state.path("revision").asLong(),"changes",java.util.List.of(change),"confirmSharedRecipe",false);
      jsonRequest(client,base,"/api/local/catalogue/records","PUT",access,command,200);
      jsonRequest(client,base,"/api/local/catalogue/records","PUT",access,command,200);
      state=jsonRequest(client,base,"/api/local/catalogue/state","GET",access,null,200);
      var item=state.path("tables").path("menu_item").get(0).deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode)item).put("description","CI locally edited item");
      var update=Map.of("table","menu_item","originalKey",java.util.List.of(item.path("id").asLong()),"values",item,"remove",false);
      jsonRequest(client,base,"/api/local/catalogue/records","PUT",access,Map.of("commandId",java.util.UUID.randomUUID(),"expectedRevision",state.path("revision").asLong(),"changes",java.util.List.of(update),"confirmSharedRecipe",false),200);
    }
    state=jsonRequest(client,base,"/api/local/catalogue/state","GET",access,null,200);
    check(state.path("revision").asLong()==2,"Catalogue revision persisted");
    check(state.path("tables").path("ingredient").valueStream().anyMatch(r->r.path("name").asText().equals("CI catalogue ingredient")),"Created ingredient persisted");
    check(state.path("tables").path("menu_item").valueStream().anyMatch(r->r.path("description").asText().equals("CI locally edited item")),"Edited item persisted");
    jsonRequest(client,base,"/api/local/admin/staff","GET",access,null,403);
    String viewer=jsonRequest(client,base,"/api/local/modules/enter","POST",null,Map.of("pin",viewerPin,"module","CATALOGUE"),200).path("token").asText();
    check(!jsonRequest(client,base,"/api/local/catalogue/state","GET",viewer,null,200).path("editable").asBoolean(),"Read-only catalogue browse");
    jsonRequest(client,base,"/api/local/catalogue/records","PUT",viewer,Map.of("commandId",java.util.UUID.randomUUID(),"expectedRevision",2,"changes",java.util.List.of(),"confirmSharedRecipe",false),403);
    post(client,base,"/api/local/modules/logout",viewer,"{}",200);
    post(client,base,"/api/local/modules/logout",access,"{}",200);
    System.out.println("WINDOWS_CATALOGUE_ITEMS_INGREDIENTS_CRUD_TLS_OK");
  }
  private static String fingerprint(com.fasterxml.jackson.databind.JsonNode snapshot,com.fasterxml.jackson.databind.JsonNode schema) throws Exception {
    var normalized=(com.fasterxml.jackson.databind.node.ObjectNode)snapshot.deepCopy();
    var tables=JsonFiles.JSON.createObjectNode();
    schema.properties().forEach(entry -> {
      var rows=new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
      snapshot.path("tables").path(entry.getKey()).forEach(rows::add);
      rows.sort(java.util.Comparator.comparing(row -> {
        var key=JsonFiles.JSON.createArrayNode();entry.getValue().path("keys").forEach(k -> key.add(row.get(k.asText())));
        return canonical(key).toString();
      }));
      tables.set(entry.getKey(),JsonFiles.JSON.valueToTree(rows));
    });
    normalized.set("tables",tables);
    return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(canonical(normalized).toString().getBytes(StandardCharsets.UTF_8)));
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
