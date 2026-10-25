package za.co.onlinepos.contracts;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Branch catalogue edits. Keys are immutable; null originalKey creates a record. */
public final class CatalogueManagement {
  private CatalogueManagement() {}

  public record Change(
      String table, List<Long> originalKey, Map<String, Object> values, boolean remove) {}

  public record Edit(
      UUID commandId, long expectedRevision, List<Change> changes, boolean confirmSharedRecipe) {}
}
