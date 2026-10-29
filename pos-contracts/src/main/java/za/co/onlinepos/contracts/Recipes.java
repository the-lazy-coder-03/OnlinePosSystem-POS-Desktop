package za.co.onlinepos.contracts;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Source product identities; ingredient keys use existing ingredient:/burger:/salad: identities.
 */
public final class Recipes {
  private Recipes() {}

  public record Product(String kind, long id) {
    public Product {
      if (!List.of("pizza", "menu_item").contains(kind) || id < 1)
        throw new IllegalArgumentException("Invalid recipe product");
    }
  }

  public record Save(
      UUID commandId,
      long expectedRevision,
      Product product,
      String family,
      List<String> selected,
      Map<String, Map<String, Object>> properties,
      boolean confirmSharedRecipe,
      List<String> restoreInherited) {
    public Save(
        UUID commandId,
        long expectedRevision,
        Product product,
        String family,
        List<String> selected,
        Map<String, Map<String, Object>> properties,
        boolean confirmSharedRecipe) {
      this(
          commandId,
          expectedRevision,
          product,
          family,
          selected,
          properties,
          confirmSharedRecipe,
          List.of());
    }
  }
}
