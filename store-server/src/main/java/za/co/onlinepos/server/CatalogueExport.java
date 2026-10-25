package za.co.onlinepos.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import za.co.onlinepos.contracts.CatalogueSnapshot;
import za.co.onlinepos.server.catalogue.SnapshotFormat;

/** Read-only export from an approved restored snapshot, never the operational cloud. */
public final class CatalogueExport {
  private CatalogueExport() {}

  public static void main(String[] args) throws Exception {
    Path target = Path.of(args[0]);
    int branch = Integer.parseInt(args[1]);
    za.co.onlinepos.contracts.Branch.fromId(branch);
    String url = System.getenv("POS_SNAPSHOT_DB_URL");
    if (url == null
        || !url.matches(
            "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/pos_catalogue_snapshot"))
      throw new IllegalArgumentException(
          "Explicit loopback pos_catalogue_snapshot restore required");
    if (!SnapshotFormat.SOURCE.equals(System.getenv("POS_SNAPSHOT_SOURCE_REVISION")))
      throw new IllegalArgumentException("Attest the reviewed restore source revision explicitly");
    var source =
        DatabaseTool.source(
            url, System.getenv("POS_SNAPSHOT_DB_USER"), System.getenv("POS_SNAPSHOT_DB_PASSWORD"));
    var format = new SnapshotFormat();
    var json =
        new ObjectMapper()
            .enable(
                com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    var tables = new LinkedHashMap<String, List<Map<String, Object>>>();
    try (var connection = source.getConnection()) {
      connection.setReadOnly(true);
      connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      var names = format.schema().fieldNames();
      while (names.hasNext()) {
        String table = names.next();
        String where =
            switch (table) {
              case "branch",
                  "branch_menu_item_price",
                  "branch_pizza_price",
                  "branch_extra_price",
                  "branch_pizza_base_option_price",
                  "branch_burger_component_price",
                  "special" ->
                  " WHERE branch_id=" + branch;
              case "special_day", "special_component", "special_addon" ->
                  " WHERE special_id IN (SELECT special_id FROM public.special WHERE branch_id="
                      + branch
                      + ")";
              case "special_component_menu_item", "special_component_pizza" ->
                  " WHERE special_component_id IN (SELECT special_component_id FROM public.special_component WHERE special_id IN (SELECT special_id FROM public.special WHERE branch_id="
                      + branch
                      + "))";
              default -> "";
            };
        // Table and column identifiers come exclusively from the compiled schema allowlist.
        var columns = new ArrayList<String>();
        format.schema().get(table).get("columns").fieldNames().forEachRemaining(columns::add);
        var actualColumns = new java.util.HashSet<String>();
        try (var metadata =
            connection.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=?")) {
          metadata.setString(1, table);
          try (var result = metadata.executeQuery()) {
            while (result.next()) actualColumns.add(result.getString(1));
          }
        }
        if (!actualColumns.equals(new java.util.HashSet<>(columns)))
          throw new IllegalArgumentException("Source schema drift: " + table);
        var rows = new ArrayList<Map<String, Object>>();
        try (var statement = connection.createStatement()) {
          statement.setQueryTimeout(30);
          try (var result =
              statement.executeQuery(
                  "SELECT row_to_json(r)::text FROM (SELECT "
                      + String.join(",", columns)
                      + " FROM public."
                      + table
                      + where
                      + ") r")) {
            while (result.next()) {
              @SuppressWarnings("unchecked")
              Map<String, Object> row = json.readValue(result.getString(1), Map.class);
              rows.add(row);
            }
          }
        }
        tables.put(table, rows);
      }
      connection.commit();
    }
    var preliminary =
        new CatalogueSnapshot(
            1,
            UUID.randomUUID(),
            branch,
            SnapshotFormat.SOURCE,
            Instant.now().toString(),
            SnapshotFormat.POLICY,
            tables,
            Map.of(),
            Map.of());
    var snapshot =
        new CatalogueSnapshot(
            1,
            preliminary.snapshotId(),
            branch,
            preliminary.sourceRevision(),
            preliminary.capturedAt(),
            preliminary.pricingPolicy(),
            tables,
            format.counts(preliminary),
            format.hashes(preliminary));
    format.validate(snapshot);
    String encoded = format.encode(snapshot);
    if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 15000000)
      throw new IllegalArgumentException("Snapshot exceeds import limit");
    Files.writeString(target, encoded + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
    System.out.println("Snapshot SHA-256: " + format.fingerprint(snapshot));
  }
}
