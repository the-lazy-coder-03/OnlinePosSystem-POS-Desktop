package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import za.co.onlinepos.contracts.Customers;
import za.co.onlinepos.installation.JsonFiles;

/** In-memory fictional profiles only; never used by the real customer gateway. */
final class PreviewCustomers {
  private final Map<UUID, ObjectNode> rows = new LinkedHashMap<>();

  PreviewCustomers() {
    for (String[] row :
        List.of(
            new String[] {"Alex", "Morgan", "0825550101", "14 Sample Lane"},
            new String[] {"Jordan", "Daniels", "0835550102", "8 Example Road"},
            new String[] {"Sam", "Williams", "0845550103", ""})) {
      var id = UUID.randomUUID();
      var fields =
          Customers.validate(
              Map.of(
                  "first_name",
                  row[0],
                  "last_name",
                  row[1],
                  "phone1",
                  row[2],
                  "formatted_address",
                  row[3],
                  "preferred_store",
                  "Kenridge"));
      rows.put(id, record(id, fields, 0));
    }
  }

  private ObjectNode record(UUID id, Map<String, String> fields, long revision) {
    var node = JsonFiles.JSON.createObjectNode();
    node.put("id", id.toString())
        .put("name", (fields.get("first_name") + " " + fields.get("last_name")).strip())
        .put("revision", revision)
        .put("archived", false)
        .put("banned", false)
        .put("banReason", "")
        .put("syncState", "PREVIEW_ONLY");
    node.set("profile", JsonFiles.JSON.valueToTree(fields));
    return node;
  }

  synchronized JsonNode request(String path, String method, Object payload) throws Exception {
    if (path.equals("/context"))
      return JsonFiles.JSON.valueToTree(
          Map.of(
              "branchId",
              1,
              "branchName",
              "Kenridge",
              "permissions",
              Customers.PERMISSIONS,
              "syncEnabled",
              false));
    if (path.equals("/sync"))
      return JsonFiles.JSON.valueToTree(
          Map.of(
              "enabled",
              false,
              "pending",
              0,
              "message",
              "Preview only — fictional records stay in memory."));
    if (path.startsWith("/conflicts"))
      return JsonFiles.JSON.valueToTree(Map.of("items", List.of(), "hasMore", false));
    if (path.startsWith("/lookup?")) {
      String phone =
          Customers.phone(
              URLDecoder.decode(path.substring(path.indexOf('=') + 1), StandardCharsets.UTF_8),
              true);
      var match =
          rows.values().stream()
              .filter(n -> n.path("profile").path("phone1").asText().equals(phone))
              .findFirst();
      return match.isPresent()
          ? JsonFiles.JSON.valueToTree(Map.of("status", "FOUND", "customer", match.get()))
          : JsonFiles.JSON.valueToTree(
              Map.of(
                  "status",
                  "REMOTE_UNAVAILABLE",
                  "message",
                  "Remote lookup unavailable in preview."));
    }
    if (path.isEmpty() || path.startsWith("?")) {
      String query = "";
      boolean archived = false;
      for (String part : path.replaceFirst("^\\?", "").split("&")) {
        String[] item = part.split("=", 2);
        if (item.length == 2) {
          if (item[0].equals("query"))
            query =
                URLDecoder.decode(item[1], StandardCharsets.UTF_8)
                    .toLowerCase(java.util.Locale.ROOT);
          if (item[0].equals("archived")) archived = Boolean.parseBoolean(item[1]);
        }
      }
      final String term = query;
      final boolean inactive = archived;
      var items =
          rows.values().stream()
              .filter(
                  n ->
                      n.path("archived").asBoolean() == inactive
                          && (n.path("name")
                                  .asText()
                                  .toLowerCase(java.util.Locale.ROOT)
                                  .contains(term)
                              || n.path("profile")
                                  .toString()
                                  .toLowerCase(java.util.Locale.ROOT)
                                  .contains(term)))
              .sorted(java.util.Comparator.comparing(n -> n.path("name").asText()))
              .toList();
      return JsonFiles.JSON.valueToTree(Map.of("items", items, "hasMore", false, "nextCursor", ""));
    }
    String[] parts = path.split("/");
    UUID id = UUID.fromString(parts[1]);
    var row = rows.get(id);
    if (method.equals("PUT") && payload instanceof Customers.Edit edit) {
      var fields = Customers.validate(edit.fields());
      if (row != null && row.path("revision").asLong() != edit.expectedRevision())
        throw new IllegalArgumentException("Stale preview record");
      var saved = record(id, fields, row == null ? 0 : row.path("revision").asLong() + 1);
      if (row != null) {
        saved.put("archived", row.path("archived").asBoolean());
        saved.put("banned", row.path("banned").asBoolean());
        saved.put("banReason", row.path("banReason").asText());
      }
      rows.put(id, saved);
      return saved.deepCopy();
    }
    if (row == null) throw new IllegalArgumentException("Unknown preview customer");
    if (path.contains("/history"))
      return JsonFiles.JSON.valueToTree(
          Map.of("items", List.of(), "statusHistory", List.of(), "hasMore", false));
    if (method.equals("PUT") && payload instanceof Customers.Status status) {
      if (path.endsWith("/ban")) {
        if (status.value() && (status.reason() == null || status.reason().isBlank()))
          throw new IllegalArgumentException("Reason required");
        row.put("banned", status.value()).put("banReason", status.value() ? status.reason() : "");
      } else row.put("archived", status.value());
      row.put("revision", row.path("revision").asLong() + 1);
    }
    return row.deepCopy();
  }
}
