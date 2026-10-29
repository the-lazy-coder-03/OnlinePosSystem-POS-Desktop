package za.co.onlinepos.contracts;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One product draft; negative source IDs are local references resolved only during commit. */
public final class ProductEdits {
  private ProductEdits() {}

  public record Recipe(
      String family,
      List<String> selected,
      Map<String, Map<String, Object>> properties,
      List<String> restoreInherited) {}

  public record Variant(
      List<Long> key, ItemCustomization.ItemRule item, ItemCustomization.Combo combo) {}

  public record Save(
      UUID commandId,
      long expectedRevision,
      String expectedProductRevision,
      String kind,
      long id,
      List<CatalogueManagement.Change> changes,
      Recipe recipe,
      List<Variant> variants) {}
}
