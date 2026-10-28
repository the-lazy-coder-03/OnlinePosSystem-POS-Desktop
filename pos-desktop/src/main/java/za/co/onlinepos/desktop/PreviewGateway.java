package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import za.co.onlinepos.installation.JsonFiles;

/** Isolated read-only sample data. Never reads trust material or connects to a store. */
final class PreviewGateway extends ModuleGateway {
  private final PreviewItems itemPreview = new PreviewItems();
  private final PreviewCustomers customers = new PreviewCustomers();

  @Override
  public JsonNode customers(String path, String method, Object payload, String token)
      throws Exception {
    return customers.request(path, method, payload);
  }

  PreviewGateway() {
    super(Path.of("unused-preview-root"));
  }

  @Override
  public void check() {}

  @Override
  public JsonNode request(String endpoint, String method, byte[] body, String token) {
    return JsonFiles.JSON.createObjectNode();
  }

  @Override
  public JsonNode admin(String path, String method, Object payload, String token) {
    if (path.equals("items") || path.startsWith("items/"))
      return itemPreview.request(path, method, payload);
    if (!method.equals("GET"))
      throw new IllegalStateException("Saving is unavailable in this read-only preview.");
    if (path.equals("audit/types"))
      return JsonFiles.JSON.valueToTree(
          java.util.Arrays.stream(za.co.onlinepos.contracts.Auditing.EventType.values())
              .map(
                  t ->
                      Map.of(
                          "action", t.name(), "category", t.category(), "optional", t.optional()))
              .toList());
    if (path.equals("audit/settings")) {
      var settings = new java.util.TreeMap<String, Boolean>();
      for (var type : za.co.onlinepos.contracts.Auditing.EventType.values())
        if (type.optional()) settings.put(type.name(), true);
      return JsonFiles.JSON.valueToTree(Map.of("branchId", 1, "revision", 0, "settings", settings));
    }
    if (path.startsWith("audit/events"))
      return JsonFiles.JSON.valueToTree(
          Map.of("branchId", 1, "items", List.of(), "hasMore", false, "nextCursor", ""));
    if (path.equals("overview")) {
      var permissions = List.of("staff.manage", "rbac.manage", "audit.view");
      return JsonFiles.JSON.valueToTree(
          Map.of(
              "owner",
              false,
              "ownerConfigured",
              false,
              "username",
              "Preview user",
              "permissions",
              permissions,
              "implementedPermissions",
              permissions,
              "staffCount",
              0,
              "pendingPins",
              0,
              "roleCount",
              0));
    }
    if (path.equals("roles") || path.equals("staff/options") || path.equals("terminals"))
      return JsonFiles.JSON.createArrayNode();
    return JsonFiles.JSON.valueToTree(Map.of("items", List.of(), "hasMore", false));
  }

  @Override
  public JsonNode catalogue(String path, String method, Object payload, String token)
      throws Exception {
    if (path.equals("items") || path.startsWith("items/"))
      return itemPreview.request(path, method, payload);
    if (!method.equals("GET"))
      throw new IllegalStateException("Saving is unavailable in this read-only preview.");
    var result = JsonFiles.JSON.createObjectNode();
    result.put("revision", 0).put("branchId", 1).put("editable", false);
    try (var schema = getClass().getResourceAsStream("preview/catalogue-schema.json");
        var tables = getClass().getResourceAsStream("preview/catalogue-sample.json")) {
      result.set("schema", JsonFiles.JSON.readTree(schema));
      result.set("tables", JsonFiles.JSON.readTree(tables));
    }
    result
        .putArray("warnings")
        .add("Preview only — fictional sample catalogue; saving is disabled.");
    return result;
  }
}
