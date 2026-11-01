package za.co.onlinepos.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.ItemCustomization.Catalog;
import za.co.onlinepos.contracts.ItemCustomization.Component;
import za.co.onlinepos.contracts.ItemCustomization.Extra;
import za.co.onlinepos.contracts.ItemCustomization.Product;
import za.co.onlinepos.contracts.ItemCustomization.QuoteRequest;
import za.co.onlinepos.contracts.ItemCustomization.Selection;
import za.co.onlinepos.contracts.ItemCustomization.Swap;

/** Selection-only draft. No order line or reusable recipe is changed by a topping tap. */
final class ToppingDraft {
  record Target(int index, String label, Product product) {}

  record Amount(Extra extra, int included, int added, int removed, int swappedIn, int total) {}

  private final Catalog catalog;
  private final QuoteRequest original;
  private Selection selection;
  private final List<Target> targets;

  ToppingDraft(Catalog catalog, QuoteRequest request, Selection resolved) {
    this.catalog = catalog;
    original = request;
    selection = resolved == null ? Selection.empty(catalog.revision()) : resolved;
    var product = ItemCustomization.effective(catalog, catalog.products().get(request.priceId()));
    var result = new ArrayList<Target>();
    if (product.combo() == null) {
      if (!product.extras().isEmpty()) result.add(new Target(-1, product.name(), product));
    } else {
      for (int i = 0; i < selection.components().size(); i++) {
        var component = selection.components().get(i);
        if (component.priceId() == null) continue;
        var child =
            ItemCustomization.effective(catalog, catalog.products().get(component.priceId()));
        if (child.extras().isEmpty()) continue;
        String slot =
            product.combo().slots().stream()
                .filter(s -> s.key().equals(component.slot()))
                .map(s -> s.name())
                .findFirst()
                .orElse(component.slot());
        result.add(new Target(i, slot + " · " + child.name(), child));
      }
    }
    targets = List.copyOf(result);
  }

  List<Target> targets() {
    return targets;
  }

  boolean combo() {
    return ItemCustomization.effective(catalog, catalog.products().get(original.priceId())).combo()
        != null;
  }

  QuoteRequest request() {
    return new QuoteRequest(
        original.priceId(), original.expectedPriceRevision(), original.quantity(), selection);
  }

  private Selection selection(Target target) {
    if (target.index() < 0) return selection;
    var child = selection.components().get(target.index()).selection();
    return child == null ? Selection.empty(selection.expectedCatalogueRevision()) : child;
  }

  List<Amount> amounts(Target target) {
    var selected = selection(target);
    var base = new LinkedHashMap<String, Integer>();
    target.product().extras().forEach(e -> base.put(e.key(), e.included()));
    for (var group : target.product().groups()) {
      if (group.replacesKey() == null || group.replacesKey().isBlank()) continue;
      var chosen =
          selected
              .choices()
              .getOrDefault(
                  group.key(),
                  group.choices().stream()
                      .filter(ItemCustomization.Choice::defaultSelected)
                      .map(ItemCustomization.Choice::key)
                      .toList());
      for (var choice : group.choices())
        if (chosen.contains(choice.key()) && choice.componentKey() != null) {
          int count = base.getOrDefault(group.replacesKey(), 0);
          base.put(group.replacesKey(), 0);
          base.merge(choice.componentKey(), count, Integer::sum);
        }
    }
    var result = new ArrayList<Amount>();
    for (var extra : target.product().extras()) {
      int included = base.getOrDefault(extra.key(), 0);
      int added = selected.additions().getOrDefault(extra.key(), 0);
      int removed = selected.removals().getOrDefault(extra.key(), 0);
      int out =
          selected.swaps().stream()
              .filter(s -> s.from().equals(extra.key()))
              .mapToInt(Swap::quantity)
              .sum();
      int in =
          selected.swaps().stream()
              .filter(s -> s.to().equals(extra.key()))
              .mapToInt(Swap::quantity)
              .sum();
      result.add(
          new Amount(extra, included, added, removed, in, included + added - removed - out + in));
    }
    return List.copyOf(result);
  }

  boolean canTap(Target target, String key, boolean remove) {
    return proposed(target, key, remove) != null;
  }

  boolean tap(Target target, String key, boolean remove) {
    var next = proposed(target, key, remove);
    if (next == null) return false;
    if (target.index() < 0) selection = next;
    else {
      var children = new ArrayList<>(selection.components());
      var old = children.get(target.index());
      children.set(
          target.index(),
          new Component(old.slot(), old.priceId(), old.expectedPriceRevision(), next));
      selection =
          new Selection(
              selection.expectedCatalogueRevision(),
              selection.removals(),
              selection.additions(),
              selection.swaps(),
              selection.choices(),
              List.copyOf(children));
    }
    return true;
  }

  private Selection proposed(Target target, String key, boolean remove) {
    var selected = selection(target);
    var amount =
        amounts(target).stream().filter(a -> a.extra().key().equals(key)).findFirst().orElse(null);
    if (amount == null) return null;
    var e = amount.extra();
    var removed = new LinkedHashMap<>(selected.removals());
    var added = new LinkedHashMap<>(selected.additions());
    var swaps = new ArrayList<>(selected.swaps());
    if (remove) {
      if (amount.added() > 0) put(added, key, amount.added() - 1);
      else {
        if (!e.removable()
            || amount.total() < 1
            || target.product().groups().stream()
                .filter(g -> g.replacesKey() != null && !g.replacesKey().isBlank())
                .anyMatch(
                    g ->
                        g.replacesKey().equals(key)
                            || g.choices().stream().anyMatch(c -> key.equals(c.componentKey()))))
          return null;
        int swappedOut =
            swaps.stream().filter(s -> s.from().equals(key)).mapToInt(Swap::quantity).sum();
        if (amount.included() - amount.removed() - swappedOut > 0)
          put(removed, key, amount.removed() + 1);
        else {
          int index = -1;
          for (int i = 0; i < swaps.size(); i++)
            if (swaps.get(i).to().equals(key)) {
              index = i;
              break;
            }
          if (index < 0) return null;
          var swap = swaps.get(index);
          if (swap.quantity() == 1) swaps.remove(index);
          else swaps.set(index, new Swap(swap.from(), swap.to(), swap.quantity() - 1));
          put(removed, swap.from(), removed.getOrDefault(swap.from(), 0) + 1);
        }
      }
    } else {
      if (!e.available()) return null;
      if (amount.removed() > 0) put(removed, key, amount.removed() - 1);
      else {
        if (e.price() == null || amount.added() >= 99) return null;
        put(added, key, amount.added() + 1);
      }
    }
    return new Selection(
        selected.expectedCatalogueRevision(),
        Map.copyOf(removed),
        Map.copyOf(added),
        List.copyOf(swaps),
        selected.choices(),
        selected.components());
  }

  private static void put(Map<String, Integer> map, String key, int count) {
    if (count == 0) map.remove(key);
    else map.put(key, count);
  }
}
