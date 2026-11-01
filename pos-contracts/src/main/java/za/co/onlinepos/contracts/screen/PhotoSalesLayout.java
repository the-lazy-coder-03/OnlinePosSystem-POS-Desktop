package za.co.onlinepos.contracts.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import za.co.onlinepos.contracts.screen.SalesScreens.Action;
import za.co.onlinepos.contracts.screen.SalesScreens.Canvas;
import za.co.onlinepos.contracts.screen.SalesScreens.Document;
import za.co.onlinepos.contracts.screen.SalesScreens.Origin;
import za.co.onlinepos.contracts.screen.SalesScreens.Page;
import za.co.onlinepos.contracts.screen.SalesScreens.Purpose;
import za.co.onlinepos.contracts.screen.SalesScreens.Rect;
import za.co.onlinepos.contracts.screen.SalesScreens.Style;
import za.co.onlinepos.contracts.screen.SalesScreens.Type;
import za.co.onlinepos.contracts.screen.SalesScreens.Widget;

/** Lossless, staged layout conversion. Publication remains an authenticated server operation. */
public final class PhotoSalesLayout {
  public static final String BACKGROUND = "#181d39";
  private static final String[] COLORS = {"#174bda", "#56cf26", "#f0d634", "#e56320", "#b92cbd"};

  private PhotoSalesLayout() {}

  public static Document apply(Document source) {
    if (source.canvas().width() == 1200
        && source.canvas().height() == 800
        && source.pages().stream()
            .allMatch(
                p ->
                    p.widgets().stream()
                        .anyMatch(w -> "navigation".equals(w.options().get("photoRole")))))
      return source;
    var pages = new ArrayList<Page>();
    for (var page : source.pages()) {
      var original = page.widgets();
      if (page.purpose() != Purpose.SALES) {
        pages.add(
            new Page(
                page.id(),
                page.name(),
                page.purpose(),
                original.stream().map(w -> scaled(w, source.canvas(), 80)).toList()));
        continue;
      }
      // Preserve group memberships and internal geometry together on dedicated continuation pages.
      var grouped = new HashSet<UUID>();
      original.stream()
          .filter(w -> w.type() == Type.GROUP || w.groupId() != null)
          .forEach(w -> grouped.add(w.id()));
      var products = new ArrayList<Widget>();
      var actions = new ArrayList<Widget>();
      var extra = new ArrayList<Widget>();
      var fixed = new HashMap<Type, Widget>();
      for (var w : original) {
        if (grouped.contains(w.id())) continue;
        switch (w.type()) {
          case PRODUCT_BUTTON -> products.add(w);
          case FUNCTION_BUTTON -> actions.add(w);
          case ORDER_LIST, TOTALS, KEYPAD, CUSTOMER -> {
            if (fixed.putIfAbsent(w.type(), w) != null) extra.add(w);
          }
          default -> extra.add(w);
        }
      }
      if (actions.isEmpty()) {
        Action[] commands = {
          Action.EDIT_LINE,
          Action.REMOVE_LINE,
          Action.CLEAR_DRAFT,
          Action.OPEN_DRAFT,
          Action.SAVE_DRAFT,
          Action.SELECT_CUSTOMER
        };
        String[] labels = {
          "Customise line", "Remove line", "Clear draft", "Open draft", "Save draft", "Customer"
        };
        for (int i = 0; i < commands.length; i++)
          actions.add(
              generated(
                  Type.FUNCTION_BUTTON,
                  labels[i],
                  new Rect(0, 0, 88, 72, 1),
                  Map.of("action", commands[i].name()),
                  "scaffold"));
      }
      // Nonstandard controls retain their identity, bindings and access on continuation pages.
      int count = Math.max(1, Math.max((products.size() + 44) / 45, (actions.size() + 8) / 9));
      for (int n = 0; n < count; n++) {
        var widgets = new ArrayList<Widget>();
        for (int i = n * 45; i < Math.min(products.size(), (n + 1) * 45); i++) {
          int at = i % 45;
          widgets.add(
              arrange(products.get(i), new Rect(10 + at % 5 * 94, 88 + at / 5 * 76, 88, 70, 1)));
        }
        for (int i = n * 9; i < Math.min(actions.size(), (n + 1) * 9); i++) {
          int at = i % 9;
          widgets.add(
              arrange(actions.get(i), new Rect(908 + at % 3 * 94, 88 + at / 3 * 78, 88, 72, 1)));
        }
        scaffold(widgets, n == 0 ? fixed : Map.of());
        pages.add(
            new Page(
                n == 0 ? page.id() : UUID.randomUUID(),
                pageName(page.name(), n == 0 ? "" : " · " + (n + 1)),
                Purpose.SALES,
                List.copyOf(widgets)));
      }
      for (int offset = 0; offset < extra.size(); offset += 30) {
        var widgets = new ArrayList<Widget>();
        for (int i = offset; i < Math.min(extra.size(), offset + 30); i++) {
          int at = i - offset;
          widgets.add(
              arrange(extra.get(i), new Rect(10 + at % 5 * 238, 88 + at / 5 * 116, 228, 106, 1)));
        }
        pages.add(
            new Page(
                UUID.randomUUID(),
                pageName(page.name(), " · More controls " + (offset / 30 + 1)),
                Purpose.OTHER,
                List.copyOf(widgets)));
      }
      if (!grouped.isEmpty())
        pages.add(
            new Page(
                UUID.randomUUID(),
                pageName(page.name(), " · Grouped controls"),
                Purpose.OTHER,
                original.stream()
                    .filter(w -> grouped.contains(w.id()))
                    .map(w -> scaled(w, source.canvas(), 80))
                    .toList()));
    }
    var completed = new ArrayList<Page>();
    for (int i = 0; i < pages.size(); i++) {
      var page = pages.get(i);
      var widgets = new ArrayList<>(page.widgets());
      int first = i / 8 * 8;
      widgets.add(
          nav(
              "◀",
              pages.get((i - 1 + pages.size()) % pages.size()).id(),
              new Rect(10, 10, 56, 64, 10)));
      for (int n = first; n < Math.min(first + 8, pages.size()); n++)
        widgets.add(
            nav(
                pages.get(n).name(),
                pages.get(n).id(),
                new Rect(72 + (n - first) * 132, 10, 126, 64, 10)));
      widgets.add(nav("▶", pages.get((i + 1) % pages.size()).id(), new Rect(1134, 10, 56, 64, 10)));
      completed.add(new Page(page.id(), page.name(), page.purpose(), List.copyOf(widgets)));
    }
    // A template copy may now span continuation pages; keep its merge baseline on each part.
    var origins = new ArrayList<Origin>();
    for (var origin : source.templateOrigins()) {
      for (var page : completed) {
        if (page.id().equals(origin.pageId())
            || page.widgets().stream().anyMatch(w -> origin.widgetIds().containsValue(w.id()))) {
          origins.add(
              new Origin(
                  origin.templateId(),
                  origin.revision(),
                  page.id(),
                  origin.widgetIds(),
                  origin.base(),
                  origin.sourcePageId(),
                  origin.referenceIds(),
                  origin.baseWorkflows()));
        }
      }
    }
    return new Document(
        source.schemaVersion(),
        source.screenId(),
        source.storeId(),
        new Canvas(1200, 800, 8),
        List.copyOf(completed),
        source.workflows(),
        List.copyOf(origins));
  }

