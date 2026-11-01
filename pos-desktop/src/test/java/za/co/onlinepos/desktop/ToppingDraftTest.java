package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.ItemCustomization.Catalog;
import za.co.onlinepos.contracts.ItemCustomization.Choice;
import za.co.onlinepos.contracts.ItemCustomization.Combo;
import za.co.onlinepos.contracts.ItemCustomization.Extra;
import za.co.onlinepos.contracts.ItemCustomization.Group;
import za.co.onlinepos.contracts.ItemCustomization.Product;
import za.co.onlinepos.contracts.ItemCustomization.QuoteRequest;
import za.co.onlinepos.contracts.ItemCustomization.Selection;
import za.co.onlinepos.contracts.ItemCustomization.Slot;
import za.co.onlinepos.contracts.ItemCustomization.Swap;

class ToppingDraftTest {
  static final UUID PIZZA = new UUID(0, 1),
      BURGER = new UUID(0, 2),
      DRINK = new UUID(0, 3),
      COMBO = new UUID(0, 4);

  static Catalog catalog() {
    var cheese =
        new Extra("cheese", "Cheese", new BigDecimal("5.00"), true, 2, true, "toppings", "Recipe");
    var ham = new Extra("ham", "Ham", new BigDecimal("8.00"), true, 1, true, "toppings", "Recipe");
    var unavailable =
        new Extra(
            "unavailable",
            "Unavailable",
            new BigDecimal("4.00"),
            false,
            0,
            true,
            "toppings",
            "Category");
    var beef =
        new Extra("beef", "Beef", new BigDecimal("15.00"), true, 1, false, "protein", "Recipe");
    var chicken =
        new Extra(
            "chicken", "Chicken", new BigDecimal("12.00"), true, 0, true, "protein", "Category");
    var protein =
        new Group(
            "protein",
            "Protein",
            1,
            1,
            "beef",
            List.of(
                new Choice("beef", "Beef", BigDecimal.ZERO, null, "beef", true, true),
                new Choice("chicken", "Chicken", BigDecimal.ZERO, null, "chicken", false, true)));
    var pizza =
        new Product(
            PIZZA,
            "Pizza",
            "pizza",
            1,
            new BigDecimal("50.00"),
            true,
            List.of(cheese, ham, unavailable),
            List.of(),
            null);
    var burger =
        new Product(
            BURGER,
            "Burger",
            "burger",
            1,
            new BigDecimal("60.00"),
            true,
            List.of(cheese, beef, chicken),
            List.of(protein),
            null);
    var drink =
        new Product(
            DRINK, "Drink", "drinks", 1, new BigDecimal("15.00"), true, List.of(), List.of(), null);
    var combo =
        new Product(
            COMBO,
            "Meal",
            "combos",
            1,
            new BigDecimal("90.00"),
            true,
            List.of(),
            List.of(),
            new Combo(
                new BigDecimal("90.00"),
                List.of(
                    new Slot("pizza", "Pizza", 1, PIZZA, Map.of(PIZZA, BigDecimal.ZERO)),
                    new Slot("burger", "Burger", 1, BURGER, Map.of(BURGER, BigDecimal.ZERO)))));
    return new Catalog(
        1,
        Map.of(PIZZA, pizza, BURGER, burger, DRINK, drink, COMBO, combo),
        Map.of(
            "cheese",
            cheese,
            "ham",
            ham,
            "beef",
            beef,
            "chicken",
            chicken,
            "unavailable",
            unavailable),
        Map.of(),
        new ItemCustomization.Rules(
            Map.of(), Map.of("cheese", "toppings", "ham", "toppings"), Map.of(), Map.of()),
        false);
  }

  static ToppingDraft draft(Catalog c, UUID id) {
    var request = new QuoteRequest(id, 1, 1, Selection.empty(c.revision()));
    return new ToppingDraft(c, request, ItemCustomization.quote(c, request).selection());
  }

