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
      UUID commandId,
      long expectedRevision,
      List<Change> changes,
      boolean confirmSharedRecipe,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          Recipes.Save recipe,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          ProductEdits.Save product,
      boolean sharedRecipe) {
    public Edit(
        UUID commandId,
        long expectedRevision,
        List<Change> changes,
        boolean confirmSharedRecipe,
        Recipes.Save recipe) {
      this(commandId, expectedRevision, changes, confirmSharedRecipe, recipe, null, false);
    }

    public Edit(
        UUID commandId, long expectedRevision, List<Change> changes, boolean confirmSharedRecipe) {
      this(commandId, expectedRevision, changes, confirmSharedRecipe, null, null, false);
    }
  }
}
