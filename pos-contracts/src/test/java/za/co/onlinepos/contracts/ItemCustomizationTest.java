package za.co.onlinepos.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.onlinepos.contracts.ItemCustomization.Catalog;
import za.co.onlinepos.contracts.ItemCustomization.Choice;
import za.co.onlinepos.contracts.ItemCustomization.Combo;
import za.co.onlinepos.contracts.ItemCustomization.Extra;
import za.co.onlinepos.contracts.ItemCustomization.ExtraRule;
import za.co.onlinepos.contracts.ItemCustomization.Group;
import za.co.onlinepos.contracts.ItemCustomization.ItemRule;
import za.co.onlinepos.contracts.ItemCustomization.Product;
import za.co.onlinepos.contracts.ItemCustomization.QuoteRequest;
import za.co.onlinepos.contracts.ItemCustomization.Rules;
import za.co.onlinepos.contracts.ItemCustomization.Selection;
import za.co.onlinepos.contracts.ItemCustomization.Slot;
import za.co.onlinepos.contracts.ItemCustomization.Swap;

class ItemCustomizationTest {
  static UUID id(int n) {
    return new UUID(0, n);
  }

  static BigDecimal money(String n) {
    return new BigDecimal(n);
  }

  private Catalog catalog() {
    var cheese = new Extra("cheese", "Cheese", money("5.00"), true, 1, true, "toppings", "Recipe");
    var bacon = new Extra("bacon", "Bacon", money("8.00"), true, 0, true, "toppings", "Category");
    var onion = new Extra("onion", "Onion", money("2.00"), true, 0, true, "toppings", "Category");
    var patty = new Extra("patty", "Patty", money("15.00"), true, 1, false, "protein", "Recipe");
    var chicken =
        new Extra("chicken", "Chicken", money("15.00"), true, 0, true, "protein", "Category");
    var extras =
        Map.of(
            "cheese", cheese, "bacon", bacon, "onion", onion, "patty", patty, "chicken", chicken);
    var burger =
        new Product(
            id(1),
            "Burger",
            "burgers",
            3,
            money("60.00"),
            true,
            List.of(cheese, patty),
            List.of(),
            null);
    var coke =
        new Product(id(2), "Coke", "drinks", 3, money("18.00"), true, List.of(), List.of(), null);
    var redbull =
        new Product(
            id(3), "Red Bull", "drinks", 3, money("35.00"), true, List.of(), List.of(), null);
    var combo =
        new Product(
            id(4),
            "Meal",
            "combos",
            3,
            money("85.00"),
            true,
            List.of(),
            List.of(),
            new Combo(
                money("85.00"),
                List.of(
                    new Slot("main", "Burger", 1, id(1), Map.of(id(1), BigDecimal.ZERO)),
                    new Slot(
                        "drink",
                        "Drink",
                        1,
                        id(2),
                        Map.of(id(2), BigDecimal.ZERO, id(3), money("12.00"))))));
    var rules =
        new Rules(
            Map.of("burgers", List.of("bacon", "onion", "chicken")),
            Map.of(
                "cheese",
                "toppings",
                "bacon",
                "toppings",
                "onion",
                "toppings",
                "patty",
                "protein",
                "chicken",
                "protein"),
            Map.of(),
            Map.of());
    return new Catalog(
        7,
        Map.of(id(1), burger, id(2), coke, id(3), redbull, id(4), combo),
        extras,
        Map.of("burgers", "Burgers", "drinks", "Drinks", "combos", "Combos"),
        rules,
        true);
  }

  private Selection selection(
      Map<String, Integer> removes, Map<String, Integer> adds, List<Swap> swaps) {
    return new Selection(7, removes, adds, swaps, Map.of(), List.of());
  }

  private BigDecimal price(Selection s) {
    return ItemCustomization.quote(catalog(), new QuoteRequest(id(1), 3, 1, s)).total();
  }

