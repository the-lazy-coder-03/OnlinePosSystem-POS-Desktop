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

  static final java.util.UUID SCREEN_STORE =
      java.util.UUID.fromString("198f13ec-1d88-4b46-aa73-b2c6f4317421");

  @Override
  public JsonNode screens(String path, String method, Object payload, String token) {
    if (method.equals("GET") && path.equals("publication"))
      return JsonFiles.JSON.valueToTree(Map.of("storeId", SCREEN_STORE, "sequence", 0));
    if (method.equals("GET") && path.startsWith("events?after="))
      return JsonFiles.JSON.valueToTree(List.of());
    if (method.equals("GET") && path.equals("context"))
      return JsonFiles.JSON.valueToTree(
          Map.of(
              "storeId",
              SCREEN_STORE,
              "catalog",
              itemPreview.request("items", "GET", null),
              "definitions",
              List.of(),
              "capabilities",
              za.co.onlinepos.contracts.screen.SalesScreens.draftCapabilities()));
    if (method.equals("POST") && path.equals("preview")) {
      var doc = (za.co.onlinepos.contracts.screen.SalesScreens.Document) payload;
      try {
        var catalog =
            JsonFiles.JSON.treeToValue(
                itemPreview.request("items", "GET", null),
                za.co.onlinepos.contracts.ItemCustomization.Catalog.class);
        return JsonFiles.JSON.valueToTree(
            Map.of(
                "warnings",
                za.co.onlinepos.contracts.screen.ScreenValidation.validate(
                    doc,
                    SCREEN_STORE,
                    catalog.products().keySet(),
                    za.co.onlinepos.contracts.screen.ScreenValidation.Limits.standard())));
      } catch (Exception e) {
        throw new IllegalArgumentException(e.getMessage());
      }
    }
    throw new IllegalStateException("Isolated preview cannot save, publish or open store records.");
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
    if (path.startsWith("products/")) {
      String[] parts = path.split("/");
      String kind = parts[1];
      long id = Long.parseLong(parts[2]);
      JsonNode details = null;
      for (var row : result.path("tables").path(kind))
        if (row.path(kind.equals("pizza") ? "pizza_id" : "id").asLong() == id) details = row;
      if (details == null) throw new IllegalArgumentException("Sample product not found");
      var recipe = JsonFiles.JSON.createObjectNode().put("family", "ingredient");
      var ingredients = recipe.putArray("ingredients");
      for (var row : result.path("tables").path("ingredient")) {
        var ingredient =
            ingredients
                .addObject()
                .put("key", "ingredient:" + row.path("ingredient_id").asLong())
                .put("name", row.path("name").asText())
                .put("available", row.path("active").asBoolean())
                .put("compatible", true)
                .put("selected", false);
        var origins = ingredient.putArray("origins");
        if (kind.equals("pizza"))
          for (var link : result.path("tables").path("pizza_default_ingredient"))
            if (link.path("pizza_id").asLong() == id
                && link.path("ingredient_id").equals(row.path("ingredient_id"))) {
              ingredient.put("selected", true);
              origins.addObject().put("table", "pizza_default_ingredient").set("properties", link);
            }
      }
      return JsonFiles.JSON.valueToTree(
          Map.of(
              "state",
              result,
              "catalog",
              itemPreview.request("items", "GET", null),
              "details",
              details,
              "recipe",
              recipe,
              "productRevision",
              "preview"));
    }
    result
        .putArray("warnings")
        .add("Preview only — fictional sample catalogue; saving is disabled.");
    return result;
  }
}
