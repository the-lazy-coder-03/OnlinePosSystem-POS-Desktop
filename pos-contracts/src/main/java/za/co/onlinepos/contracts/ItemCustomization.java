package za.co.onlinepos.contracts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared, server-authoritative item configuration and exact-money pricing. */
public final class ItemCustomization {
  private ItemCustomization() {}

  public record Extra(
      String key,
      String name,
      BigDecimal price,
      boolean available,
      int included,
      boolean removable,
      String swapGroup,
      String origin) {}

  public record Choice(
      String key,
      String name,
      BigDecimal surcharge,
      UUID productPriceId,
      String componentKey,
      boolean defaultSelected,
      boolean available) {}

  public record Group(
      String key, String name, int min, int max, String replacesKey, List<Choice> choices) {}

  public record ExtraRule(
      BigDecimal price, Integer included, Boolean removable, String swapGroup) {}

  public record ItemRule(
      List<String> exclusions,
      Map<String, ExtraRule> extras,
      List<Group> groups,
      boolean overrideGroups) {}

  public record Slot(
      String key,
      String name,
      int quantity,
      UUID defaultPriceId,
      Map<UUID, BigDecimal> choices,
      Integer minimum,
      Integer maximum,
      Map<UUID, Integer> quantities) {
    public Slot(
        String key, String name, int quantity, UUID defaultPriceId, Map<UUID, BigDecimal> choices) {
      this(key, name, quantity, defaultPriceId, choices, 1, 1, Map.of());
    }

    public int min() {
      return minimum == null ? 1 : minimum;
    }

    public int max() {
      return maximum == null ? 1 : maximum;
    }

    public int quantityFor(UUID id) {
      return quantities == null ? quantity : quantities.getOrDefault(id, quantity);
    }
  }

  public enum PricingMode {
    FIXED,
    CALCULATED
  }

  public record Combo(
      BigDecimal price, List<Slot> slots, PricingMode pricingMode, Boolean enabled) {
    public Combo(BigDecimal price, List<Slot> slots) {
      this(price, slots, PricingMode.FIXED, true);
    }

    public Combo(BigDecimal price, List<Slot> slots, PricingMode pricingMode) {
      this(price, slots, pricingMode, true);
    }

    public boolean available() {
      return enabled == null || enabled;
    }

    public PricingMode mode() {
      return pricingMode == null ? PricingMode.FIXED : pricingMode;
    }
  }

  public record Rules(
      Map<String, List<String>> categoryExtras,
      Map<String, String> swapGroups,
      Map<UUID, ItemRule> items,
      Map<UUID, Combo> combos) {
    public static Rules empty() {
      return new Rules(Map.of(), Map.of(), Map.of(), Map.of());
    }
  }

  public record Product(
      UUID priceId,
      String name,
      String category,
      long priceRevision,
      BigDecimal price,
      boolean available,
      List<Extra> extras,
      List<Group> groups,
      Combo combo) {}

  public record Catalog(
      long revision,
      Map<UUID, Product> products,
      Map<String, Extra> extras,
      Map<String, String> categories,
      Rules rules,
      boolean editable,
      Map<String, String> parents) {
    public Catalog(
        long revision,
        Map<UUID, Product> products,
        Map<String, Extra> extras,
        Map<String, String> categories,
        Rules rules,
        boolean editable) {
      this(revision, products, extras, categories, rules, editable, Map.of());
    }
  }

  public static boolean withinCategory(Catalog catalog, String selected, String category) {
    if (selected == null) return true;
    var visited = new HashSet<String>();
    while (category != null && visited.add(category)) {
      if (selected.equals(category)) return true;
      category = catalog.parents() == null ? null : catalog.parents().get(category);
    }
    return false;
  }

  public record Swap(String from, String to, int quantity) {}

  public record Component(
      String slot, UUID priceId, long expectedPriceRevision, Selection selection) {}

  public record Selection(
      long expectedCatalogueRevision,
      Map<String, Integer> removals,
      Map<String, Integer> additions,
      List<Swap> swaps,
      Map<String, List<String>> choices,
      List<Component> components) {
    public static Selection empty(long revision) {
      return new Selection(revision, Map.of(), Map.of(), List.of(), Map.of(), List.of());
    }
  }