  @Test
  void paidExtrasAreRemovedFirstAndIncludedPortionsAreRestoredFree() {
    var c = catalog();
    var d = draft(c, PIZZA);
    var target = d.targets().getFirst();
    assertTrue(d.tap(target, "cheese", false));
    assertTrue(d.tap(target, "cheese", false));
    assertEquals(new BigDecimal("60.00"), ItemCustomization.quote(c, d.request()).total());
    assertTrue(d.tap(target, "cheese", true));
    assertEquals(1, d.request().selection().additions().get("cheese"));
    assertTrue(d.request().selection().removals().isEmpty());
    assertTrue(d.tap(target, "cheese", true));
    assertTrue(d.tap(target, "cheese", true));
    assertEquals(1, d.request().selection().removals().get("cheese"));
    assertTrue(d.tap(target, "cheese", false));
    assertTrue(d.request().selection().additions().isEmpty());
    assertTrue(d.request().selection().removals().isEmpty());
    assertEquals(new BigDecimal("50.00"), ItemCustomization.quote(c, d.request()).total());
    assertTrue(d.tap(target, "cheese", true));
    assertTrue(d.tap(target, "cheese", true));
    assertFalse(d.canTap(target, "cheese", true));
    assertFalse(d.canTap(target, "unavailable", false));
  }

  @Test
  void compatibleFamiliesProtectedProteinsAndEmptyProductsArePreserved() {
    var c = catalog();
    var d = draft(c, BURGER);
    var target = d.targets().getFirst();
    assertFalse(d.canTap(target, "ham", false));
    assertFalse(d.canTap(target, "beef", true));
    assertTrue(d.tap(target, "beef", false));
    assertTrue(d.tap(target, "beef", true));
    assertFalse(d.canTap(target, "beef", true));
    var selection =
        new Selection(
            1, Map.of(), Map.of(), List.of(), Map.of("protein", List.of("chicken")), List.of());
    var r = new QuoteRequest(BURGER, 1, 1, selection);
    var chicken = new ToppingDraft(c, r, ItemCustomization.quote(c, r).selection());
    assertFalse(chicken.canTap(chicken.targets().getFirst(), "chicken", true));
    assertTrue(draft(c, DRINK).targets().isEmpty());
  }

  @Test
  void comboEditsRemainInsideOnlyTheChosenComponentAndQuoteAsAWhole() {
    var c = catalog();
    var d = draft(c, COMBO);
    var before = d.request().selection();
    assertTrue(d.combo());
    assertEquals(2, d.targets().size());
    var pizza =
        d.targets().stream()
            .filter(t -> t.product().priceId().equals(PIZZA))
            .findFirst()
            .orElseThrow();
    var burger =
        d.targets().stream()
            .filter(t -> t.product().priceId().equals(BURGER))
            .findFirst()
            .orElseThrow();
    var originalBurger = before.components().get(burger.index());
    d.tap(pizza, "ham", false);
    assertEquals(originalBurger, d.request().selection().components().get(burger.index()));
    assertTrue(d.request().selection().additions().isEmpty());
    assertEquals(new BigDecimal("98.00"), ItemCustomization.quote(c, d.request()).total());
  }

  @Test
  void existingSwapsSurviveOtherEditsAndRemovingSwappedPortionsDoesNotRestoreTheSource() {
    var c = catalog();
    var original =
        new Selection(
            1, Map.of(), Map.of(), List.of(new Swap("cheese", "ham", 1)), Map.of(), List.of());
    var request = new QuoteRequest(PIZZA, 1, 1, original);
    var d = new ToppingDraft(c, request, ItemCustomization.quote(c, request).selection());
    var target = d.targets().getFirst();
    d.tap(target, "cheese", false);
    assertEquals(original.swaps(), d.request().selection().swaps());
    d.tap(target, "ham", true);
    d.tap(target, "ham", true);
    assertTrue(d.request().selection().swaps().isEmpty());
    assertEquals(1, d.request().selection().removals().get("cheese"));
    ItemCustomization.quote(c, d.request());
  }
}
