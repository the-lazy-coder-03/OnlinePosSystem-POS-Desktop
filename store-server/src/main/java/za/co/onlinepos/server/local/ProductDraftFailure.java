package za.co.onlinepos.server.local;

/** Safe validation feedback for a section of an atomic product draft. */
public final class ProductDraftFailure extends IllegalArgumentException {
  private final String section;
  private final String field;

  public ProductDraftFailure(String message) {
    super(message);
    String text = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
    section =
        text.contains("recipe")
                || text.contains("ingredient")
                || text.contains("protein")
                || text.contains("component")
            ? "Ingredients / Recipe"
            : text.contains("combo") || text.contains("slot")
                ? "Combo Configuration"
                : text.contains("price") || text.contains("amount")
                    ? "Pricing"
                    : text.contains("modifier")
                            || text.contains("option")
                            || text.contains("selection")
                        ? "Modifiers / Extras"
                        : "General Details";
    field = text.contains("name") ? "name" : "";
  }

  public String section() {
    return section;
  }

  public String field() {
    return field;
  }
}