  public record QuoteRequest(
      UUID priceId, long expectedPriceRevision, int quantity, Selection selection) {}

  public record Edit(UUID commandId, long expectedRevision, Rules rules) {}

  public record Charge(String label, BigDecimal amount) {}

  public record ResolvedOption(
      String groupKey, String groupName, Choice choice, String productName, Long productRevision) {}

  public record ResolvedComponent(String slot, int quantity, Quote quote) {}

  public record Quote(
      UUID priceId,
      String name,
      long catalogueRevision,
      long priceRevision,
      int quantity,
      BigDecimal basePrice,
      List<Extra> recipe,
      Selection selection,
      List<Charge> charges,
      List<ResolvedOption> options,
      List<ResolvedComponent> components,
      BigDecimal unitPrice,
      BigDecimal total,
      PricingMode pricingMode) {}

  public static BigDecimal money(BigDecimal value) {
    if (value == null || value.signum() < 0 || value.precision() > 19)
      throw new IllegalArgumentException("A nonnegative price is required");
    BigDecimal result;
    try {
      result = value.setScale(2, java.math.RoundingMode.UNNECESSARY);
    } catch (ArithmeticException invalid) {
      throw new IllegalArgumentException("Prices require at most two decimal places");
    }
    if (result.precision() > 19)
      throw new IllegalArgumentException("Price exceeds supported amount");
    return result;
  }

