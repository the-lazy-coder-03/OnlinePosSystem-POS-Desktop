package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import za.co.onlinepos.contracts.ProductEdits;

/** A draft-only recipe component. No network calls or independent persistence. */
final class ProductIngredientPicker extends VBox implements AutoCloseable {
  private Dialog<ButtonType> activeDialog;
  private boolean closed;
  private final List<JsonNode> ingredients = new ArrayList<>();
  private final Set<String> selected = new LinkedHashSet<>(), restored = new LinkedHashSet<>();
  private final Map<String, Map<String, Object>> properties = new LinkedHashMap<>();
  private final Runnable changed;
  private final Supplier<JsonNode> create;
  private final String family;
  private final java.util.function.Consumer<JsonNode> discardCreated;
  private final VBox linked = new VBox(10);

  ProductIngredientPicker(JsonNode view, Runnable changed, Supplier<JsonNode> create) {
    this(view, changed, create, row -> {});
  }

  ProductIngredientPicker(
      JsonNode view,
      Runnable changed,
      Supplier<JsonNode> create,
      java.util.function.Consumer<JsonNode> discardCreated) {
    super(12);
    this.discardCreated = discardCreated;
    this.family = view.path("family").asText("ingredient");
    this.changed = changed;
    this.create = create;
    view.path("ingredients")
        .forEach(
            r -> {
              ingredients.add(r);
              if (r.path("selected").asBoolean()) selected.add(r.path("key").asText());
            });
    var edit = new Button("Edit Ingredients");
    edit.setId("product-edit-ingredients");
    edit.setOnAction(e -> picker());
    getChildren().addAll(new Label("Ingredients included in this product"), edit, linked);
    render();
  }

  ProductEdits.Recipe draft() {
    var props = new LinkedHashMap<String, Map<String, Object>>();
    properties.forEach(
        (key, value) -> {
          if (selected.contains(key)) props.put(key, value);
        });
    return new ProductEdits.Recipe(family, List.copyOf(selected), props, List.copyOf(restored));
  }

  private void render() {
    linked.getChildren().clear();
    for (var ingredient : ingredients) {
      String key = ingredient.path("key").asText();
      boolean inherited = false, overridden = false;
      var values = new LinkedHashMap<String, Object>();
      for (var origin : ingredient.path("origins")) {
        String table = origin.path("table").asText();
        if (restored.contains(key)
            && (table.equals("burger_item_component_override")
                || table.equals("burger_item_default_component"))) continue;
        inherited |= table.equals("burger_recipe_component");
        overridden |= table.equals("burger_item_component_override");
        for (String prop : List.of("default_qty", "is_removable", "sort_order"))
          if (origin.path("properties").hasNonNull(prop))
            values.put(
                prop,
                za.co.onlinepos.installation.JsonFiles.JSON.convertValue(
                    origin.path("properties").get(prop), Object.class));
      }
      if (!selected.contains(key) && !inherited && !overridden) continue;
      values.putAll(properties.getOrDefault(key, Map.of()));
      String origin = inherited ? overridden ? "Overridden" : "Inherited" : "Product-specific";
      var title =
          new Label(
              ingredient.path("name").asText()
                  + " · "
                  + (selected.contains(key) ? origin : "Excluded")
                  + (ingredient.path("available").asBoolean() ? "" : " · Unavailable"));
      var row = new VBox(6, title);
      var fields = new HBox(12);
      if (selected.contains(key)) {
        if (!key.startsWith("burger:") && !key.startsWith("salad:")) {
          var qty =
              new Spinner<Integer>(
                  1, 99, ((Number) values.getOrDefault("default_qty", 1)).intValue());
          qty.setPrefWidth(90);
          qty.setAccessibleText("Included quantity for " + ingredient.path("name").asText());
          qty.valueProperty().addListener((o, a, b) -> property(key, "default_qty", b));
          fields.getChildren().addAll(new Label("Included quantity"), qty);
        }
        if (!key.startsWith("salad:")) {
          var removable = new CheckBox("Customer may remove");
          removable.setSelected((Boolean) values.getOrDefault("is_removable", true));
          removable.setOnAction(e -> property(key, "is_removable", removable.isSelected()));
          var order =
              new Spinner<Integer>(
                  -100000, 100000, ((Number) values.getOrDefault("sort_order", 0)).intValue());
          order.setPrefWidth(100);
          order.setAccessibleText("Display order");
          order.valueProperty().addListener((o, a, b) -> property(key, "sort_order", b));
          fields.getChildren().addAll(removable, new Label("Order"), order);
        }
      }
      final boolean hasShared = inherited;
      if (inherited || overridden) {
        var restore = new Button("Restore Inherited");
        restore.setOnAction(
            e -> {
              restored.add(key);
              properties.remove(key);
              if (hasShared) selected.add(key);
              else selected.remove(key);
              changed.run();
              render();
            });
        fields.getChildren().add(restore);
      }
      row.getChildren().add(fields);
      linked.getChildren().add(row);
    }
    if (linked.getChildren().isEmpty())
      linked
          .getChildren()
          .add(new Label("No ingredients linked. Use Edit Ingredients to select several at once."));
  }

