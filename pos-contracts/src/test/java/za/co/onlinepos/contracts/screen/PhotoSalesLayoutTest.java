package za.co.onlinepos.contracts.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.onlinepos.contracts.screen.SalesScreens.Access;
import za.co.onlinepos.contracts.screen.SalesScreens.Canvas;
import za.co.onlinepos.contracts.screen.SalesScreens.Document;
import za.co.onlinepos.contracts.screen.SalesScreens.Page;
import za.co.onlinepos.contracts.screen.SalesScreens.Purpose;
import za.co.onlinepos.contracts.screen.SalesScreens.Rect;
import za.co.onlinepos.contracts.screen.SalesScreens.Style;
import za.co.onlinepos.contracts.screen.SalesScreens.Type;
import za.co.onlinepos.contracts.screen.SalesScreens.Widget;

class PhotoSalesLayoutTest {
  @Test
  void conversionPreservesEveryBindingAndMakesOverflowReachable() {
    var widgets = new ArrayList<Widget>();
    var prices = new HashSet<UUID>();
    for (int i = 0; i < 101; i++) {
      var price = UUID.randomUUID();
      prices.add(price);
      widgets.add(
          ScreenDocuments.widget(
              Type.PRODUCT_BUTTON,
              "A long product name " + i,
              new Rect(0, 0, 90, 60, 1),
              Map.of("priceId", price.toString())));
    }
    for (int i = 0; i < 20; i++)
      widgets.add(
          ScreenDocuments.widget(
              Type.FUNCTION_BUTTON,
              "Custom action " + i,
              new Rect(0, 0, 90, 60, 1),
              Map.of("action", "EDIT_LINE")));
    var group =
        ScreenDocuments.widget(Type.GROUP, "Group", new Rect(20, 100, 200, 200, 0), Map.of());
    widgets.add(group);
    var grouped =
        ScreenDocuments.widget(Type.TEXT, "Grouped note", new Rect(30, 120, 100, 60, 1), Map.of());
    widgets.add(
        new Widget(
            grouped.id(),
            grouped.type(),
            grouped.rect(),
            grouped.style(),
            grouped.label(),
            grouped.binding(),
            grouped.options(),
            Access.OWNER,
            group.id()));
    var custom = new Style("#123456", "#ffffff", 21, "#abcdef", 2, "Verdana", null);
    var first = widgets.getFirst();
    widgets.set(
        0,
        new Widget(
            first.id(),
            first.type(),
            first.rect(),
            custom,
            first.label(),
            first.binding(),
            first.options(),
            Access.MANAGER,
            null));
    var pageId = UUID.randomUUID();
    var oldNav =
        ScreenDocuments.widget(
            Type.NAV_BUTTON,
            "Existing navigation",
            new Rect(0, 0, 100, 60, 1),
            Map.of("targetPageId", pageId.toString()));
    widgets.add(oldNav);
    var source =
        new Document(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new Canvas(1200, 720, 8),
            List.of(new Page(pageId, "Products", Purpose.SALES, List.copyOf(widgets))),
            List.of(),
            List.of());
    var converted = PhotoSalesLayout.apply(source);
    ScreenValidation.validate(
        converted, source.storeId(), prices, ScreenValidation.Limits.standard());
    assertEquals(source.screenId(), converted.screenId());
    assertEquals(pageId, converted.pages().getFirst().id());
    var byId = new HashMap<UUID, Widget>();
    converted.pages().forEach(p -> p.widgets().forEach(w -> assertNull(byId.put(w.id(), w))));
    for (var original : widgets) {
      var found = byId.get(original.id());
      assertNotNull(found);
      assertEquals(original.binding(), found.binding());
      assertEquals(original.options(), found.options());
      assertEquals(original.access(), found.access());
      assertEquals(original.groupId(), found.groupId());
    }
    assertEquals(custom, byId.get(first.id()).style());
    assertEquals(101, byId.values().stream().filter(w -> w.type() == Type.PRODUCT_BUTTON).count());
    var reached = new HashSet<UUID>();
    reach(converted, pageId, reached);
    assertEquals(converted.pages().size(), reached.size());
    assertEquals(converted, PhotoSalesLayout.apply(converted));
  }

