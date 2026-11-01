package za.co.onlinepos.contracts.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import za.co.onlinepos.contracts.screen.SalesScreens.Action;
import za.co.onlinepos.contracts.screen.SalesScreens.Document;
import za.co.onlinepos.contracts.screen.SalesScreens.NodeType;
import za.co.onlinepos.contracts.screen.SalesScreens.Step;
import za.co.onlinepos.contracts.screen.SalesScreens.Type;
import za.co.onlinepos.contracts.screen.SalesScreens.Widget;

/**
 * Shared by server persistence and renderer. Limits are configurable, not authoring page quotas.
 */
public final class ScreenValidation {
  private ScreenValidation() {}

  public record Limits(int pages, int widgets, int nodes, int bytes) {
    public static Limits standard() {
      return new Limits(1000, 10000, 256, 4 * 1024 * 1024);
    }
  }

  public static List<String> validate(Document doc, UUID store, Set<UUID> prices, Limits limits) {
    var warnings = new ArrayList<String>();
    require(
        doc != null && doc.schemaVersion() == 1 && store.equals(doc.storeId()),
        "Unsupported schema or wrong store");
    id(doc.screenId());
    var c = doc.canvas();
    require(
        c != null
            && finite(c.width(), 320, 16384)
            && finite(c.height(), 240, 16384)
            && c.snapSize() >= 1
            && c.snapSize() <= 128,
        "Invalid canvas");
    require(
        doc.pages() != null && !doc.pages().isEmpty() && doc.pages().size() <= limits.pages(),
        "Invalid page count");
    require(
        doc.workflows() != null && doc.templateOrigins() != null, "Document collections required");
    var ids = new HashSet<UUID>();
    var pages = new HashSet<UUID>();
    var widgets = new HashMap<UUID, Widget>();
    var widgetPages = new HashMap<UUID, UUID>();
    for (var p : doc.pages()) {
      require(p != null, "Missing page");
      unique(ids, p.id());
      pages.add(p.id());
      text(p.name(), 160);
      require(p.purpose() != null && p.widgets() != null, "Invalid page");
      for (var w : p.widgets()) {
        require(w != null, "Missing widget");
        unique(ids, w.id());
        widgetPages.put(w.id(), p.id());
        widgets.put(w.id(), w);
      }
    }
    require(widgets.size() <= limits.widgets(), "Too many widgets");
    var workflows = new HashSet<UUID>();
    for (var flow : doc.workflows()) {
      require(flow != null, "Missing workflow");
      unique(ids, flow.id());
      workflows.add(flow.id());
      text(flow.name(), 160);
      require(
          flow.steps() != null && flow.steps().size() <= limits.nodes(), "Invalid workflow size");
      var steps = new HashMap<UUID, Step>();
      for (var n : flow.steps()) {
        require(n != null, "Missing workflow node");
        unique(ids, n.id());
        require(n.type() != null, "Invalid workflow node");
        steps.put(n.id(), n);
      }
      require(steps.containsKey(flow.entry()), "Workflow entry is missing");
      for (var n : flow.steps()) {
        require(n.next() == null || steps.containsKey(n.next()), "Missing workflow destination");
        require(
            n.otherwise() == null || steps.containsKey(n.otherwise()),
            "Missing decision destination");
        if (n.type() == NodeType.ACTION)
          require(n.action() != null && n.next() != null, "Action and next step required");
        if (n.type() == NodeType.PROMPT) {
          text(n.prompt(), 300);
          require(n.next() != null, "Prompt destination required");
        }
        if (n.type() == NodeType.DECISION)
          require(
              n.condition() != null
                  && Set.of("HAS_SELECTION", "CART_EMPTY", "CUSTOMER_SELECTED")
                      .contains(n.condition())
                  && n.next() != null
                  && n.otherwise() != null,
              "Unsupported decision");
        if (n.type() == NodeType.END)
          require(
              n.next() == null && n.otherwise() == null, "End node cannot execute another step");
      }
      visit(flow.entry(), steps, new HashSet<>(), new HashSet<>(), 0, limits.nodes());
      var reachable = new HashSet<UUID>();
      reach(flow.entry(), steps, reachable);
      require(reachable.size() == steps.size(), "Unreachable workflow nodes");
    }
    for (var w : widgets.values()) {
      require(
          w.type() != null
              && w.rect() != null
              && w.style() != null
              && w.access() != null
              && w.binding() != null
              && w.options() != null,
          "Incomplete widget");
      var r = w.rect();
      require(
          finite(r.x(), 0, c.width())
              && finite(r.y(), 0, c.height())
              && finite(r.w(), 4, c.width())
              && finite(r.h(), 4, c.height())
              && r.x() + r.w() <= c.width()
              && r.y() + r.h() <= c.height()
              && Math.abs((long) r.z()) <= 100000,
          "Widget is outside canvas");
      if (r.w() < 44 || r.h() < 44) warnings.add("Small touch target: " + w.id());
      var s = w.style();
      color(s.background());
      color(s.foreground());
      color(s.border());
      require(
          finite(s.fontSize(), 8, 120) && finite(s.borderWidth(), 0, 20), "Invalid widget style");
      require(
          s.fontFamily() != null
              && Set.of("Segoe UI", "Arial", "Verdana", "System").contains(s.fontFamily()),
          "Unsupported font");
      if (s.assetHash() != null)
        require(s.assetHash().matches("[a-f0-9]{64}"), "Invalid asset hash");
      require(w.label() != null && w.label().length() <= 200, "Invalid widget label");
      require(
          Set.of("priceId", "targetPageId", "action", "workflowId", "assetHash")
              .containsAll(w.binding().keySet()),
          "Unknown binding fields");
      require(
          Set.of("columns", "text", "alignment", "photoRole").containsAll(w.options().keySet()),
          "Unknown widget options");
      if (w.options().containsKey("photoRole"))
        require(
            Set.of("navigation", "scaffold").contains(w.options().get("photoRole")),
            "Invalid photo layout role");
      for (var v : w.options().values())
        require(v != null && v.length() <= 2000, "Oversized widget option");
      if (w.options().containsKey("alignment"))
        require(
            Set.of("LEFT", "CENTER", "RIGHT").contains(w.options().get("alignment")),
            "Invalid alignment");
      if (w.type() == Type.PRODUCT_BUTTON)
        require(
            prices.contains(uuid(w.binding().get("priceId"))),
            "Unknown or wrong-store product variant");
      if (w.type() == Type.NAV_BUTTON)
        require(pages.contains(uuid(w.binding().get("targetPageId"))), "Unknown navigation page");
      if (w.type() == Type.FUNCTION_BUTTON) {
        require(
            w.binding().containsKey("action") ^ w.binding().containsKey("workflowId"),
            "Choose one action or workflow");
        if (w.binding().containsKey("action"))
          try {
            Action.valueOf(w.binding().get("action"));
          } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported action");
          }
        else require(workflows.contains(uuid(w.binding().get("workflowId"))), "Missing workflow");
      }
      if (w.type() == Type.ORDER_LIST && w.options().containsKey("columns"))
        for (String col : w.options().get("columns").split(","))
          require(
              Set.of("quantity", "description", "unitPrice", "lineTotal", "notes").contains(col),
              "Invalid order column");
      if (w.groupId() != null)
        require(
            widgets.containsKey(w.groupId())
                && widgets.get(w.groupId()).type() == Type.GROUP
                && widgetPages.get(w.id()).equals(widgetPages.get(w.groupId()))
                && !w.groupId().equals(w.id()),
            "Missing group");
      var seen = new HashSet<UUID>();
      var current = w;
      while (current.groupId() != null) {
        require(seen.add(current.id()), "Cyclic widget group");
        current = widgets.get(current.groupId());
        require(current != null, "Missing group");
      }
    }
    for (var origin : doc.templateOrigins()) {
      require(origin != null, "Missing template origin");
      id(origin.sourcePageId());
      require(origin.referenceIds() != null, "Missing template reference mapping");
      origin
          .referenceIds()
          .forEach(
              (a, b) -> {
                id(a);
                id(b);
              });
      id(origin.templateId());
      require(
          origin.revision() > 0
              && pages.contains(origin.pageId())
              && origin.widgetIds() != null
              && origin.base() != null,
          "Invalid template origin");
      origin
          .widgetIds()
          .forEach(
              (a, b) -> {
                id(a);
                id(b);
              });
      for (var widget : origin.base())
        require(
            widget != null
                && widget.id() != null
                && widget.type() != null
                && widget.rect() != null
                && widget.style() != null
                && widget.binding() != null
                && widget.options() != null,
            "Incomplete template baseline");
    }
    return List.copyOf(warnings);
  }

  private static void reach(UUID id, Map<UUID, Step> steps, Set<UUID> seen) {
    if (id == null || !seen.add(id)) return;
    var s = steps.get(id);
    reach(s.next(), steps, seen);
    reach(s.otherwise(), steps, seen);
  }

  private static void visit(
      UUID id, Map<UUID, Step> steps, Set<UUID> active, Set<UUID> done, int depth, int max) {
    if (id == null || done.contains(id)) return;
    require(depth <= max && active.add(id), "Cyclic workflow");
    var s = steps.get(id);
    visit(s.next(), steps, active, done, depth + 1, max);
    visit(s.otherwise(), steps, active, done, depth + 1, max);
    active.remove(id);
    done.add(id);
  }

  private static boolean finite(double v, double min, double max) {
    return Double.isFinite(v) && v >= min && v <= max;
  }

  private static void color(String color) {
    require(color != null && color.matches("#[a-fA-F0-9]{6}"), "Colors must be hexadecimal");
  }

  private static void text(String s, int max) {
    require(
        s != null
            && !s.isBlank()
            && s.length() <= max
            && s.codePoints().noneMatch(Character::isISOControl),
        "Invalid name or prompt");
  }

  public static UUID uuid(String s) {
    try {
      UUID id = UUID.fromString(s);
      id(id);
      return id;
    } catch (Exception e) {
      throw new IllegalArgumentException("Valid UUID required");
    }
  }

  private static void id(UUID id) {
    require(id != null && !id.equals(new UUID(0, 0)), "Nonzero UUID required");
  }

  private static void unique(Set<UUID> ids, UUID id) {
    id(id);
    require(ids.add(id), "Duplicate identifier");
  }

  private static void require(boolean good, String message) {
    if (!good) throw new IllegalArgumentException(message);
  }
}
