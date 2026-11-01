package za.co.onlinepos.contracts.screen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.screen.SalesScreens.Access;
import za.co.onlinepos.contracts.screen.SalesScreens.Canvas;
import za.co.onlinepos.contracts.screen.SalesScreens.Conflict;
import za.co.onlinepos.contracts.screen.SalesScreens.Document;
import za.co.onlinepos.contracts.screen.SalesScreens.Merge;
import za.co.onlinepos.contracts.screen.SalesScreens.Origin;
import za.co.onlinepos.contracts.screen.SalesScreens.Page;
import za.co.onlinepos.contracts.screen.SalesScreens.Purpose;
import za.co.onlinepos.contracts.screen.SalesScreens.Rect;
import za.co.onlinepos.contracts.screen.SalesScreens.Style;
import za.co.onlinepos.contracts.screen.SalesScreens.Type;
import za.co.onlinepos.contracts.screen.SalesScreens.Widget;

/** Pure document transformations; copies never share mutable widgets or identities. */
public final class ScreenDocuments {
  private ScreenDocuments() {}

  public static Widget widget(Type type, String label, Rect rect, Map<String, String> binding) {
    return new Widget(
        UUID.randomUUID(),
        type,
        rect,
        type == Type.ORDER_LIST || type == Type.KEYPAD || type == Type.TEXT
            ? new Style("#ffffff", "#18364b", 18, "#d8e1eb", 1, "Segoe UI", null)
            : type == Type.TOTALS
                ? new Style("#18364b", "#ffffff", 22, "#d8e1eb", 1, "Segoe UI", null)
                : Style.standard(),
        label,
        Map.copyOf(binding),
        Map.of(),
        Access.CASHIER,
        null);
  }

  public static Document starter(UUID store, ItemCustomization.Catalog catalog) {
    var pages = new ArrayList<Page>();
    var categories = new java.util.TreeMap<String, List<ItemCustomization.Product>>();
    for (var p : catalog.products().values())
      if (p.available())
        categories
            .computeIfAbsent(
                p.category() == null || p.category().isBlank()
                    ? "Products"
                    : catalog.categories().getOrDefault(p.category(), p.category()),
                k -> new ArrayList<>())
            .add(p);
    if (categories.isEmpty()) categories.put("Products", List.of());
    for (var entry : categories.entrySet()) {
      var widgets = new ArrayList<Widget>();
      entry.getValue().stream()
          .sorted(
              java.util.Comparator.comparing(ItemCustomization.Product::name)
                  .thenComparing(p -> p.priceId().toString()))
          .forEach(
              p ->
                  widgets.add(
                      widget(
                          Type.PRODUCT_BUTTON,
                          p.name(),
                          new Rect(0, 0, 90, 64, 1),
                          Map.of("priceId", p.priceId().toString()))));
      pages.add(new Page(UUID.randomUUID(), entry.getKey(), Purpose.SALES, List.copyOf(widgets)));
    }
    return PhotoSalesLayout.apply(
        new Document(
            1,
            UUID.randomUUID(),
            store,
            new Canvas(1200, 800, 8),
            List.copyOf(pages),
            List.of(),
            List.of()));
  }

  public static Document page(Document d, Page page) {
    return new Document(
        d.schemaVersion(),
        d.screenId(),
        d.storeId(),
        d.canvas(),
        d.pages().stream().map(p -> p.id().equals(page.id()) ? page : p).toList(),
        d.workflows(),
        d.templateOrigins());
  }

  public static Widget geometry(Widget w, Rect r) {
    return new Widget(
        w.id(),
        w.type(),
        r,
        w.style(),
        w.label(),
        w.binding(),
        w.options(),
        w.access(),
        w.groupId());
  }