  @Test
  void splitTemplateCopyRetainsSelectiveUpdatesOnItsContinuationPage() {
    var store = UUID.randomUUID();
    var price = UUID.randomUUID();
    var widgets = new ArrayList<Widget>();
    for (int i = 0; i < 50; i++)
      widgets.add(
          ScreenDocuments.widget(
              Type.PRODUCT_BUTTON,
              "Product " + i,
              new Rect(0, 0, 90, 60, 1),
              Map.of("priceId", price.toString())));
    var sourcePage =
        new Page(
            UUID.randomUUID(), "Long category ".repeat(11), Purpose.SALES, List.copyOf(widgets));
    var templateDoc =
        new Document(
            1,
            UUID.randomUUID(),
            store,
            new Canvas(1200, 720, 8),
            List.of(sourcePage),
            List.of(),
            List.of());
    var target =
        new Document(
            1,
            UUID.randomUUID(),
            store,
            new Canvas(1200, 720, 8),
            List.of(new Page(UUID.randomUUID(), "Existing", Purpose.SALES, List.of())),
            List.of(),
            List.of());
    var copy =
        ScreenDocuments.applyTemplate(
            target,
            new SalesScreens.Revision(
                templateDoc.screenId(), 1, "Template", true, "", templateDoc));
    var converted = PhotoSalesLayout.apply(copy);
    var original = widgets.getLast();
    UUID local = copy.templateOrigins().getFirst().widgetIds().get(original.id());
    var localPage =
        converted.pages().stream()
            .filter(p -> p.widgets().stream().anyMatch(w -> w.id().equals(local)))
            .findFirst()
            .orElseThrow();
    var origin =
        converted.templateOrigins().stream()
            .filter(o -> o.pageId().equals(localPage.id()))
            .findFirst()
            .orElseThrow();
    var revisedWidgets = new ArrayList<>(widgets);
    revisedWidgets.set(
        49,
        new Widget(
            original.id(),
            original.type(),
            original.rect(),
            original.style(),
            "Updated product",
            original.binding(),
            original.options(),
            original.access(),
            original.groupId()));
    var revised =
        ScreenDocuments.page(
            templateDoc,
            new Page(
                sourcePage.id(),
                sourcePage.name(),
                sourcePage.purpose(),
                List.copyOf(revisedWidgets)));
    var merged =
        ScreenDocuments.updateTemplate(
                converted,
                origin,
                new SalesScreens.Revision(templateDoc.screenId(), 2, "Template", true, "", revised),
                Set.of(local + ".label"))
            .document();
    assertEquals(
        "Updated product",
        merged.pages().stream()
            .flatMap(p -> p.widgets().stream())
            .filter(w -> w.id().equals(local))
            .findFirst()
            .orElseThrow()
            .label());
    ScreenValidation.validate(merged, store, Set.of(price), ScreenValidation.Limits.standard());
  }

  private static void reach(Document doc, UUID id, Set<UUID> reached) {
    if (!reached.add(id)) return;
    doc.pages().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow().widgets().stream()
        .filter(w -> w.type() == Type.NAV_BUTTON)
        .forEach(w -> reach(doc, UUID.fromString(w.binding().get("targetPageId")), reached));
  }

  @Test
  void specialisedPagesKeepTheirPurposeAndControlsWithinBounds() {
    var widget =
        ScreenDocuments.widget(
            Type.PAYMENT_PANEL, "Checkout", new Rect(0, 0, 800, 600, 1), Map.of());
    var source =
        new Document(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new Canvas(800, 600, 8),
            List.of(new Page(UUID.randomUUID(), "Payment", Purpose.PAYMENT, List.of(widget))),
            List.of(),
            List.of());
    var result = PhotoSalesLayout.apply(source);
    assertEquals(Purpose.PAYMENT, result.pages().getFirst().purpose());
    assertEquals(widget.id(), result.pages().getFirst().widgets().getFirst().id());
    ScreenValidation.validate(
        result, source.storeId(), Set.of(), ScreenValidation.Limits.standard());
  }
}