  private void property(String key, String field, Object value) {
    restored.remove(key);
    properties.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(field, value);
    changed.run();
  }

  private void picker() {
    var pending = new LinkedHashSet<>(selected);
    var added = new ArrayList<JsonNode>();
    var dialog = new Dialog<ButtonType>();
    activeDialog = dialog;
    dialog.setTitle("Add / Remove Ingredients");
    if (getScene() != null) dialog.initOwner(getScene().getWindow());
    var apply = new ButtonType("Apply to Product", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
    var search = new TextField();
    search.setPromptText("Search ingredients");
    search.setId("ingredient-picker-search");
    var filter = new ComboBox<String>();
    filter.getItems().setAll("All", "Selected", "Available");
    filter.setValue("All");
    var typeFilter = new ComboBox<String>();
    typeFilter.getItems().add("All ingredient types");
    ingredients.stream()
        .map(r -> r.path("type").asText("ingredient"))
        .distinct()
        .sorted()
        .forEach(typeFilter.getItems()::add);
    typeFilter.getSelectionModel().selectFirst();
    var rows = new VBox(8);
    var count = new Label();
    Runnable render =
        () -> {
          rows.getChildren().clear();
          for (var row : ingredients) {
            String key = row.path("key").asText();
            if (!typeFilter.getValue().equals("All ingredient types")
                && !typeFilter.getValue().equals(row.path("type").asText("ingredient"))) continue;
            if (!row.path("name")
                .asText()
                .toLowerCase(java.util.Locale.ROOT)
                .contains(search.getText().toLowerCase(java.util.Locale.ROOT))) continue;
            if ("Selected".equals(filter.getValue()) && !pending.contains(key)) continue;
            if ("Available".equals(filter.getValue()) && !row.path("available").asBoolean())
              continue;
            var check =
                new CheckBox(
                    row.path("name").asText()
                        + (row.path("available").asBoolean() ? "" : " · Unavailable"));
            check.setUserData(key);
            check.setSelected(pending.contains(key));
            check.setDisable(
                !pending.contains(key)
                    && (!row.path("available").asBoolean() || !row.path("compatible").asBoolean()));
            check.setOnAction(
                e -> {
                  if (check.isSelected()) pending.add(key);
                  else pending.remove(key);
                  count.setText(pending.size() + " selected · Unsaved selection");
                });
            rows.getChildren().add(check);
          }
          count.setText(pending.size() + " selected · Unsaved selection");
        };
    search.textProperty().addListener((o, a, b) -> render.run());
    filter.valueProperty().addListener((o, a, b) -> render.run());
    typeFilter.valueProperty().addListener((o, a, b) -> render.run());
    var all = new Button("Select All Shown");
    all.setOnAction(
        e -> {
          for (var node : rows.getChildren())
            if (node instanceof CheckBox check && !check.isDisabled())
              pending.add(check.getUserData().toString());
          render.run();
        });
    var clear = new Button("Clear Shown");
    clear.setOnAction(
        e -> {
          rows.getChildren().forEach(n -> pending.remove(n.getUserData().toString()));
          render.run();
        });
    var add = new Button("Add New Ingredient");
    add.setOnAction(
        e -> {
          var row = create.get();
          if (row != null) {
            ingredients.add(row);
            added.add(row);
            pending.add(row.path("key").asText());
            render.run();
          }
        });
    var scroll = new ScrollPane(rows);
    scroll.setFitToWidth(true);
    scroll.setPrefViewportHeight(360);
    dialog
        .getDialogPane()
        .setContent(
            new VBox(
                12,
                new javafx.scene.layout.FlowPane(10, 10, search, filter, typeFilter),
                new HBox(10, all, clear, add),
                count,
                scroll,
                new Label(
                    "Apply stages this selection. Save Product commits all sections together.")));
    dialog.getDialogPane().setPrefWidth(650);
    render.run();
    var answer = dialog.showAndWait().orElse(ButtonType.CANCEL);
    activeDialog = null;
    if (closed) return;
    if (answer == apply) {
      var difference = new LinkedHashSet<>(pending);
      difference.addAll(selected);
      difference.removeIf(k -> pending.contains(k) == selected.contains(k));
      restored.removeAll(difference);
      selected.clear();
      selected.addAll(pending);
      changed.run();
      render();
    } else {
      ingredients.removeAll(added);
      added.forEach(discardCreated);
    }
  }

  @Override
  public void close() {
    closed = true;
    if (activeDialog != null) {
      activeDialog.setResult(ButtonType.CANCEL);
      activeDialog.close();
    }
  }
}
