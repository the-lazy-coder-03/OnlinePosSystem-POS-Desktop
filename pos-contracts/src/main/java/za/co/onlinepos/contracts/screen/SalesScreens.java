package za.co.onlinepos.contracts.screen;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Versioned, declarative configuration only. No document can confer a business permission. */
public final class SalesScreens {
  private SalesScreens() {}

  public enum Type {
    NAV_BUTTON,
    PRODUCT_BUTTON,
    FUNCTION_BUTTON,
    ORDER_LIST,
    KEYPAD,
    TEXT,
    IMAGE,
    CUSTOMER,
    TOTALS,
    PAYMENT_PANEL,
    GROUP
  }

  public enum Access {
    CASHIER,
    MANAGER,
    OWNER
  }

  public enum Purpose {
    SALES,
    PAYMENT,
    OTHER
  }

  public enum Action {
    OPEN_DRAFT,
    EDIT_LINE,
    SET_QUANTITY,
    REMOVE_LINE,
    CLEAR_DRAFT,
    SAVE_DRAFT,
    NEW_DRAFT,
    SELECT_CUSTOMER,
    HOLD,
    RESUME,
    VOID,
    CANCEL_SALE,
    DISCOUNT_LINE,
    DISCOUNT_ORDER,
    CASH_PAYMENT,
    CARD_PAYMENT,
    PRINT,
    REFUND
  }

  public enum NodeType {
    ACTION,
    PROMPT,
    DECISION,
    END
  }

  public record WorkflowRequest(UUID screenId, long revision, UUID workflowId) {}

  public record Asset(UUID commandId, String hash, String mediaType, byte[] content) {}

  public record Bundle(Document document, List<Asset> assets, String name, boolean template) {
    public Bundle(Document document, List<Asset> assets) {
      this(document, assets, "Imported screen", false);
    }

    public Bundle {
      if (name == null || name.isBlank()) name = "Imported screen";
    }
  }

  public record Canvas(double width, double height, int snapSize) {}

  public record Rect(double x, double y, double w, double h, int z) {}

  public record Style(
      String background,
      String foreground,
      double fontSize,
      String border,
      double borderWidth,
      String fontFamily,
      String assetHash) {
    public static Style standard() {
      return new Style("#087f7a", "#ffffff", 18, "#d8e1eb", 1, "Segoe UI", null);
    }
  }

  public record Widget(
      UUID id,
      Type type,
      Rect rect,
      Style style,
      String label,
      Map<String, String> binding,
      Map<String, String> options,
      Access access,
      UUID groupId) {}

  public record Page(UUID id, String name, Purpose purpose, List<Widget> widgets) {}

  public record Step(
      UUID id,
      NodeType type,
      Action action,
      String condition,
      String prompt,
      UUID next,
      UUID otherwise) {}

  public record Workflow(UUID id, String name, UUID entry, List<Step> steps) {}

  public record Origin(
      UUID templateId,
      long revision,
      UUID pageId,
      Map<UUID, UUID> widgetIds,
      List<Widget> base,
      UUID sourcePageId,
      Map<UUID, UUID> referenceIds,
      List<Workflow> baseWorkflows) {
    public Origin {
      if (baseWorkflows == null) baseWorkflows = List.of();
    }
  }

  public record Document(
      int schemaVersion,
      UUID screenId,
      UUID storeId,
      Canvas canvas,
      List<Page> pages,
      List<Workflow> workflows,
      List<Origin> templateOrigins) {}

  public record Save(
      UUID commandId, long expectedRevision, String name, boolean template, Document document) {}

  public record Publish(UUID commandId, UUID screenId, long revision, long expectedSequence) {}

  public record Revision(
      UUID screenId,
      long revision,
      String name,
      boolean template,
      String hash,
      Document document) {}

  public record Publication(
      UUID eventId,
      UUID storeId,
      long sequence,
      UUID screenId,
      long revision,
      String hash,
      Document document) {}

  public record Capabilities(Set<Action> available, Map<Action, String> unavailable) {}

  public record Conflict(UUID widgetId, String field, String base, String local, String incoming) {}

  public record Merge(Document document, List<Conflict> conflicts) {}

  public static Capabilities draftCapabilities() {
    var available =
        Set.of(
            Action.OPEN_DRAFT,
            Action.EDIT_LINE,
            Action.SET_QUANTITY,
            Action.REMOVE_LINE,
            Action.CLEAR_DRAFT,
            Action.SAVE_DRAFT,
            Action.NEW_DRAFT,
            Action.SELECT_CUSTOMER);
    var missing = new java.util.EnumMap<Action, String>(Action.class);
    for (var action : Action.values())
      if (!available.contains(action))
        missing.put(
            action, "No authoritative backend for this operation. It is hidden from the cashier.");
    return new Capabilities(available, Map.copyOf(missing));
  }
}
