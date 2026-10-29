package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.contracts.Recipes;
import za.co.onlinepos.installation.JsonFiles;

/** One product-first editor shared by catalogue records and the customization workbench. */
final class RecipeEditor extends VBox implements AutoCloseable {
  private final ModuleGateway gateway;
  private final ExecutorService executor;
  private final String token;
  private final String resource;
  private final Runnable saved;
  private final TextField search = new TextField();
  private final CheckBox selectedFirst = new CheckBox("Selected first");
  private final Label feedback = new Label(), count = new Label();
  private final VBox rows = new VBox(8);
  private final Button save = new Button("Save Recipe"),
      reload = new Button("Reload / discard"),
      create = new Button("New Ingredient"),
      copy = new Button("Copy Recipe");
  private final ComboBox<String> family = new ComboBox<>();
  private final List<JsonNode> ingredients = new ArrayList<>();
  private final LinkedHashSet<String> selected = new LinkedHashSet<>(),
      original = new LinkedHashSet<>();
  private final Map<String, Map<String, Object>> edits = new LinkedHashMap<>();
  private final Map<String, String> sortText = new LinkedHashMap<>();
  private final java.util.Set<String> invalidSort = new java.util.HashSet<>();
  private JsonNode view, state;
  private boolean busy, closed, dirty, filling, conflict;
  private Recipes.Save pending;
  private long generation;
  private String savedNotice = "";
  private Label dialogMessage;
  private java.util.function.Consumer<Boolean> dirtyListener = value -> {};

  void onDirty(java.util.function.Consumer<Boolean> listener) {
    dirtyListener = listener;
  }

  boolean hasChanges() {
    return dirty;
  }

  private javafx.scene.control.Dialog<?> dialog;

  RecipeEditor(
      ModuleGateway gateway,
      ExecutorService executor,
      String token,
      Recipes.Product product,
      Runnable saved) {
    this(gateway, executor, token, product.kind() + "/" + product.id(), saved);
  }

  RecipeEditor(
      ModuleGateway gateway, ExecutorService executor, String token, UUID priceId, Runnable saved) {
    this(gateway, executor, token, "price/" + priceId, saved);
  }

  private RecipeEditor(
      ModuleGateway gateway,
      ExecutorService executor,
      String token,
      String resource,
      Runnable saved) {
    this.gateway = gateway;
    this.executor = executor;
    this.token = token;
    this.resource = resource;
    this.saved = saved;
    setPadding(new Insets(12));
    setSpacing(10);
    setId("recipe-editor");
    search.setPromptText("Search ingredients");
    search.setId("recipe-search");
    search.textProperty().addListener((o, a, b) -> render());
    selectedFirst.selectedProperty().addListener((o, a, b) -> render());
    family.getItems().setAll("ingredient", "burger");
    family.setId("recipe-family");
    family.setOnAction(
        e -> {
          if (!filling && view != null) {
            if (confirmClose()) load(false);
            else {
              filling = true;
              family.setValue(view.path("family").asText());
              filling = false;
            }
          }
        });
    var all = new Button("Select Visible");
    all.setId("recipe-select-visible");
    all.setOnAction(
        e -> {
          for (var row : visible())
            if (row.path("available").asBoolean() && row.path("compatible").asBoolean())
              selected.add(row.path("key").asText());
          changed();
          render();
        });
    var clear = new Button("Clear Visible");
    clear.setId("recipe-clear-visible");
    clear.setOnAction(
        e -> {
          visible().forEach(r -> selected.remove(r.path("key").asText()));
          changed();
          render();
        });
    save.setId("recipe-save");
    save.setOnAction(e -> save());
    reload.setOnAction(
        e -> {
          if (confirmClose()) load(false);
        });
    var cancel = new Button("Cancel");
    cancel.setId("recipe-cancel");
    cancel.setOnAction(
        e -> {
          if (confirmClose()) load(false);
        });
    create.setOnAction(e -> newIngredient());
    copy.setOnAction(e -> copy());
    feedback.setWrapText(true);
    feedback.setId("recipe-feedback");
    var scroll = new ScrollPane(rows);
    scroll.setFitToWidth(true);
    scroll.setPrefViewportHeight(380);
    getChildren()
        .addAll(
            new Label("Ingredients / Recipe"),
            new Label(
                "Recipe portions are included. Removing ingredients does not discount the price."),
            new FlowPane(10, 10, search, selectedFirst, family, all, clear, count),
            scroll,
            new FlowPane(10, 10, create, copy, save, cancel, reload),
            feedback);
    load(false);
  }