  public static Document copy(Document source, UUID store) {
    var ids = new HashMap<UUID, UUID>();
    source
        .pages()
        .forEach(
            p -> {
              ids.put(p.id(), UUID.randomUUID());
              p.widgets().forEach(w -> ids.put(w.id(), UUID.randomUUID()));
            });
    source
        .workflows()
        .forEach(
            f -> {
              ids.put(f.id(), UUID.randomUUID());
              f.steps().forEach(n -> ids.put(n.id(), UUID.randomUUID()));
            });
    var pages =
        source.pages().stream()
            .map(
                p ->
                    new Page(
                        ids.get(p.id()),
                        p.name(),
                        p.purpose(),
                        p.widgets().stream().map(w -> remap(w, ids)).toList()))
            .toList();
    var flows =
        source.workflows().stream()
            .map(
                f ->
                    new SalesScreens.Workflow(
                        ids.get(f.id()),
                        f.name(),
                        ids.get(f.entry()),
                        f.steps().stream()
                            .map(
                                n ->
                                    new SalesScreens.Step(
                                        ids.get(n.id()),
                                        n.type(),
                                        n.action(),
                                        n.condition(),
                                        n.prompt(),
                                        ids.get(n.next()),
                                        ids.get(n.otherwise())))
                            .toList()))
            .toList();
    return new Document(1, UUID.randomUUID(), store, source.canvas(), pages, flows, List.of());
  }

  private static Widget remap(Widget w, Map<UUID, UUID> ids) {
    var b = new HashMap<>(w.binding());
    for (String key : List.of("targetPageId", "workflowId"))
      if (b.containsKey(key))
        b.put(
            key,
            java.util.Objects.requireNonNull(
                    ids.get(UUID.fromString(b.get(key))),
                    "Template reference needs explicit rebinding")
                .toString());
    return new Widget(
        ids.get(w.id()),
        w.type(),
        w.rect(),
        w.style(),
        w.label(),
        Map.copyOf(b),
        w.options(),
        w.access(),
        ids.get(w.groupId()));
  }

  public static Document applyTemplate(Document target, SalesScreens.Revision template) {
    if (!template.template() || !target.storeId().equals(template.document().storeId()))
      throw new IllegalArgumentException("A template from this store is required");
    var copied = copy(template.document(), target.storeId());
    var pages = new ArrayList<>(target.pages());
    pages.addAll(copied.pages());
    var flows = new ArrayList<>(target.workflows());
    flows.addAll(copied.workflows());
    var origins = new ArrayList<>(target.templateOrigins());
    var references = new HashMap<UUID, UUID>();
    for (int i = 0; i < copied.pages().size(); i++) {
      var source = template.document().pages().get(i);
      var local = copied.pages().get(i);
      references.put(source.id(), local.id());
      for (int j = 0; j < source.widgets().size(); j++)
        references.put(source.widgets().get(j).id(), local.widgets().get(j).id());
    }
    for (int i = 0; i < copied.workflows().size(); i++) {
      var original = template.document().workflows().get(i);
      var clone = copied.workflows().get(i);
      references.put(original.id(), clone.id());
      for (int n = 0; n < original.steps().size(); n++)
        references.put(original.steps().get(n).id(), clone.steps().get(n).id());
    }

    for (int i = 0; i < copied.pages().size(); i++) {
      var source = template.document().pages().get(i);
      var local = copied.pages().get(i);
      var map = new HashMap<UUID, UUID>();
      for (int j = 0; j < source.widgets().size(); j++)
        map.put(source.widgets().get(j).id(), local.widgets().get(j).id());
      origins.add(
          new Origin(
              template.screenId(),
              template.revision(),
              local.id(),
              Map.copyOf(map),
              source.widgets(),
              source.id(),
              Map.copyOf(references),
              template.document().workflows()));
    }
    return new Document(
        1,
        target.screenId(),
        target.storeId(),
        target.canvas(),
        List.copyOf(pages),
        List.copyOf(flows),
        List.copyOf(origins));
  }

