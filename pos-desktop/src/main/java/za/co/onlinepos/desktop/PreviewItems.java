package za.co.onlinepos.desktop;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.ItemCustomization.Catalog;
import za.co.onlinepos.contracts.ItemCustomization.Choice;
import za.co.onlinepos.contracts.ItemCustomization.Combo;
import za.co.onlinepos.contracts.ItemCustomization.Extra;
import za.co.onlinepos.contracts.ItemCustomization.Group;
import za.co.onlinepos.contracts.ItemCustomization.Product;
import za.co.onlinepos.contracts.ItemCustomization.Rules;
import za.co.onlinepos.contracts.ItemCustomization.Slot;
import za.co.onlinepos.installation.JsonFiles;

/** Fictional, memory-only item setup playground. No customer, audit or database records. */
final class PreviewItems {
  private Catalog catalog;

  static UUID id(int n) {
    return new UUID(0, n);
  }

  PreviewItems() {
    var cheese =
        new Extra("cheese", "Cheese", new BigDecimal("5.00"), true, 1, true, "toppings", "Recipe");
    var bacon =
        new Extra("bacon", "Bacon", new BigDecimal("8.00"), true, 0, true, "toppings", "Category");
    var beef =
        new Extra(
            "beef", "Beef patty", new BigDecimal("15.00"), true, 1, false, "protein", "Recipe");
    var chicken =
        new Extra(
            "chicken",
            "Chicken fillet",
            new BigDecimal("15.00"),
            true,
            0,
            true,
            "protein",
            "Category");
    var extras = Map.of("cheese", cheese, "bacon", bacon, "beef", beef, "chicken", chicken);
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
    var products = new LinkedHashMap<UUID, Product>();
    products.put(
        id(1),
        new Product(
            id(1),
            "Sample burger",
            "burgers",
            0,
            new BigDecimal("60.00"),
            true,
            List.of(cheese, beef),
            List.of(protein),
            null));
    products.put(
        id(2),
        new Product(
            id(2),
            "Sample chips",
            "sides",
            0,
            new BigDecimal("20.00"),
            true,
            List.of(),
            List.of(),
            null));
    products.put(
        id(3),
        new Product(
            id(3),
            "Sample Coke",
            "drinks",
            0,
            new BigDecimal("18.00"),
            true,
            List.of(),
            List.of(),
            null));
    products.put(
        id(4),
        new Product(
            id(4),
            "Sample Red Bull",
            "drinks",
            0,
            new BigDecimal("35.00"),
            true,
            List.of(),
            List.of(),
            null));
    var slots =
        List.of(
            new Slot("burger", "Burger", 1, id(1), Map.of(id(1), BigDecimal.ZERO)),
            new Slot("chips", "Chips", 1, id(2), Map.of(id(2), BigDecimal.ZERO)),
            new Slot(
                "drink",
                "Drink",
                1,
                id(3),
                Map.of(id(3), BigDecimal.ZERO, id(4), new BigDecimal("12.00"))));
    products.put(
        id(5),
        new Product(
            id(5),
            "Sample burger meal",
            "combos",
            0,
            new BigDecimal("85.00"),
            true,
            List.of(),
            List.of(),
            new Combo(new BigDecimal("85.00"), slots)));
    var rules =
        new Rules(
            Map.of("burgers", List.of("bacon", "chicken")),
            Map.of(
                "cheese", "toppings", "bacon", "toppings", "beef", "protein", "chicken", "protein"),
            Map.of(),
            Map.of());
    catalog =
        new Catalog(
            0,
            products,
            extras,
            Map.of("burgers", "Burgers", "sides", "Sides", "drinks", "Drinks", "combos", "Combos"),
            rules,
            true);
  }

  synchronized com.fasterxml.jackson.databind.JsonNode request(
      String path, String method, Object payload) {
    if (path.equals("items") && method.equals("GET")) return JsonFiles.JSON.valueToTree(catalog);
    if (path.equals("items/rules") && payload instanceof ItemCustomization.Edit edit) {
      if (edit.expectedRevision() != catalog.revision())
        throw new IllegalArgumentException("Preview revision changed");
      var next =
          new Catalog(
              catalog.revision() + 1,
              catalog.products(),
              catalog.extras(),
              catalog.categories(),
              edit.rules(),
              true);
      ItemCustomization.validate(next);
      catalog = next;
      return JsonFiles.JSON.valueToTree(Map.of("revision", next.revision()));
    }
    if (path.equals("items/quote") && payload instanceof ItemCustomization.QuoteRequest request)
      return JsonFiles.JSON.valueToTree(ItemCustomization.quote(catalog, request));
    throw new IllegalStateException("Preview cannot save real drafts");
  }
}