  private Recipes.Product product() {
    var p = view.path("product");
    return new Recipes.Product(p.path("kind").asText(), p.path("id").asLong());
  }

  private List<JsonNode> visible() {
    String q = search.getText().strip().toLowerCase(java.util.Locale.ROOT);
    return ingredients.stream()
        .filter(r -> r.path("name").asText().toLowerCase(java.util.Locale.ROOT).contains(q))
        .sorted(
            Comparator.<JsonNode>comparingInt(
                    r ->
                        selectedFirst.isSelected() && selected.contains(r.path("key").asText())
                            ? 0
                            : 1)
                .thenComparing(r -> r.path("name").asText(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private void changed() {
    dirty = !selected.equals(original) || !edits.isEmpty() || !invalidSort.isEmpty();
    pending = null;
    dirtyListener.accept(dirty);
    feedback.setText(dirty ? "Unsaved recipe changes" : "");
    save.setDisable(
        busy || conflict || !dirty || view != null && !view.path("editable").asBoolean());
    count.setText(selected.size() + " selected");
  }

  private void render() {
    rows.getChildren().clear();
    if (view == null) return;
    boolean editable = view.path("editable").asBoolean();
    rows.setDisable(!editable);
    create.setDisable(!editable);
    copy.setDisable(!editable);
    for (var row : visible()) {
      String key = row.path("key").asText();
      var check =
          new CheckBox(
              row.path("name").asText()
                  + (!row.path("available").asBoolean() ? " — unavailable" : ""));
      check.setId("recipe-ingredient-" + key.replace(':', '-'));
      check.setSelected(selected.contains(key));
      check.setDisable(
          busy
              || !row.path("available").asBoolean() && !original.contains(key)
              || !row.path("compatible").asBoolean() && !original.contains(key));
      check.setOnAction(
          e -> {
            if (check.isSelected()) selected.add(key);
            else selected.remove(key);
            changed();
            render();
          });
      var line = new HBox(12, check);
      var origins = row.path("origins");
      String origin =
          origins.isEmpty()
              ? "New link"
              : java.util.stream.StreamSupport.stream(origins.spliterator(), false)
                  .map(
                      r ->
                          switch (r.path("table").asText()) {
                            case "burger_recipe_component" -> "Shared recipe";
                            case "burger_item_default_component" -> "Item addition";
                            case "salad_ingredients" -> "Existing salad link";
                            default -> "Product recipe";
                          })
                  .distinct()
                  .collect(java.util.stream.Collectors.joining(" + "));
      line.getChildren().add(new Label(origin));
      if (selected.contains(key) && !key.startsWith("salad:")) {
        JsonNode properties =
            origins.isEmpty()
                ? JsonFiles.JSON.createObjectNode()
                : origins.get(0).path("properties");
        var staged = edits.getOrDefault(key, Map.of());
        if (key.startsWith("ingredient:")) {
          int qty =
              staged.containsKey("default_qty")
                  ? ((Number) staged.get("default_qty")).intValue()
                  : properties.path("default_qty").asInt(1);
          var quantity = new Spinner<Integer>(1, 99, Math.max(1, Math.min(99, qty)));
          quantity.setPrefWidth(85);
          quantity.setAccessibleText("Included quantity for " + row.path("name").asText());
          quantity.valueProperty().addListener((o, a, b) -> property(key, "default_qty", b));
          line.getChildren().addAll(new Label("Included"), quantity);
        }
        var removable = new CheckBox("Removable");
        removable.setSelected(
            (Boolean)
                staged.getOrDefault(
                    "is_removable", properties.path("is_removable").asBoolean(true)));
        removable.setOnAction(e -> property(key, "is_removable", removable.isSelected()));
        line.getChildren().add(removable);
        var order =
            new TextField(
                sortText.getOrDefault(
                    key,
                    staged
                        .getOrDefault("sort_order", properties.path("sort_order").asInt(0))
                        .toString()));
        order.setPrefWidth(60);
        order.setPromptText("Order");
        order
            .textProperty()
            .addListener(
                (o, a, b) -> {
                  sortText.put(key, b);
                  try {
                    int number = Integer.parseInt(b.strip());
                    invalidSort.remove(key);
                    property(key, "sort_order", number);
                  } catch (NumberFormatException bad) {
                    invalidSort.add(key);
                    dirty = true;
                    pending = null;
                    feedback.setText("Display order must be an integer");
                    save.setDisable(true);
                  }
                });
        line.getChildren().addAll(new Label("Order"), order);
      }
      rows.getChildren().add(line);
    }
    if (rows.getChildren().isEmpty())
      rows.getChildren()
          .add(new Label("No matching ingredients. Create an ingredient or change the search."));
    count.setText(selected.size() + " selected");
    save.setDisable(
        busy || conflict || !dirty || view != null && !view.path("editable").asBoolean());
  }

  private void property(String key, String field, Object value) {
    edits.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(field, value);
    changed();
  }

  private void task(
      java.util.concurrent.Callable<JsonNode> work, java.util.function.Consumer<JsonNode> done) {
    if (busy || closed) return;
    busy = true;
    feedback.setText("Loading / saving recipe…");
    setDisable(true);
    long attempt = ++generation;
    if (dialog != null && dialog.isShowing()) dialog.getDialogPane().setDisable(true);
    executor.execute(
        () -> {
          try {
            var result = work.call();
            Platform.runLater(
                () -> {
                  if (closed || attempt != generation) return;
                  busy = false;
                  setDisable(false);
                  if (dialog != null) dialog.getDialogPane().setDisable(false);
                  done.accept(result);
                });
          } catch (Exception failure) {
            Platform.runLater(
                () -> {
                  if (closed || attempt != generation) return;
                  busy = false;
                  setDisable(false);
                  if (dialog != null) {
                    dialog.getDialogPane().setDisable(false);
                    dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(false);
                  }
                  int status =
                      failure
                              instanceof
                              za.co.onlinepos.installation.TrustedStoreClient.ModuleFailure f
                          ? f.status()
                          : failure instanceof DevGateway.Failure f ? f.status : 503;
                  conflict = status == 409;
                  if (conflict) pending = null;
                  feedback.setText(
                      conflict
                          ? "Catalogue changed. Reload and review before saving."
                          : status == 400
                              ? "Invalid recipe. Check selections and properties; no changes were saved."
                              : "Save not confirmed. Your recipe edits are retained; retry when connected.");
                  if (dialogMessage != null && dialog != null && dialog.isShowing())
                    dialogMessage.setText(feedback.getText());
                  save.setDisable(conflict || !dirty);
                });
          }
        });
  }

  private void load(boolean preserve) {
    String f = family.getValue();
    long expected = preserve && state != null ? state.path("revision").asLong() : -1;
    task(
        () -> {
          var next =
              gateway.catalogue(
                  "recipes/" + resource + (f == null ? "" : "?family=" + f), "GET", null, token);
          var snapshot = gateway.catalogue("state", "GET", null, token);
          if (snapshot.path("revision").asLong() != next.path("revision").asLong()
              || (expected >= 0 && next.path("revision").asLong() != expected))
            throw new za.co.onlinepos.installation.TrustedStoreClient.ModuleFailure(409);
          state = snapshot;
          return next;
        },
        next -> {
          view = next;
          if (next.hasNonNull("productName"))
            ((Label) getChildren().getFirst())
                .setText("Ingredients / Recipe — " + next.path("productName").asText());
          conflict = false;
          ingredients.clear();
          next.path("ingredients").forEach(ingredients::add);
          original.clear();
          ingredients.stream()
              .filter(r -> r.path("selected").asBoolean())
              .forEach(r -> original.add(r.path("key").asText()));
          if (!preserve) {
            selected.clear();
            selected.addAll(original);
            edits.clear();
            sortText.clear();
            invalidSort.clear();
            pending = null;
            dirty = false;
          }
          filling = true;
          family.setValue(next.path("family").asText());
          family.setDisable(product().kind().equals("pizza"));
          filling = false;
          dirtyListener.accept(dirty);
          feedback.setText(
              preserve ? "Ingredient saved. Recipe changes remain staged." : savedNotice);
          render();
        });
  }

  private void save() {
    if (view == null || !view.path("editable").asBoolean() || !dirty || conflict) return;
    if (invalidSort.stream().anyMatch(selected::contains)) {
      feedback.setText("Display order must be an integer.");
      return;
    }
    boolean shared = false;
    if (pending == null) {
      var properties = new LinkedHashMap<String, Map<String, Object>>();
      edits.forEach(
          (k, v) -> {
            if (selected.contains(k)) properties.put(k, new LinkedHashMap<>(v));
          });
      pending =
          new Recipes.Save(
              UUID.randomUUID(),
              view.path("revision").asLong(),
              product(),
              view.path("family").asText(),
              List.copyOf(selected),
              properties,
              shared);
    }
    var command = pending;
    task(
        () -> gateway.catalogue("recipes", "PUT", command, token),
        result -> {
          dirty = false;
          pending = null;
          edits.clear();
          savedNotice = "Recipe saved on the Master.";
          feedback.setText(savedNotice);
          saved.run();
          if (!closed) load(false);
        });
  }

  private boolean confirm(String title, String message) {
    var alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
    alert.setHeaderText(title);
    if (getScene() != null) alert.initOwner(getScene().getWindow());
    dialog = alert;
    boolean accepted = alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    dialog = null;
    return accepted;
  }

  boolean confirmClose() {
    return !busy && (!dirty || confirm("Unsaved recipe", "Discard the staged recipe changes?"));
  }

  private void newIngredient() {
    if (view == null || !view.path("editable").asBoolean()) return;
    boolean burger = view.path("family").asText().equals("burger");
    var name = new TextField();
    var category = new ComboBox<JsonNode>();
    var type = new ComboBox<String>();
    type.getItems().setAll("default_topping", "protein", "extra_topping");
    type.setValue("default_topping");
    for (var r : state.path("tables").path("price_category")) category.getItems().add(r);
    category.setConverter(
        new javafx.util.StringConverter<>() {
          public String toString(JsonNode n) {
            return n == null ? "" : n.path("name").asText();
          }

          public JsonNode fromString(String s) {
            return null;
          }
        });
    if (!category.getItems().isEmpty()) category.getSelectionModel().selectFirst();
    var pendingIngredient =
        new java.util.concurrent.atomic.AtomicReference<CatalogueManagement.Edit>();
    name.textProperty().addListener((o, a, b) -> pendingIngredient.set(null));
    category.valueProperty().addListener((o, a, b) -> pendingIngredient.set(null));
    type.valueProperty().addListener((o, a, b) -> pendingIngredient.set(null));
    var popup = new javafx.scene.control.Dialog<ButtonType>();
    popup.setTitle("New Ingredient");
    popup.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    var error = new Label();
    dialogMessage = error;
    var fields =
        new VBox(
            10,
            new Label("Ingredient name"),
            name,
            new Label(burger ? "Component type" : "Price category"),
            burger ? type : category,
            error);
    popup.getDialogPane().setContent(fields);
    var newCategory = new Button("New price category");
    if (!burger) {
      fields.getChildren().add(newCategory);
      newCategory.setOnAction(
          e -> {
            var input = new javafx.scene.control.TextInputDialog();
            input.setHeaderText("Price category name");
            input
                .showAndWait()
                .filter(s -> !s.isBlank())
                .ifPresent(
                    label -> {
                      var change =
                          new CatalogueManagement.Change(
                              "price_category",
                              null,
                              Map.of(
                                  "name",
                                  label,
                                  "price_category_id",
                                  0,
                                  "sort_order",
                                  0,
                                  "active",
                                  true),
                              false);
                      task(
                          () -> {
                            gateway.catalogue(
                                "records",
                                "PUT",
                                new CatalogueManagement.Edit(
                                    UUID.randomUUID(),
                                    state.path("revision").asLong(),
                                    List.of(change),
                                    false),
                                token);
                            return gateway.catalogue("state", "GET", null, token);
                          },
                          next -> {
                            state = next;
                            category.getItems().clear();
                            next.path("tables")
                                .path("price_category")
                                .forEach(category.getItems()::add);
                            category.getItems().stream()
                                .filter(r -> r.path("name").asText().equals(label))
                                .findFirst()
                                .ifPresent(category::setValue);
                            view =
                                ((com.fasterxml.jackson.databind.node.ObjectNode) view)
                                    .put("revision", next.path("revision").asLong());
                          });
                    });
          });
    }
    popup
        .getDialogPane()
        .lookupButton(ButtonType.OK)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            event -> {
              event.consume();
              if (name.getText().isBlank() || !burger && category.getValue() == null) {
                error.setText("Enter a name and required price category.");
                return;
              }
              var values = new LinkedHashMap<String, Object>();
              values.put("name", name.getText().strip());
              values.put("active", true);
              values.put("seasonal", false);
              values.put(burger ? "component_id" : "ingredient_id", 0);
              if (burger) values.put("component_type", type.getValue());
              else
                values.put(
                    "price_category_id", category.getValue().path("price_category_id").asLong());
              if (pendingIngredient.get() == null)
                pendingIngredient.set(
                    new CatalogueManagement.Edit(
                        UUID.randomUUID(),
                        view.path("revision").asLong(),
                        List.of(
                            new CatalogueManagement.Change(
                                burger ? "burger_component" : "ingredient", null, values, false)),
                        false));
              var command = pendingIngredient.get();
              popup.getDialogPane().lookupButton(ButtonType.OK).setDisable(true);
              task(
                  () -> {
                    var result = gateway.catalogue("records", "PUT", command, token);
                    var snapshot = gateway.catalogue("state", "GET", null, token);
                    if (result.path("revision").asLong() != snapshot.path("revision").asLong())
                      throw new za.co.onlinepos.installation.TrustedStoreClient.ModuleFailure(409);
                    return snapshot;
                  },
                  next -> {
                    state = next;
                    String table = burger ? "burger_component" : "ingredient";
                    for (var r : next.path("tables").path(table))
                      if (r.path("name").asText().equals(name.getText().strip()))
                        selected.add(
                            (burger ? "burger:" : "ingredient:")
                                + r.path(burger ? "component_id" : "ingredient_id").asLong());
                    dirty = true;
                    pending = null;
                    popup.close();
                    load(true);
                  });
            });
    dialog = popup;
    popup.show();
  }

  private String names(java.util.Collection<String> keys) {
    return keys.stream()
        .map(
            key ->
                ingredients.stream()
                    .filter(r -> r.path("key").asText().equals(key))
                    .findFirst()
                    .map(r -> r.path("name").asText())
                    .orElse(key))
        .collect(java.util.stream.Collectors.joining(", "));
  }

  private record Source(String name, Recipes.Product product) {
    @Override
    public String toString() {
      return name;
    }
  }

  private void copy() {
    var choices = new ComboBox<Source>();
    for (String kind : List.of("pizza", "menu_item"))
      for (var row : state.path("tables").path(kind)) {
        var source =
            new Recipes.Product(kind, row.path(kind.equals("pizza") ? "pizza_id" : "id").asLong());
        if (!source.equals(product()))
          choices.getItems().add(new Source(row.path("name").asText() + " (" + kind + ")", source));
      }
    var popup = new javafx.scene.control.Dialog<ButtonType>();
    popup.setTitle("Copy Recipe");
    popup.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    popup.getDialogPane().setContent(choices);
    dialog = popup;
    popup
        .showAndWait()
        .filter(b -> b == ButtonType.OK)
        .ifPresent(
            b -> {
              if (choices.getValue() == null) return;
              var sourceProduct = choices.getValue().product();
              task(
                  () ->
                      gateway.catalogue(
                          "recipes/" + sourceProduct.kind() + "/" + sourceProduct.id(),
                          "GET",
                          null,
                          token),
                  source -> {
                    if (!source.path("family").equals(view.path("family"))) {
                      feedback.setText("Choose a recipe from the same ingredient family.");
                      return;
                    }
                    var next = new LinkedHashSet<String>();
                    var props = new LinkedHashMap<String, Map<String, Object>>();
                    for (var row : source.path("ingredients"))
                      if (row.path("selected").asBoolean()) {
                        String key = row.path("key").asText();
                        if (ingredients.stream()
                            .noneMatch(r -> r.path("key").asText().equals(key))) {
                          feedback.setText(
                              "This recipe contains product-specific links that cannot be copied.");
                          return;
                        }
                        next.add(key);
                        var fields = new LinkedHashMap<String, Object>();
                        var old = row.path("origins").get(0).path("properties");
                        for (String field : List.of("default_qty", "is_removable", "sort_order"))
                          if (old.has(field))
                            fields.put(
                                field, JsonFiles.JSON.convertValue(old.get(field), Object.class));
                        props.put(key, fields);
                      }
                    var added = new LinkedHashSet<>(next);
                    added.removeAll(selected);
                    var removed = new LinkedHashSet<>(selected);
                    removed.removeAll(next);
                    if (confirm(
                        "Replace destination recipe?",
                        "Add: "
                            + names(added)
                            + "\nRemove: "
                            + names(removed)
                            + "\nCopy supported properties for "
                            + next.size()
                            + " ingredients. Save Recipe commits this change.")) {
                      selected.clear();
                      selected.addAll(next);
                      edits.clear();
                      edits.putAll(props);
                      changed();
                      render();
                    }
                  });
            });
    dialog = null;
  }

  @Override
  public void close() {
    closed = true;
    ++generation;
    if (dialog != null) dialog.close();
    selected.clear();
    ingredients.clear();
    edits.clear();
  }
}