  private static String pageName(String base, String suffix) {
    return base.substring(0, Math.min(base.length(), 160 - suffix.length())) + suffix;
  }

  private static Widget nav(String label, UUID page, Rect rect) {
    return generated(
        Type.NAV_BUTTON, label, rect, Map.of("targetPageId", page.toString()), "navigation");
  }

  private static void scaffold(List<Widget> widgets, Map<Type, Widget> existing) {
    add(widgets, existing, Type.TOTALS, "Subtotal", new Rect(488, 132, 408, 64, 1));
    add(widgets, existing, Type.ORDER_LIST, "Draft order", new Rect(488, 204, 408, 526, 1));
    add(widgets, existing, Type.CUSTOMER, "Choose customer", new Rect(488, 742, 408, 48, 1));
    add(widgets, existing, Type.KEYPAD, "Selected line quantity", new Rect(908, 338, 282, 452, 1));
  }

  private static void add(
      List<Widget> widgets, Map<Type, Widget> existing, Type type, String label, Rect rect) {
    var old = existing.get(type);
    widgets.add(
        old == null ? generated(type, label, rect, Map.of(), "scaffold") : arrange(old, rect));
  }

  private static Widget generated(
      Type type, String label, Rect rect, Map<String, String> binding, String role) {
    var w = ScreenDocuments.widget(type, label, rect, binding);
    return new Widget(
        w.id(),
        w.type(),
        rect,
        theme(w),
        label,
        binding,
        Map.of("photoRole", role),
        w.access(),
        null);
  }

  private static Widget scaled(Widget w, Canvas old, double top) {
    var r = w.rect();
    return arrange(
        w,
        new Rect(
            r.x() * 1200 / old.width(),
            top + r.y() * (800 - top) / old.height(),
            r.w() * 1200 / old.width(),
            r.h() * (800 - top) / old.height(),
            r.z()));
  }

  private static Widget arrange(Widget w, Rect rect) {
    return new Widget(
        w.id(),
        w.type(),
        rect,
        theme(w),
        w.label(),
        w.binding(),
        w.options(),
        w.access(),
        w.groupId());
  }

  private static Style theme(Widget w) {
    var old = w.style();
    boolean standard =
        old.equals(Style.standard())
            || old.equals(new Style("#ffffff", "#18364b", 18, "#d8e1eb", 1, "Segoe UI", null))
            || old.equals(new Style("#18364b", "#ffffff", 22, "#d8e1eb", 1, "Segoe UI", null));
    if (!standard) return old;
    String bg =
        switch (w.type()) {
          case PRODUCT_BUTTON ->
              COLORS[Math.floorMod(w.binding().toString().hashCode(), COLORS.length)];
          case NAV_BUTTON -> COLORS[Math.floorMod(w.label().hashCode(), COLORS.length)];
          case FUNCTION_BUTTON ->
              switch (w.binding().getOrDefault("action", "")) {
                case "SAVE_DRAFT" -> "#f0d634";
                case "OPEN_DRAFT", "SELECT_CUSTOMER" -> "#6653bb";
                default -> "#bd262d";
              };
          case TOTALS -> "#087dcc";
          case KEYPAD -> "#263dba";
          default -> BACKGROUND;
        };
    String fg =
        bg.equals("#56cf26") || bg.equals("#f0d634") || bg.equals("#e56320")
            ? "#102018"
            : "#ffffff";
    return new Style(
        bg,
        fg,
        w.type() == Type.TOTALS ? 28 : w.type() == Type.FUNCTION_BUTTON ? 14 : 15,
        "#58618b",
        1,
        "Arial",
        old.assetHash());
  }
}