  @Test
  void includedPortionsRemovalsAdditionsAndUpgradeOnlySwapsUseExactMoney() {
    ItemCustomization.validate(catalog());
    assertEquals(money("60.00"), price(Selection.empty(7)));
    assertEquals(money("60.00"), price(selection(Map.of("cheese", 1), Map.of(), List.of())));
    assertEquals(money("65.00"), price(selection(Map.of(), Map.of("cheese", 1), List.of())));
    assertEquals(
        money("63.00"),
        price(selection(Map.of(), Map.of(), List.of(new Swap("cheese", "bacon", 1)))));
    assertEquals(
        money("60.00"),
        price(selection(Map.of(), Map.of(), List.of(new Swap("cheese", "onion", 1)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            price(
                selection(Map.of("cheese", 1), Map.of(), List.of(new Swap("cheese", "bacon", 1)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> price(selection(Map.of(), Map.of(), List.of(new Swap("cheese", "bacon", 2)))));
    assertThrows(
        IllegalArgumentException.class,
        () -> price(selection(Map.of("patty", 1), Map.of(), List.of())));
    assertThrows(
        IllegalArgumentException.class,
        () -> price(selection(Map.of(), Map.of("unknown", 1), List.of())));
    assertThrows(
        IllegalArgumentException.class,
        () -> price(selection(Map.of(), Map.of(), List.of(new Swap("cheese", "chicken", 1)))));
    assertEquals(
        money("126.00"),
        ItemCustomization.quote(
                catalog(),
                new QuoteRequest(
                    id(1),
                    3,
                    2,
                    selection(Map.of(), Map.of(), List.of(new Swap("cheese", "bacon", 1)))))
            .total());
  }

  @Test
  void inheritanceOverridesAndSizeSpecificPricesAreResolvedAtQuoteTime() {
    var c = catalog();
    var rule =
        new ItemRule(
            List.of("onion"),
            Map.of("bacon", new ExtraRule(money("0.10"), 0, true, "toppings")),
            List.of(),
            false);
    var rules =
        new Rules(
            c.rules().categoryExtras(), c.rules().swapGroups(), Map.of(id(1), rule), Map.of());
    var updated = new Catalog(7, c.products(), c.extras(), c.categories(), rules, true);
    assertTrue(
        ItemCustomization.effective(updated, c.products().get(id(1))).extras().stream()
            .noneMatch(e -> e.key().equals("onion")));
    assertEquals(
        money("60.30"),
        ItemCustomization.quote(
                updated,
                new QuoteRequest(id(1), 3, 1, selection(Map.of(), Map.of("bacon", 3), List.of())))
            .total());
    var ingredient = new Extra("olive", "Olive", money("4.25"), true, 0, true, "", "Candidate");
    var pizza =
        new Product(
            id(5),
            "Large pizza",
            "pizza",
            3,
            money("80.00"),
            true,
            List.of(ingredient),
            List.of(),
            null);
    var products = new java.util.HashMap<>(c.products());
    products.put(id(5), pizza);
    var extras = new java.util.HashMap<>(c.extras());
    extras.put("olive", new Extra("olive", "Olive", null, true, 0, true, "", "Catalogue"));
    var categories = new java.util.HashMap<>(c.categories());
    categories.put("pizza", "Pizza");
    var categoryExtras = new java.util.HashMap<>(c.rules().categoryExtras());
    categoryExtras.put("pizza", List.of("olive"));
    var sized =
        new Catalog(
            7,
            products,
            extras,
            categories,
            new Rules(categoryExtras, c.rules().swapGroups(), Map.of(), Map.of()),
            true);
    assertEquals(
        money("84.25"),
        ItemCustomization.quote(
                sized,
                new QuoteRequest(id(5), 3, 1, selection(Map.of(), Map.of("olive", 1), List.of())))
            .total());
  }

  @Test
  void choicesReplaceIncludedComponentsAndEnforceLimitsDefaultsAndAvailability() {
    var c = catalog();
    var group =
        new Group(
            "protein",
            "Protein",
            1,
            1,
            "patty",
            List.of(
                new Choice("beef", "Beef", money("0.00"), null, "patty", false, true),
                new Choice("chicken", "Chicken", money("2.50"), null, "chicken", true, true)));
    var rules =
        new Rules(
            c.rules().categoryExtras(),
            c.rules().swapGroups(),
            Map.of(id(1), new ItemRule(List.of(), Map.of(), List.of(group), true)),
            Map.of());
    var configured = new Catalog(7, c.products(), c.extras(), c.categories(), rules, true);
    ItemCustomization.validate(configured);
    var quote = ItemCustomization.quote(configured, new QuoteRequest(id(1), 3, 1, null));
    assertEquals(money("62.50"), quote.total());
    assertEquals("chicken", quote.options().getFirst().choice().componentKey());
    assertEquals(
        0,
        quote.recipe().stream()
            .filter(e -> e.key().equals("patty"))
            .findFirst()
            .orElseThrow()
            .included());
    assertEquals(
        1,
        quote.recipe().stream()
            .filter(e -> e.key().equals("chicken"))
            .findFirst()
            .orElseThrow()
            .included());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ItemCustomization.quote(
                configured,
                new QuoteRequest(
                    id(1),
                    3,
                    1,
                    new Selection(
                        7,
                        Map.of(),
                        Map.of(),
                        List.of(),
                        Map.of("protein", List.of()),
                        List.of()))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ItemCustomization.quote(
                configured,
                new QuoteRequest(
                    id(1),
                    3,
                    1,
                    new Selection(
                        7,
                        Map.of(),
                        Map.of(),
                        List.of(),
                        Map.of("protein", List.of("beef", "chicken")),
                        List.of()))));
    var doubleRule =
        new ItemRule(
            List.of(), Map.of("patty", new ExtraRule(null, 2, false, null)), List.of(group), true);
    var doubleCatalog =
        new Catalog(
            7,
            c.products(),
            c.extras(),
            c.categories(),
            new Rules(
                c.rules().categoryExtras(),
                c.rules().swapGroups(),
                Map.of(id(1), doubleRule),
                Map.of()),
            true);
    var doubleQuote = ItemCustomization.quote(doubleCatalog, new QuoteRequest(id(1), 3, 1, null));
    assertEquals(
        0,
        doubleQuote.recipe().stream()
            .filter(e -> e.key().equals("patty"))
            .findFirst()
            .orElseThrow()
            .included());
    assertEquals(
        2,
        doubleQuote.recipe().stream()
            .filter(e -> e.key().equals("chicken"))
            .findFirst()
            .orElseThrow()
            .included());
    assertEquals(money("62.50"), doubleQuote.total());
    assertThrows(
        ItemCustomization.Stale.class,
        () -> ItemCustomization.quote(configured, new QuoteRequest(id(1), 2, 1, null)));
    assertThrows(
        ItemCustomization.Stale.class,
        () ->
            ItemCustomization.quote(configured, new QuoteRequest(id(1), 3, 1, Selection.empty(6))));
  }

  @Test
  void combosChargeConfiguredUpgradesAndComponentExtrasWithoutStandalonePrices() {
    var selection =
        new Selection(
            7,
            Map.of(),
            Map.of(),
            List.of(),
            Map.of(),
            List.of(
                new ItemCustomization.Component(
                    "main", id(1), 3, selection(Map.of(), Map.of("bacon", 1), List.of())),
                new ItemCustomization.Component("drink", id(3), 3, null)));
    var q = ItemCustomization.quote(catalog(), new QuoteRequest(id(4), 3, 2, selection));
    assertEquals(money("105.00"), q.unitPrice());
    assertEquals(money("210.00"), q.total());
    assertEquals(money("8.00"), q.components().getFirst().quote().total());
    var bad =
        new Combo(
            money("50.00"),
            List.of(new Slot("nested", "Nested", 1, id(4), Map.of(id(4), BigDecimal.ZERO))));
    var c = catalog();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ItemCustomization.validate(
                new Catalog(
                    7,
                    c.products(),
                    c.extras(),
                    c.categories(),
                    new Rules(
                        c.rules().categoryExtras(),
                        c.rules().swapGroups(),
                        Map.of(),
                        Map.of(id(1), bad)),
                    true)));
    assertThrows(IllegalArgumentException.class, () -> ItemCustomization.money(money("1.001")));
  }

  private Catalog withCombo(Combo combo) {
    var c = catalog();
    return new Catalog(
        c.revision(),
        c.products(),
        c.extras(),
        c.categories(),
        new Rules(
            c.rules().categoryExtras(),
            c.rules().swapGroups(),
            c.rules().items(),
            Map.of(id(4), combo)),
        true);
  }

  @Test
  void calculatedComboUsesSelectedBranchPricesAndQuantitiesWithoutUpgradeFees() {
    var c =
        withCombo(
            new Combo(
                money("999.00"),
                List.of(
                    new Slot(
                        "drinks",
                        "Drinks",
                        1,
                        null,
                        Map.of(id(2), money("20.00"), id(3), money("99.00")),
                        1,
                        2,
                        Map.of(id(2), 2, id(3), 1))),
                ItemCustomization.PricingMode.CALCULATED));
    ItemCustomization.validate(c);
    var selections =
        new Selection(
            7,
            Map.of(),
            Map.of(),
            List.of(),
            Map.of(),
            List.of(
                new ItemCustomization.Component("drinks", id(2), 3, null),
                new ItemCustomization.Component("drinks", id(3), 3, null)));
    var quote = ItemCustomization.quote(c, new QuoteRequest(id(4), 3, 2, selections));
    assertEquals(money("142.00"), quote.total());
    assertEquals(money("0.00"), quote.basePrice());
    assertEquals(2, quote.components().getFirst().quantity());
    assertEquals(
        quote.total(),
        ItemCustomization.quote(c, new QuoteRequest(id(4), 3, 2, quote.selection())).total());
    var duplicate =
        new Selection(
            7,
            Map.of(),
            Map.of(),
            List.of(),
            Map.of(),
            List.of(
                new ItemCustomization.Component("drinks", id(2), 3, null),
                new ItemCustomization.Component("drinks", id(2), 3, null)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ItemCustomization.quote(c, new QuoteRequest(id(4), 3, 1, duplicate)));
  }

  @Test
  void optionalComboCanDeselectDefaultAndBranchDisabledComboCannotBeQuoted() {
    var slots =
        List.of(
            new Slot("drink", "Drink", 1, id(2), Map.of(id(2), BigDecimal.ZERO), 0, 1, Map.of()));
    var c = withCombo(new Combo(money("50.00"), slots));
    var empty =
        new Selection(
            7,
            Map.of(),
            Map.of(),
            List.of(),
            Map.of(),
            List.of(new ItemCustomization.Component("drink", null, 0, null)));
    var quote = ItemCustomization.quote(c, new QuoteRequest(id(4), 3, 1, empty));
    assertEquals(money("50.00"), quote.total());
    assertTrue(quote.components().isEmpty());
    assertTrue(
        ItemCustomization.quote(c, new QuoteRequest(id(4), 3, 1, quote.selection()))
            .components()
            .isEmpty());
    var disabled =
        withCombo(new Combo(money("50.00"), slots, ItemCustomization.PricingMode.FIXED, false));
    assertThrows(
        IllegalArgumentException.class,
        () -> ItemCustomization.quote(disabled, new QuoteRequest(id(4), 3, 1, empty)));
  }
}