  private static <T> List<T> list(List<T> value) {
    if (value == null || value.size() > 200 || value.stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalArgumentException("Invalid selections");
    return value;
  }

  private static <K, V> Map<K, V> map(Map<K, V> value) {
    if (value == null
        || value.size() > 200
        || value.keySet().stream().anyMatch(java.util.Objects::isNull)
        || value.values().stream().anyMatch(java.util.Objects::isNull))
      throw new IllegalArgumentException("Invalid selections");
    return value;
  }

  private static void count(int n) {
    if (n < 1 || n > 99) throw new IllegalArgumentException("Quantity must be between 1 and 99");
  }

  private static void label(String s) {
    if (s == null || s.isBlank() || s.length() > 128)
      throw new IllegalArgumentException("A name is required (maximum 128 characters)");
  }

  public static Product effective(Catalog catalog, Product base) {
    var extras = new LinkedHashMap<String, Extra>();
    for (String key : catalog.rules().categoryExtras().getOrDefault(base.category(), List.of())) {
      Extra e = catalog.extras().get(key);
      if (e != null)
        extras.put(
            key,
            new Extra(
                e.key(),
                e.name(),
                e.price(),
                e.available(),
                0,
                true,
                catalog.rules().swapGroups().getOrDefault(key, ""),
                "Category"));
    }
    for (var e : base.extras())
      if (e.included() > 0 || !"Candidate".equals(e.origin()) || extras.containsKey(e.key()))
        extras.put(
            e.key(),
            new Extra(
                e.key(),
                e.name(),
                e.price(),
                e.available(),
                e.included(),
                e.removable(),
                catalog.rules().swapGroups().getOrDefault(e.key(), ""),
                e.included() > 0 ? "Recipe" : "Category"));
    var rule = catalog.rules().items().get(base.priceId());
    List<Group> groups = base.groups();
    if (rule != null) {
      for (var entry : rule.extras().entrySet()) {
        var original =
            extras.getOrDefault(
                entry.getKey(),
                base.extras().stream()
                    .filter(e -> e.key().equals(entry.getKey()))
                    .findFirst()
                    .orElse(catalog.extras().get(entry.getKey())));
        if (original == null) throw new IllegalArgumentException("Unknown topping");
        var r = entry.getValue();
        extras.put(
            entry.getKey(),
            new Extra(
                original.key(),
                original.name(),
                r.price() == null ? original.price() : money(r.price()),
                original.available(),
                r.included() == null ? original.included() : r.included(),
                r.removable() == null ? original.removable() : r.removable(),
                r.swapGroup() == null
                    ? catalog.rules().swapGroups().getOrDefault(original.key(), "")
                    : r.swapGroup(),
                "Item override"));
      }
      for (String key : rule.exclusions()) {
        if (extras.containsKey(key) && extras.get(key).included() > 0)
          throw new IllegalArgumentException(
              "Remove the recipe allowance before excluding an included topping");
        extras.remove(key);
      }
      if (rule.overrideGroups()) groups = rule.groups();
    }
    return new Product(
        base.priceId(),
        base.name(),
        base.category(),
        base.priceRevision(),
        base.price(),
        base.available()
            && (catalog.rules().combos().getOrDefault(base.priceId(), base.combo()) == null
                || catalog.rules().combos().getOrDefault(base.priceId(), base.combo()).available()),
        List.copyOf(extras.values()),
        groups,
        catalog.rules().combos().getOrDefault(base.priceId(), base.combo()));
  }

  public static void validate(Catalog c) {
    var r = c.rules();
    map(r.categoryExtras());
    map(r.swapGroups());
    map(r.items());
    map(r.combos());
    r.categoryExtras()
        .forEach(
            (category, keys) -> {
              if (!c.categories().containsKey(category))
                throw new IllegalArgumentException("Unknown category");
              unique(list(keys));
              for (String key : keys)
                if (!c.extras().containsKey(key))
                  throw new IllegalArgumentException("Unknown category topping");
            });
    r.swapGroups()
        .forEach(
            (key, group) -> {
              if (!c.extras().containsKey(key))
                throw new IllegalArgumentException("Unknown swap topping");
              label(group);
            });
    r.items()
        .forEach(
            (id, rule) -> {
              if (!c.products().containsKey(id)) throw new IllegalArgumentException("Unknown item");
              list(rule.exclusions());
              map(rule.extras());
              list(rule.groups());
              unique(rule.exclusions());
              rule.extras()
                  .forEach(
                      (key, e) -> {
                        if (!c.extras().containsKey(key))
                          throw new IllegalArgumentException("Unknown topping override");
                        if (e.price() != null) money(e.price());
                        if (e.included() != null && (e.included() < 0 || e.included() > 99))
                          throw new IllegalArgumentException("Invalid recipe quantity");
                      });
              if (!rule.overrideGroups() && !rule.groups().isEmpty())
                throw new IllegalArgumentException("Enable group overrides to change choices");
            });
    for (var original : c.products().values()) {
      var p = effective(c, original);
      var keys = new HashSet<String>();
      for (var g : p.combo() == null ? list(p.groups()) : List.<Group>of()) {
        label(g.key());
        label(g.name());
        if (!keys.add(g.key()) || g.min() < 0 || g.max() < g.min() || g.max() > 99)
          throw new IllegalArgumentException("Invalid option limits");
        var choices = new HashSet<String>();
        int defaults = 0;
        for (var choice : list(g.choices())) {
          label(choice.key());
          label(choice.name());
          money(choice.surcharge());
          if (!choices.add(choice.key())) throw new IllegalArgumentException("Duplicate option");
          if (choice.defaultSelected()) defaults++;
          if (choice.productPriceId() != null
              && (!c.products().containsKey(choice.productPriceId())
                  || isCombo(c, choice.productPriceId())))
            throw new IllegalArgumentException("Option product must be a standalone product");
          if (choice.componentKey() != null
              && !choice.componentKey().isBlank()
              && p.extras().stream().noneMatch(e -> e.key().equals(choice.componentKey())))
            throw new IllegalArgumentException("Option component must be available to the item");
        }
        if (defaults > g.max() || g.max() > choices.size())
          throw new IllegalArgumentException("Invalid defaults or choice limits");
        if (g.replacesKey() != null
            && !g.replacesKey().isBlank()
            && (g.min() != 1
                || g.max() != 1
                || p.extras().stream()
                    .noneMatch(e -> e.key().equals(g.replacesKey()) && e.included() > 0)))
          throw new IllegalArgumentException(
              "A recipe replacement requires exactly one choice and an included component");
      }
      if (p.combo() != null) {
        if (c.rules().combos().containsKey(p.priceId())
            && (p.extras().stream().anyMatch(e -> e.included() > 0) || !p.groups().isEmpty()))
          throw new IllegalArgumentException(
              "Remove parent recipe allowances and options before converting to a combo; customize components instead");
        money(p.combo().price());
        if (list(p.combo().slots()).isEmpty())
          throw new IllegalArgumentException("A combo needs components");
        var slots = new HashSet<String>();
        for (var slot : p.combo().slots()) {
          label(slot.key());
          label(slot.name());
          count(slot.quantity());
          if (slot.min() < 0
              || slot.max() < 1
              || slot.min() > slot.max()
              || slot.max() > map(slot.choices()).size())
            throw new IllegalArgumentException("Invalid combo selection limits");
          if (slot.quantities() != null) {
            map(slot.quantities());
            for (var entry : slot.quantities().entrySet()) {
              if (!slot.choices().containsKey(entry.getKey()))
                throw new IllegalArgumentException("Unknown quantity choice");
              count(entry.getValue());
            }
          }
          if (!slots.add(slot.key()) || map(slot.choices()).isEmpty())
            throw new IllegalArgumentException("Invalid combo slots");
          if (slot.defaultPriceId() != null && !slot.choices().containsKey(slot.defaultPriceId()))
            throw new IllegalArgumentException("Default must be an allowed choice");
          slot.choices()
              .forEach(
                  (id, price) -> {
                    money(price);
                    if (!c.products().containsKey(id) || id.equals(p.priceId()) || isCombo(c, id))
                      throw new IllegalArgumentException("Nested combos are not supported");
                  });
          if (p.combo().mode() == PricingMode.FIXED
              && slot.defaultPriceId() != null
              && money(slot.choices().get(slot.defaultPriceId())).signum() != 0)
            throw new IllegalArgumentException(
                "Default combo choice must be included at no surcharge");
        }
      }
    }
  }

  private static boolean isCombo(Catalog c, UUID id) {
    return c.rules().combos().containsKey(id) || c.products().get(id).combo() != null;
  }

  private static void unique(List<String> values) {
    if (new HashSet<>(values).size() != values.size())
      throw new IllegalArgumentException("Duplicate selection");
  }

  public static Quote quote(Catalog c, QuoteRequest request) {
    return quote(c, request, false);
  }

  private static Quote quote(Catalog c, QuoteRequest request, boolean component) {
    Product original = c.products().get(request.priceId());
    if (original == null || !original.available())
      throw new IllegalArgumentException("Product unavailable");
    if (request.expectedPriceRevision() != original.priceRevision())
      throw new Stale("Price changed; refresh and review");
    if (request.quantity() < 1 || request.quantity() > 999)
      throw new IllegalArgumentException("Invalid quantity");
    Product p = effective(c, original);
    if (!p.available()) throw new IllegalArgumentException("Product unavailable at this branch");
    Selection s = request.selection() == null ? Selection.empty(c.revision()) : request.selection();
    if (s.expectedCatalogueRevision() != c.revision())
      throw new Stale("Configuration changed; refresh and review");
    map(s.removals());
    map(s.additions());
    map(s.choices());
    list(s.swaps());
    list(s.components());
    var extras = new LinkedHashMap<String, Extra>();
    p.extras().forEach(e -> extras.put(e.key(), e));
    var remaining = new HashMap<String, Integer>();
    p.extras().forEach(e -> remaining.put(e.key(), e.included()));
    var charges = new ArrayList<Charge>();
    var resolvedChoices = new LinkedHashMap<String, List<String>>();
    var options = new ArrayList<ResolvedOption>();
    var consumed = new HashSet<String>();
    for (var group : p.combo() == null ? p.groups() : List.<Group>of()) {
      if (!consumed.add(group.key())) throw new IllegalArgumentException("Duplicate group");
      List<String> selected =
          s.choices().containsKey(group.key())
              ? list(s.choices().get(group.key()))
              : group.choices().stream().filter(Choice::defaultSelected).map(Choice::key).toList();
      unique(selected);
      if (selected.size() < group.min() || selected.size() > group.max())
        throw new IllegalArgumentException(
            "Choose " + group.min() + "–" + group.max() + " for " + group.name());
      for (String key : selected) {
        var choice =
            group.choices().stream()
                .filter(o -> o.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown option"));
        if (!choice.available()
            || choice.productPriceId() != null
                && !c.products().get(choice.productPriceId()).available())
          throw new IllegalArgumentException("Option unavailable");
        if (group.replacesKey() != null && !group.replacesKey().isBlank()) {
          int allowance = remaining.getOrDefault(group.replacesKey(), 0);
          if (allowance < 1)
            throw new IllegalArgumentException("Included replacement allowance already used");
          take(remaining, group.replacesKey(), allowance);
          if (choice.componentKey() == null || choice.componentKey().isBlank())
            throw new IllegalArgumentException("Choose a replacement component");
          var replacement = required(extras, choice.componentKey());
          if (!replacement.available())
            throw new IllegalArgumentException("Replacement unavailable");
          remaining.merge(choice.componentKey(), allowance, Integer::sum);
        }
        var associated =
            choice.productPriceId() == null ? null : c.products().get(choice.productPriceId());
        options.add(
            new ResolvedOption(
                group.key(),
                group.name(),
                choice,
                associated == null ? "" : associated.name(),
                associated == null ? null : associated.priceRevision()));
        charge(charges, group.name() + ": " + choice.name(), money(choice.surcharge()));
      }
      resolvedChoices.put(group.key(), selected);
    }
    if (!consumed.containsAll(s.choices().keySet()))
      throw new IllegalArgumentException("Unknown choice group");
    var resolvedRecipe = new ArrayList<Extra>();
    for (var e : p.combo() == null ? extras.values() : List.<Extra>of())
      resolvedRecipe.add(
          new Extra(
              e.key(),
              e.name(),
              e.price(),
              e.available(),
              remaining.getOrDefault(e.key(), 0),
              e.removable(),
              e.swapGroup(),
              e.origin()));
    s.removals()
        .forEach(
            (key, n) -> {
              count(n);
              var e = required(extras, key);
              if (!e.removable())
                throw new IllegalArgumentException("This ingredient cannot be removed");
              take(remaining, key, n);
            });
    for (var swap : s.swaps()) {
      count(swap.quantity());
      var from = required(extras, swap.from());
      var to = required(extras, swap.to());
      if (!from.removable()
          || !to.available()
          || from.key().equals(to.key())
          || from.swapGroup() == null
          || from.swapGroup().isBlank()
          || !from.swapGroup().equals(to.swapGroup()))
        throw new IllegalArgumentException("Incompatible topping swap");
      take(remaining, from.key(), swap.quantity());
      BigDecimal difference = money(to.price()).subtract(money(from.price())).max(BigDecimal.ZERO);
      charge(
          charges,
          from.name() + " → " + to.name() + " × " + swap.quantity(),
          difference.multiply(BigDecimal.valueOf(swap.quantity())));
    }
    s.additions()
        .forEach(
            (key, n) -> {
              count(n);
              var e = required(extras, key);
              if (!e.available()) throw new IllegalArgumentException("Topping unavailable");
              charge(
                  charges,
                  "Extra " + e.name() + " × " + n,
                  money(e.price()).multiply(BigDecimal.valueOf(n)));
            });
    for (var e : extras.values())
      if (p.combo() == null && remaining.getOrDefault(e.key(), 0) > 0 && !e.available())
        throw new IllegalArgumentException("Included ingredient unavailable: " + e.name());
    var components = new ArrayList<ResolvedComponent>();
    var resolvedComponents = new ArrayList<Component>();
    BigDecimal base =
        component
            ? BigDecimal.ZERO.setScale(2)
            : money(
                p.combo() == null
                    ? p.price()
                    : p.combo().mode() == PricingMode.CALCULATED
                        ? BigDecimal.ZERO
                        : p.combo().price());
    if (p.combo() != null) {
      if (component) throw new IllegalArgumentException("Nested combo");
      if (!s.additions().isEmpty()
          || !s.removals().isEmpty()
          || !s.swaps().isEmpty()
          || !s.choices().isEmpty())
        throw new IllegalArgumentException("Customize each combo component instead");
      var supplied = new LinkedHashMap<String, List<Component>>();
      for (var selection : s.components())
        supplied.computeIfAbsent(selection.slot(), ignored -> new ArrayList<>()).add(selection);
      for (var slot : p.combo().slots()) {
        var selections = supplied.remove(slot.key());
        if (selections == null) {
          selections = new ArrayList<>();
          if (slot.defaultPriceId() != null) {
            var product = c.products().get(slot.defaultPriceId());
            selections.add(
                new Component(slot.key(), slot.defaultPriceId(), product.priceRevision(), null));
          }
        }
        if (selections.stream().anyMatch(selection -> selection.priceId() == null)) {
          if (selections.size() != 1)
            throw new IllegalArgumentException("Invalid empty combo group");
          selections = List.of();
        }
        if (selections.size() < slot.min() || selections.size() > slot.max())
          throw new IllegalArgumentException(
              "Choose " + slot.min() + "–" + slot.max() + " for " + slot.name());
        if (selections.isEmpty()) resolvedComponents.add(new Component(slot.key(), null, 0, null));
        var selectedIds = new HashSet<UUID>();
        for (var chosen : selections) {
          if (!selectedIds.add(chosen.priceId()))
            throw new IllegalArgumentException("Duplicate combo choice");
          if (!slot.choices().containsKey(chosen.priceId()))
            throw new IllegalArgumentException("Product not allowed for " + slot.name());
          int quantity = slot.quantityFor(chosen.priceId());
          var quote =
              quote(
                  c,
                  new QuoteRequest(
                      chosen.priceId(),
                      chosen.expectedPriceRevision(),
                      quantity,
                      chosen.selection()),
                  p.combo().mode() == PricingMode.FIXED);
          if (p.combo().mode() == PricingMode.FIXED)
            charge(
                charges,
                slot.name() + " upgrade × " + quantity,
                money(slot.choices().get(chosen.priceId())).multiply(BigDecimal.valueOf(quantity)));
          charge(
              charges,
              slot.name()
                  + (p.combo().mode() == PricingMode.FIXED ? " customization" : " selected price"),
              quote.total());
          components.add(new ResolvedComponent(slot.name(), quantity, quote));
          resolvedComponents.add(
              new Component(
                  slot.key(), chosen.priceId(), chosen.expectedPriceRevision(), quote.selection()));
        }
      }
      if (!supplied.isEmpty()) throw new IllegalArgumentException("Unknown combo slot");
    } else if (!s.components().isEmpty())
      throw new IllegalArgumentException("Standalone item has no combo components");
    BigDecimal unit =
        money(
            base.add(
                charges.stream().map(Charge::amount).reduce(BigDecimal.ZERO, BigDecimal::add)));
    var resolved =
        new Selection(
            c.revision(),
            Map.copyOf(s.removals()),
            Map.copyOf(s.additions()),
            List.copyOf(s.swaps()),
            Map.copyOf(resolvedChoices),
            List.copyOf(resolvedComponents));
    return new Quote(
        p.priceId(),
        p.name(),
        c.revision(),
        p.priceRevision(),
        request.quantity(),
        base,
        List.copyOf(resolvedRecipe),
        resolved,
        List.copyOf(charges),
        List.copyOf(options),
        List.copyOf(components),
        unit,
        money(unit.multiply(BigDecimal.valueOf(request.quantity()))),
        p.combo() == null ? null : p.combo().mode());
  }

  private static Extra required(Map<String, Extra> extras, String key) {
    var e = extras.get(key);
    if (e == null) throw new IllegalArgumentException("Topping is not available to this item");
    return e;
  }

  private static void take(Map<String, Integer> values, String key, int n) {
    int left = values.getOrDefault(key, 0);
    if (left < n) throw new IllegalArgumentException("Included topping allowance already used");
    values.put(key, left - n);
  }

  private static void charge(List<Charge> values, String name, BigDecimal amount) {
    values.add(new Charge(name, money(amount)));
  }

  public static final class Stale extends IllegalArgumentException {
    public Stale(String message) {
      super(message);
    }
  }
}