  /**
   * Selective three-way field merge. Conflicts retain the local value until explicitly selected.
   */
  public static Merge updateTemplate(
      Document d, Origin origin, SalesScreens.Revision incoming, Set<String> accept) {
    if (!incoming.template()
        || !incoming.screenId().equals(origin.templateId())
        || incoming.revision() <= origin.revision())
      throw new IllegalArgumentException("Newer matching template required");
    var mapping = new HashMap<>(origin.widgetIds());
    var references = new HashMap<>(origin.referenceIds());
    for (var flow : incoming.document().workflows())
      if (!references.containsKey(flow.id())) {
        references.put(flow.id(), UUID.randomUUID());
        for (var node : flow.steps()) references.put(node.id(), UUID.randomUUID());
      }
    var reverse = new HashMap<UUID, UUID>();
    mapping.forEach((a, b) -> reverse.put(b, a));
    var base = new HashMap<UUID, Widget>();
    origin.base().forEach(w -> base.put(w.id(), w));
    var next = new HashMap<UUID, Widget>();
    incoming.document().pages().stream()
        .filter(p -> p.id().equals(origin.sourcePageId()))
        .forEach(p -> p.widgets().forEach(w -> next.put(w.id(), w)));
    var page =
        d.pages().stream().filter(p -> p.id().equals(origin.pageId())).findFirst().orElseThrow();
    var result = new ArrayList<Widget>();
    var changes = new ArrayList<Conflict>();
    var nextBase = new HashMap<>(base);
    for (var n : next.values())
      if (!base.containsKey(n.id()) && accept.contains(n.id() + ".added")) {
        UUID id = UUID.randomUUID();
        mapping.put(n.id(), id);
        references.put(n.id(), id);
      }
    for (var local : page.widgets()) {
      UUID source = reverse.get(local.id());
      var b = base.get(source);
      var n = next.get(source);
      if (b == null) {
        result.add(local);
        continue;
      }
      if (n == null) {
        changes.add(new Conflict(local.id(), "deleted", b.label(), local.label(), "deleted"));
        if (!accept.contains(local.id() + ".deleted")) result.add(local);
        else {
          nextBase.remove(source);
          mapping.remove(source);
        }
        continue;
      }
      var normalizedBase = remap(b, references);
      var normalizedNext = remap(n, references);
      var merged = mergeWidget(normalizedBase, local, normalizedNext, accept, changes);
      result.add(merged);
      // Advance only the explicitly applied fields in the template baseline.
      var sourceKeys = new java.util.HashSet<String>();
      for (String key : accept)
        if (key.startsWith(local.id() + "."))
          sourceKeys.add(source + key.substring(local.id().toString().length()));
      nextBase.put(source, mergeWidget(b, b, n, sourceKeys, new ArrayList<>()));
    }
    for (var n : next.values())
      if (!base.containsKey(n.id())) {
        changes.add(new Conflict(n.id(), "added", "absent", "absent", n.label()));
        if (accept.contains(n.id() + ".added")) {
          result.add(remap(n, references));
          nextBase.put(n.id(), n);
        }
      }
    // Workflow updates create a new independent graph and rebind this page only.
    var workflows = new ArrayList<>(d.workflows());
    var graphBaseline = new HashMap<UUID, SalesScreens.Workflow>();
    origin.baseWorkflows().forEach(f -> graphBaseline.put(f.id(), f));
    var incomingGraphs = new HashMap<UUID, SalesScreens.Workflow>();
    incoming.document().workflows().forEach(f -> incomingGraphs.put(f.id(), f));
    for (var entry : incomingGraphs.entrySet()) {
      UUID source = entry.getKey();
      var nextGraph = entry.getValue();
      var baseline = graphBaseline.get(source);
      if (Objects.equals(baseline, nextGraph)) continue;
      UUID localId = references.get(source);
      var local = workflows.stream().filter(f -> f.id().equals(localId)).findFirst().orElse(null);
      UUID reviewId = baseline == null || localId == null ? source : localId;
      String baseSummary = baseline == null ? "absent" : graphSummary(baseline),
          localSummary = local == null ? "absent" : graphSummary(local);
      changes.add(
          new Conflict(reviewId, "workflow", baseSummary, localSummary, graphSummary(nextGraph)));
      if (!accept.contains(reviewId + ".workflow")) continue;
      var graphIds = new HashMap<UUID, UUID>();
      graphIds.put(source, UUID.randomUUID());
      for (var node : nextGraph.steps()) graphIds.put(node.id(), UUID.randomUUID());
      var clone = remapFlow(nextGraph, graphIds);
      workflows.add(clone);
      references.putAll(graphIds);
      graphBaseline.put(source, nextGraph);
      if (localId != null)
        for (int i = 0; i < result.size(); i++) {
          var w = result.get(i);
          if (localId.toString().equals(w.binding().get("workflowId"))) {
            var binding = new HashMap<>(w.binding());
            binding.put("workflowId", clone.id().toString());
            result.set(
                i,
                new Widget(
                    w.id(),
                    w.type(),
                    w.rect(),
                    w.style(),
                    w.label(),
                    Map.copyOf(binding),
                    w.options(),
                    w.access(),
                    w.groupId()));
          }
        }
    }
    for (var original : origin.baseWorkflows())
      if (!incomingGraphs.containsKey(original.id())) {
        UUID local = references.get(original.id());
        changes.add(
            new Conflict(
                local == null ? original.id() : local,
                "workflowRemoved",
                graphSummary(original),
                "Independent graph retained",
                "Removed from template"));
        if (accept.contains((local == null ? original.id() : local) + ".workflowRemoved"))
          graphBaseline.remove(original.id());
      }
    var origins = new ArrayList<>(d.templateOrigins());
    int at = origins.indexOf(origin);
    boolean fullyApplied = nextBase.equals(next) && graphBaseline.equals(incomingGraphs);
    origins.set(
        at,
        new Origin(
            origin.templateId(),
            fullyApplied ? incoming.revision() : origin.revision(),
            origin.pageId(),
            Map.copyOf(mapping),
            List.copyOf(nextBase.values()),
            origin.sourcePageId(),
            Map.copyOf(references),
            List.copyOf(graphBaseline.values())));
    var updated = page(d, new Page(page.id(), page.name(), page.purpose(), List.copyOf(result)));
    return new Merge(
        new Document(
            1,
            d.screenId(),
            d.storeId(),
            d.canvas(),
            updated.pages(),
            List.copyOf(workflows),
            List.copyOf(origins)),
        List.copyOf(changes));
  }

