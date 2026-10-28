package za.co.onlinepos.server.dev;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
public class DevCatalogueImport {
  private final DevCatalogueRepository repository;

  public DevCatalogueImport(DevCatalogueRepository repository) {
    this.repository = repository;
  }

  public void apply(com.fasterxml.jackson.databind.JsonNode seed) {
    var store = repository.store();
    String hash = seed.path("sourceSha256").asText();
    if (!hash.matches("[0-9a-f]{64}"))
      throw new IllegalStateException("Invalid catalogue source checksum");
    store.tx.executeWithoutResult(
        status -> {
          store.lock();
          var receipts =
              store.jdbc.queryForList(
                  "SELECT source_hash FROM dev_meta.import_receipt", String.class);
          if (!receipts.isEmpty()) {
            if (!receipts.contains(hash))
              throw new IllegalStateException(
                  "Catalogue source changed; explicit migration required");
            return;
          }
          // Validate the full import against a disposable H2 mirror before touching live records.
          try (var reference =
              java.sql.DriverManager.getConnection(
                  "jdbc:h2:mem:import_"
                      + java.util.UUID.randomUUID()
                      + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                  "sa",
                  "")) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("db/h2/schema.sql"))
                .execute(
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                        reference, true));
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource(
                        "db/h2-migration/V7__category_archive.sql"))
                .populate(reference);
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource(
                        "db/h2-migration/V9__nested_categories.sql"))
                .populate(reference);
            var tempJdbc =
                new org.springframework.jdbc.core.JdbcTemplate(
                    new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                        reference, true));
            var temporary =
                new DevCatalogueRepository(new DevModuleStore(tempJdbc, store.json, store.tx));
            for (String table :
                repository.schema().properties().stream().map(Map.Entry::getKey).toList()) {
              if (!seed.path("tables").has(table))
                throw new IllegalStateException("Incomplete catalogue seed");
              for (var row : seed.path("tables").path(table)) temporary.insert(table, row);
            }
          } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Catalogue seed validation failed");
          }
          for (String table :
              repository.schema().properties().stream().map(Map.Entry::getKey).toList()) {
            var current = repository.rows(table);
            for (var row : seed.path("tables").path(table)) {
              var existing =
                  current.stream()
                      .filter(r -> repository.key(r, table).equals(repository.key(row, table)))
                      .findFirst();
              if (existing.isPresent()) {
                var expected = row.deepCopy();
                if (repository.columns(table).contains("archived") && !expected.has("archived"))
                  ((com.fasterxml.jackson.databind.node.ObjectNode) expected)
                      .put("archived", false);
                if (repository.columns(table).contains("parent_id") && !expected.has("parent_id"))
                  ((com.fasterxml.jackson.databind.node.ObjectNode) expected).putNull("parent_id");
                if (!new za.co.onlinepos.server.catalogue.SnapshotFormat()
                    .canonical(existing.get())
                    .equals(
                        new za.co.onlinepos.server.catalogue.SnapshotFormat().canonical(expected)))
                  throw new IllegalStateException(
                      "Catalogue import conflicts with existing "
                          + table
                          + "; existing records retained");
              } else repository.insert(table, row);
            }
          }
          store.jdbc.update(
              "UPDATE dev_meta.category_ids SET next_id=(SELECT COALESCE(MAX(id),0)+1 FROM public.menu_category) WHERE singleton=1");
          store.jdbc.update(
              "UPDATE dev_meta.store_ids SET next_id=(SELECT COALESCE(MAX(branch_id),0)+1 FROM public.branch) WHERE singleton=1");
          store.jdbc.update("INSERT INTO dev_meta.import_receipt(source_hash) VALUES(?)", hash);
          long revision = store.bump();
          store.event(
              DevModuleStore.identity("import", hash),
              "CATALOGUE_BOOTSTRAPPED",
              DevModuleStore.identity("branch", "1"),
              revision,
              Map.of("sourceChecksum", hash, "branchId", 1));
        });
  }
}