  private static SalesScreens.Workflow remapFlow(SalesScreens.Workflow f, Map<UUID, UUID> ids) {
    return new SalesScreens.Workflow(
        ids.get(f.id()),
        f.name(),
        ids.get(f.entry()),
        f.steps().stream()
            .map(
                n ->
                    new SalesScreens.Step(
                        ids.get(n.id()),
                        n.type(),
                        n.action(),
                        n.condition(),
                        n.prompt(),
                        ids.get(n.next()),
                        ids.get(n.otherwise())))
            .toList());
  }

  private static String graphSummary(SalesScreens.Workflow f) {
    var indices = new HashMap<UUID, Integer>();
    for (int i = 0; i < f.steps().size(); i++) indices.put(f.steps().get(i).id(), i + 1);
    return f.name()
        + " · start "
        + indices.get(f.entry())
        + " · "
        + f.steps().stream()
            .map(
                n ->
                    n.type()
                        + " "
                        + (n.action() == null ? "" : n.action())
                        + " "
                        + (n.prompt() == null ? "" : n.prompt())
                        + " "
                        + (n.condition() == null ? "" : n.condition())
                        + " → "
                        + indices.get(n.next())
                        + " / "
                        + indices.get(n.otherwise()))
            .toList();
  }

  private static Widget mergeWidget(
      Widget b, Widget local, Widget n, Set<String> accept, List<Conflict> changes) {
    return new Widget(
        local.id(),
        choose(local.id(), "type", b.type(), local.type(), n.type(), accept, changes),
        choose(local.id(), "rect", b.rect(), local.rect(), n.rect(), accept, changes),
        choose(local.id(), "style", b.style(), local.style(), n.style(), accept, changes),
        choose(local.id(), "label", b.label(), local.label(), n.label(), accept, changes),
        choose(local.id(), "binding", b.binding(), local.binding(), n.binding(), accept, changes),
        choose(local.id(), "options", b.options(), local.options(), n.options(), accept, changes),
        choose(local.id(), "access", b.access(), local.access(), n.access(), accept, changes),
        choose(local.id(), "group", b.groupId(), local.groupId(), n.groupId(), accept, changes));
  }

  private static <T> T choose(
      UUID id,
      String field,
      T base,
      T local,
      T incoming,
      Set<String> accept,
      List<Conflict> conflicts) {
    if (Objects.equals(base, incoming) || Objects.equals(local, incoming)) return local;
    conflicts.add(
        new Conflict(
            id, field, String.valueOf(base), String.valueOf(local), String.valueOf(incoming)));
    return accept.contains(id + "." + field) ? incoming : local;
  }
}
