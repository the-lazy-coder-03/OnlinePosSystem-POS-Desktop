package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.ProductEdits;
import za.co.onlinepos.contracts.Recipes;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.TrustedStoreClient;

/** Product-owned workspace. Every section edits the same draft and uses one commit command. */
final class ProductWorkspace extends BorderPane implements AutoCloseable {
  private record Category(String kind, long id, String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  private record Product(String kind, JsonNode row) {
    long id() {
      return row.path(kind.equals("pizza") ? "pizza_id" : "id").asLong();
    }

    @Override
    public String toString() {
      return row.path("name").asText() + (row.path("active").asBoolean() ? "" : " · Inactive");
    }
  }

  private record Price(List<Long> key, UUID id) {}

  private final ModuleGateway gateway;
  private final ExecutorService executor;
  private final String token;
  private final TreeView<Category> categories = new TreeView<>();
  private final ListView<Product> products = new ListView<>();
  private final TextField search = new TextField();
  private final BorderPane editor = new BorderPane();
  private final TabPane tabs = new TabPane();
  private final Label feedback = new Label(),
      heading = new Label("Select a product"),
      status = new Label();
  private final Button save = new Button("Save Product"), cancel = new Button("Cancel");
  private final Map<String, CatalogueManagement.Change> changes = new LinkedHashMap<>();
  private final Map<String, Label> errors = new LinkedHashMap<>();
  private final List<Price> prices = new ArrayList<>();
  private final Map<UUID, ItemCustomization.Rules> variantRules = new LinkedHashMap<>();
  private final List<ItemWorkbench> workbenches = new ArrayList<>();
  private final List<Dialog<?>> dialogs = new ArrayList<>();
  private JsonNode state, originalState, recipeView;
  private ItemCustomization.Catalog catalog;
  private Product product;
  private ObjectNode details;
  private ProductIngredientPicker recipe;
  private String productRevision;
  private boolean dirty, busy, closed, filling, conflict;
  private boolean recipeDirty;
  private long nextDraft = -1;
  private ProductEdits.Save pending;
  private boolean combosOnly;
  private VBox modifierBox;
  private VBox pricingBox;
  private VBox comboBox;
  private Tab comboTab;

  ProductWorkspace(ModuleGateway gateway, ExecutorService executor, String token) {
    this.gateway = gateway;
    this.executor = executor;
    this.token = token;
    setId("product-workspace");
    getStyleClass().add("product-workspace");
    setPadding(new Insets(8));
    categories.setId("product-category-tree");
    categories.setPrefWidth(200);
    categories
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (!filling) filterProducts();
            });
    search.setPromptText("Search products");
    search.setId("product-search");
    search.textProperty().addListener((o, a, b) -> filterProducts());
    products.setId("product-list");
    products.setPrefWidth(230);
    products
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (filling || b == null || b == product) return;
              if (!confirmClose()) {
                filling = true;
                products.getSelectionModel().select(a);
                filling = false;
                return;
              }
              open(b);
            });
    var add = new Button("Add Product");
    add.setId("product-add");
    add.setOnAction(e -> addProduct());
    var reload = new Button("Reload");
    reload.setOnAction(
        e -> {
          if (confirmClose()) load();
        });
    var middle = new VBox(10, search, new HBox(8, add, reload), products);
    VBox.setVgrow(products, Priority.ALWAYS);
    var categoryAdd = new Button("Add Category");
    categoryAdd.setId("product-add-category");
    categoryAdd.setOnAction(e -> categoryDialog(null));
    var categoryEdit = new Button("Edit Category");
    categoryEdit.setId("product-edit-category");
    categoryEdit.setOnAction(
        e -> {
          var selected = categories.getSelectionModel().getSelectedItem();
          if (selected != null && selected.getValue().id() > 0) categoryDialog(selected.getValue());
        });
    var left = new VBox(10, new Label("Categories"), categories, categoryAdd, categoryEdit);
    VBox.setVgrow(categories, Priority.ALWAYS);
    left.getStyleClass().add("product-categories");
    middle.getStyleClass().add("product-browser");
    editor.getStyleClass().add("product-editor");
    var split = new SplitPane(left, middle, editor);
    split.setDividerPositions(.18, .41);
    setCenter(split);
    heading.getStyleClass().add("product-editor-title");
    heading.setWrapText(true);
    status.setId("product-unsaved");
    save.setId("product-save");
    save.getStyleClass().add("product-primary");
    save.setOnAction(e -> save());
    cancel.setId("product-cancel");
    cancel.setOnAction(
        e -> {
          if (confirmClose()) {
            if (product != null && product.id() > 0) open(product);
            else {
              state = originalState.deepCopy();
              clearEditor();
              filterProducts();
            }
          }
        });
    editor.setTop(new VBox(6, heading, status));
    editor.setCenter(tabs);
    feedback.setWrapText(true);
    feedback.setId("product-feedback");
    editor.setBottom(new VBox(8, feedback, new HBox(10, save, cancel)));
    tabs.setId("product-sections");
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    updateButtons();
    load();
  }

  void combosOnly(boolean value) {
    combosOnly = value;
    filterProducts();
  }

  private <T> void work(Callable<T> operation, Consumer<T> complete) {
    if (busy || closed) return;
    busy = true;
    updateButtons();
    categories.setDisable(true);
    products.setDisable(true);
    tabs.setDisable(true);
    feedback.setText("Working…");
    executor.execute(
        () -> {
          try {
            T value = operation.call();
            Platform.runLater(
                () -> {
                  if (closed) return;
                  unlock();
                  feedback.setText("");
                  complete.accept(value);
                });
          } catch (Exception failure) {
            Platform.runLater(
                () -> {
                  if (closed) return;
                  unlock();
                  int code =
                      failure instanceof TrustedStoreClient.ModuleFailure f ? f.status() : 503;
                  if (code == 409) conflict = true;
                  if (code == 400) pending = null;
                  feedback.setText(
                      code == 409
                          ? "This product or its dependencies changed. Your edits are retained. Reload and review before saving."
                          : code == 401 || code == 403
                              ? "Your session cannot edit this catalogue."
                              : code == 400
                                  ? "The product was not saved. Review the fields and selections in each section."
                                  : "Save was not confirmed. Your draft is retained; retry uses the same command.");
                  if (failure instanceof TrustedStoreClient.ModuleFailure f
                      && !f.section().isBlank()) {
                    feedback.setText(f.getMessage());
                    if (errors.containsKey(f.field()))
                      errors.get(f.field()).setText(f.getMessage());
                    tabs.getTabs().stream()
                        .filter(t -> t.getText().equals(f.section()))
                        .findFirst()
                        .ifPresent(t -> tabs.getSelectionModel().select(t));
                  }

                  updateButtons();
                });
          }
        });
  }

  private void unlock() {
    busy = false;
    categories.setDisable(false);
    products.setDisable(false);
    tabs.setDisable(state != null && !state.path("editable").asBoolean());
    updateButtons();
  }

  private void load() {
    work(
        () -> {
          var s = gateway.catalogue("state", "GET", null, token);
          var c = gateway.catalogue("items", "GET", null, token);
          if (s.path("revision").asLong() != c.path("revision").asLong())
            throw new TrustedStoreClient.ModuleFailure(409);
          return List.of(s, c);
        },
        loaded -> {
          state = loaded.get(0);
          originalState = state.deepCopy();
          catalog = decode(loaded.get(1), ItemCustomization.Catalog.class);
          clearEditor();
          tree();
          filterProducts();
        });
  }

  private List<JsonNode> rows(String table) {
    var rows = new ArrayList<JsonNode>();
    if (state != null) state.path("tables").path(table).forEach(rows::add);
    return rows;
  }

  private long id(JsonNode row, String table) {
    return row.path(
            table.equals("pizza_category")
                ? "pizza_category_id"
                : table.equals("pizza") ? "pizza_id" : "id")
        .asLong();
  }

  private void tree() {
    var previous = categories.getSelectionModel().getSelectedItem();
    Category selectedCategory = previous == null ? null : previous.getValue();
    filling = true;
    var root = new TreeItem<>(new Category("", 0, "All Products"));
    root.setExpanded(true);
    for (String table : List.of("menu_category", "pizza_category")) {
      var nodes = new LinkedHashMap<Long, TreeItem<Category>>();
      rows(table).stream()
          .filter(r -> !r.path("archived").asBoolean())
          .sorted(Comparator.comparingInt(r -> r.path("sort_order").asInt()))
          .forEach(
              row ->
                  nodes.put(
                      id(row, table),
                      new TreeItem<>(
                          new Category(table, id(row, table), row.path("name").asText()))));
      for (var row : rows(table))
        if (nodes.containsKey(id(row, table))) {
          var node = nodes.get(id(row, table));
          var parent = nodes.get(row.path("parent_id").asLong());
          if (parent == null) root.getChildren().add(node);
          else parent.getChildren().add(node);
        }
    }
    categories.setRoot(root);
    var match = findCategory(root, selectedCategory);
    categories.getSelectionModel().select(match == null ? root : match);
    if (match != null)
      for (var ancestor = match.getParent(); ancestor != null; ancestor = ancestor.getParent())
        ancestor.setExpanded(true);
    filling = false;
  }

  private TreeItem<Category> findCategory(TreeItem<Category> root, Category selected) {
    if (selected == null) return null;
    if (root.getValue().kind().equals(selected.kind()) && root.getValue().id() == selected.id())
      return root;
    for (var child : root.getChildren()) {
      var found = findCategory(child, selected);
      if (found != null) return found;
    }
    return null;
  }

  private void filterProducts() {
    if (state == null || filling) return;
    var selected = categories.getSelectionModel().getSelectedItem();
    Category category = selected == null ? null : selected.getValue();
    filling = true;
    products.getItems().clear();
    for (String kind : List.of("menu_item", "pizza"))
      for (var row : rows(kind)) {
        var p = new Product(kind, row);
        if (!p.toString()
            .toLowerCase(java.util.Locale.ROOT)
            .contains(search.getText().toLowerCase(java.util.Locale.ROOT))) continue;
        if (category != null && category.id() > 0) {
          String prefix = kind.equals("pizza") ? "pizza:" : "menu:";
          if (!category.kind().equals(kind.equals("pizza") ? "pizza_category" : "menu_category")
              || !ItemCustomization.withinCategory(
                  catalog,
                  prefix + category.id(),
                  prefix
                      + row.path(kind.equals("pizza") ? "pizza_category_id" : "category_id")
                          .asLong())) continue;
        }
        if (combosOnly
            && priceRows(p).stream()
                .noneMatch(
                    price -> catalog.rules().combos().containsKey(priceIdentity(kind, price))))
          continue;
        products.getItems().add(p);
      }
    products.getItems().sort(Comparator.comparing(Product::toString));
    if (product != null)
      products.getItems().stream()
          .filter(p -> p.kind().equals(product.kind()) && p.id() == product.id())
          .findFirst()
          .ifPresent(p -> products.getSelectionModel().select(p));
    filling = false;
  }

  private void open(Product selected) {
    work(
        () ->
            gateway.catalogue(
                "products/" + selected.kind() + "/" + selected.id(), "GET", null, token),
        aggregate -> {
          clearDraft();
          state = aggregate.path("state");
          originalState = state.deepCopy();
          catalog = decode(aggregate.path("catalog"), ItemCustomization.Catalog.class);
          product = selected;
          details = (ObjectNode) aggregate.path("details").deepCopy();
          recipeView = aggregate.path("recipe");
          productRevision = aggregate.path("productRevision").asText();
          product = new Product(selected.kind(), details);
          tree();
          filterProducts();
          render();
        });
  }

  private void clearDraft() {
    workbenches.forEach(ItemWorkbench::close);
    workbenches.clear();
    changes.clear();
    errors.clear();
    prices.clear();
    variantRules.clear();
    if (recipe != null) recipe.close();
    recipe = null;
    recipeDirty = false;
    dirty = false;
    conflict = false;
    pending = null;
  }

  private void clearEditor() {
    clearDraft();
    product = null;
    details = null;
    tabs.getTabs().clear();
    heading.setText("Select a product");
    status.setText("");
    updateButtons();
  }

  private void recipeChanged() {
    recipeDirty = true;
    changed();
    if (modifierBox != null && comboBox != null && !filling) fillModifiers(modifierBox);
  }

  private void changed() {
    if (filling || closed) return;
    dirty = true;
    pending = null;
    status.setText("Unsaved changes · Save Product updates all sections");
    updateButtons();
  }

  private void updateButtons() {
    for (String id : List.of("product-add", "product-add-category", "product-edit-category"))
      if (lookup("#" + id) instanceof Button action)
        action.setDisable(busy || state == null || !state.path("editable").asBoolean());
    save.setDisable(
        busy || !dirty || conflict || state == null || !state.path("editable").asBoolean());
    cancel.setDisable(busy || product == null);
  }

  private Tab tab(String title, Node node) {
    var scroll = new ScrollPane(node);
    scroll.setFitToWidth(true);
    var tab = new Tab(title, scroll);
    tabs.getTabs().add(tab);
    return tab;
  }

  private VBox section() {
    var box = new VBox(12);
    box.setPadding(new Insets(16));
    return box;
  }

  private void render() {
    filling = true;
    tabs.getTabs().clear();
    errors.clear();
    prices.clear();
    heading.setText(details.path("name").asText("New Product"));
    var general = section();
    text(general, "name", "Product name", details);
    if (details.has("description")) text(general, "description", "Description", details);
    String categoryTable = product.kind().equals("pizza") ? "pizza_category" : "menu_category";
    String categoryField = product.kind().equals("pizza") ? "pizza_category_id" : "category_id";
    var category = new ComboBox<Category>();
    category.setMaxWidth(Double.MAX_VALUE);
    rows(categoryTable).stream()
        .filter(r -> !r.path("archived").asBoolean())
        .forEach(
            row ->
                category
                    .getItems()
                    .add(
                        new Category(
                            categoryTable, id(row, categoryTable), row.path("name").asText())));
    category.getItems().stream()
        .filter(c -> c.id() == details.path(categoryField).asLong())
        .findFirst()
        .ifPresent(category::setValue);
    category.setOnAction(
        e -> {
          if (category.getValue() != null) {
            details.put(categoryField, category.getValue().id());
            stageDetails();
          }
        });
    general.getChildren().addAll(new Label("Category"), category);
    integer(general, "sort_order", "Display order", details);
    for (String flag : List.of("is_300ml", "is_2l"))
      if (details.has(flag))
        check(general, flag, flag.equals("is_300ml") ? "300 ml size" : "2 litre size", details);
    tab("General Details", general);

    var recipeBox = section();
    boolean linked = false;
    for (var row : recipeView.path("ingredients"))
      if (row.path("selected").asBoolean()) linked = true;
    var assignment =
        rows("burger_recipe_assignment").stream()
            .filter(r -> r.path("burger_id").asLong() == product.id())
            .findFirst();
    if (assignment.isPresent()) {
      var name =
          rows("burger_recipe").stream()
              .filter(r -> r.path("recipe_id").equals(assignment.get().path("recipe_id")))
              .findFirst()
              .map(r -> r.path("name").asText())
              .orElse("Shared recipe");
      recipeBox
          .getChildren()
          .add(new Label("Shared recipe: " + name + " · Product edits affect only this product"));
      var shared = new Button("Edit Shared Recipe");
      shared.setOnAction(e -> sharedRecipe());
      recipeBox.getChildren().add(shared);
    }
    if (recipeView.path("family").asText().equals("burger")) sharedRecipeAssignment(recipeBox);
    if (product.kind().equals("pizza") || linked || assignment.isPresent()) {
      recipe =
          new ProductIngredientPicker(
              recipeView, this::recipeChanged, this::newIngredient, this::discardIngredient);
      recipeBox.getChildren().add(recipe);
      tab("Ingredients / Recipe", recipeBox);
    } else {
      var enable = new Button("Configure Ingredients / Recipe");
      var family = new ComboBox<String>();
      family.getItems().setAll("ingredient", "burger");
      family.setValue("ingredient");
      general.getChildren().addAll(new Label("Optional recipe configuration"), family, enable);
      enable.setOnAction(
          e -> {
            recipeView = emptyRecipe(family.getValue());
            recipe =
                new ProductIngredientPicker(
                    recipeView, this::recipeChanged, this::newIngredient, this::discardIngredient);
            var box = section();
            if (family.getValue().equals("burger")) sharedRecipeAssignment(box);
            box.getChildren().add(recipe);
            tab("Ingredients / Recipe", box);
            enable.setDisable(true);
            recipeChanged();
          });
    }
    var sizeBox = section();
    if (product.kind().equals("pizza")) {
      sizeBox.getChildren().add(new Label("Choose the sizes offered for this product"));
      for (var size : rows("pizza_size")) {
        long sizeId = size.path("pizza_size_id").asLong();
        var existing =
            rows("pizza_allowed_size").stream()
                .filter(
                    r ->
                        r.path("pizza_id").asLong() == product.id()
                            && r.path("pizza_size_id").asLong() == sizeId)
                .findFirst();
        var allowed = new CheckBox(size.path("cm").asText() + " cm");
        allowed.setSelected(existing.isPresent());
        allowed.setOnAction(
            e -> {
              var row =
                  JsonFiles.JSON
                      .createObjectNode()
                      .put("pizza_id", product.id())
                      .put("pizza_size_id", sizeId);
              stage("pizza_allowed_size", row, !allowed.isSelected());
              if (!allowed.isSelected())
                rows("branch_pizza_price").stream()
                    .filter(
                        p ->
                            p.path("pizza_id").asLong() == product.id()
                                && p.path("pizza_size_id").asLong() == sizeId)
                    .forEach(p -> stage("branch_pizza_price", p, true));
              rebuildPrices();
            });
        sizeBox.getChildren().add(allowed);
      }
    } else
      sizeBox
          .getChildren()
          .add(
              new Label("This product has one standard price. Size flags are in General Details."));
    tab("Sizes / Variants", sizeBox);
    var priceBox = section();
    pricingBox = priceBox;
    priceBox.setId("product-pricing");
    tab("Pricing", priceBox);
    fillPrices(priceBox);
    comboBox = section();
    comboTab = null;
    modifierBox = section();
    fillModifiers(modifierBox);
    tab("Modifiers / Extras", modifierBox);
    var availability = section();
    var available = check(availability, "active", "Available for sale", details);
    var archive = new Button("Archive Product");
    archive.setOnAction(
        e -> {
          if (confirm(
              "Archive Product",
              "Mark this product inactive when Save Product is clicked? Historical orders are retained.")) {
            details.put("active", false);
            available.setSelected(false);
            stageDetails();
            feedback.setText("Product will be archived when Save Product is clicked.");
          }
        });
    availability.getChildren().add(archive);
    tab("Availability", availability);
    filling = false;
    status.setText(
        dirty ? "Unsaved changes · Save Product updates all sections" : "All changes saved");
    tabs.setDisable(!state.path("editable").asBoolean());
    updateButtons();
  }

  private void sharedRecipeAssignment(VBox box) {
    var shared = new ComboBox<Category>();
    shared.getItems().add(new Category("burger_recipe", 0, "No shared recipe"));
    rows("burger_recipe").stream()
        .filter(r -> r.path("active").asBoolean())
        .forEach(
            r ->
                shared
                    .getItems()
                    .add(
                        new Category(
                            "burger_recipe",
                            r.path("recipe_id").asLong(),
                            r.path("name").asText())));
    var assignment =
        rows("burger_recipe_assignment").stream()
            .filter(r -> r.path("burger_id").asLong() == product.id())
            .findFirst();
    long previous = assignment.map(r -> r.path("recipe_id").asLong()).orElse(0L);
    shared.getItems().stream()
        .filter(c -> c.id() == previous)
        .findFirst()
        .ifPresent(shared::setValue);
    if (shared.getValue() == null) shared.getSelectionModel().selectFirst();
    var protein =
        new Spinner<Integer>(
            1, 99, assignment.map(r -> r.path("protein_quantity_required").asInt(1)).orElse(1));
    protein
        .valueProperty()
        .addListener(
            (o, a, b) -> {
              var row =
                  rows("burger_recipe_assignment").stream()
                      .filter(r -> r.path("burger_id").asLong() == product.id())
                      .findFirst();
              if (row.isPresent())
                stage(
                    "burger_recipe_assignment",
                    ((ObjectNode) row.get().deepCopy()).put("protein_quantity_required", b),
                    false);
            });
    shared.setOnAction(
        e -> {
          if (shared.getValue() == null) return;
          if (recipe != null
              && dirty
              && !confirm(
                  "Change shared recipe",
                  "Reload this product's ingredient selection from the chosen recipe? Other pending product sections will be retained."))
            return;
          var old =
              rows("burger_recipe_assignment").stream()
                  .filter(r -> r.path("burger_id").asLong() == product.id())
                  .findFirst();
          long recipeId = shared.getValue().id();
          if (recipeId == 0) old.ifPresent(row -> stage("burger_recipe_assignment", row, true));
          else {
            var row =
                old.isPresent()
                    ? (ObjectNode) old.get().deepCopy()
                    : JsonFiles.JSON
                        .createObjectNode()
                        .put("burger_id", product.id())
                        .put("protein_required", true)
                        .put("protein_quantity_required", protein.getValue());
            stage("burger_recipe_assignment", row.put("recipe_id", recipeId), false);
          }
          recipeView = emptyRecipe("burger");
          for (var ingredient : recipeView.path("ingredients")) {
            long component = Long.parseLong(ingredient.path("key").asText().substring(7));
            var origins = ((ObjectNode) ingredient).putArray("origins");
            boolean selected = false;
            for (String table :
                List.of(
                    "burger_recipe_component",
                    "burger_item_default_component",
                    "burger_item_component_override"))
              for (var row : rows(table))
                if (row.path("component_id").asLong() == component
                    && (table.equals("burger_recipe_component")
                        ? row.path("recipe_id").asLong() == recipeId
                        : row.path("burger_id").asLong() == product.id())) {
                  origins.addObject().put("table", table).set("properties", row);
                  selected =
                      table.equals("burger_item_component_override")
                          ? !row.path("excluded").asBoolean() && selected
                          : true;
                }
            ((ObjectNode) ingredient).put("selected", selected);
          }
          if (recipe != null) box.getChildren().remove(recipe);
          recipe =
              new ProductIngredientPicker(
                  recipeView, this::recipeChanged, this::newIngredient, this::discardIngredient);
          box.getChildren().add(recipe);
          changed();
        });
    box.getChildren()
        .addAll(
            new Label("Shared recipe assignment"),
            shared,
            new Label("Protein portions (when required)"),
            protein);
  }

  private void fillModifiers(VBox modifiers) {
    workbenches.forEach(ItemWorkbench::close);
    workbenches.clear();
    comboBox.getChildren().clear();
    modifiers.getChildren().clear();
    modifiers.getChildren().add(new Label("Reusable modifier groups"));
    if (product.kind().equals("menu_item"))
      for (var group : rows("modifier_group")) {
        long groupId = group.path("id").asLong();
        var check = new CheckBox(group.path("name").asText());
        check.setSelected(
            rows("menu_item_modifier_group").stream()
                .anyMatch(
                    r ->
                        r.path("menu_item_id").asLong() == product.id()
                            && r.path("group_id").asLong() == groupId));
        check.setOnAction(
            e -> {
              stage(
                  "menu_item_modifier_group",
                  JsonFiles.JSON
                      .createObjectNode()
                      .put("menu_item_id", product.id())
                      .put("group_id", groupId),
                  !check.isSelected());
              fillModifiers(modifiers);
            });
        modifiers.getChildren().add(check);
      }
    var variantTabs = new TabPane();
    variantTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    for (var price : prices) {
      var base = catalog.products().get(price.id());
      if (base == null)
        base =
            new ItemCustomization.Product(
                price.id(),
                details.path("name").asText(),
                (product.kind().equals("pizza") ? "pizza:" : "menu:")
                    + details
                        .path(product.kind().equals("pizza") ? "pizza_category_id" : "category_id")
                        .asLong(),
                catalog.revision(),
                priceAmount(price.key()),
                true,
                List.of(),
                List.of(),
                null);
      base = stagedProduct(base);
      var all = new LinkedHashMap<>(catalog.products());
      all.put(price.id(), base);
      var extras = new LinkedHashMap<>(catalog.extras());
      base.extras().forEach(e -> extras.putIfAbsent(e.key(), e));
      var sourceCatalog =
          new ItemCustomization.Catalog(
              catalog.revision(),
              all,
              extras,
              catalog.categories(),
              variantRules.getOrDefault(price.id(), catalog.rules()),
              catalog.editable(),
              catalog.parents());
      var workbench =
          new ItemWorkbench(
              gateway,
              executor,
              token,
              sourceCatalog,
              price.id(),
              rules -> {
                variantRules.put(price.id(), rules);
                changed();
                updateComboTab();
              });
      var host = new VBox(12);
      workbench.comboHost(host);
      comboBox.getChildren().add(host);
      workbenches.add(workbench);
      variantTabs.getTabs().add(new Tab(variantName(price.key()), workbench));
    }
    modifiers.getChildren().add(variantTabs);
    updateComboTab();
  }

  private ItemCustomization.Product stagedProduct(ItemCustomization.Product base) {
    var groups = new ArrayList<ItemCustomization.Group>();
    if (product.kind().equals("menu_item")) {
      for (var link : rows("menu_item_modifier_group"))
        if (link.path("menu_item_id").asLong() == product.id())
          for (var group : rows("modifier_group"))
            if (group.path("id").asLong() == link.path("group_id").asLong()) {
              var choices = new ArrayList<ItemCustomization.Choice>();
              for (var option : rows("modifier_option"))
                if (option.path("group_id").asLong() == group.path("id").asLong()) {
                  UUID ref = null;
                  if (option.hasNonNull("menu_item_id")) {
                    String identity =
                        state
                            .path("priceIdentities")
                            .path(
                                "branch_menu_item_price"
                                    + List.of(
                                        state.path("branchId").asLong(),
                                        option.path("menu_item_id").asLong()))
                            .asText();
                    if (!identity.isBlank()) ref = UUID.fromString(identity);
                  }
                  choices.add(
                      new ItemCustomization.Choice(
                          "modifier:" + option.path("id").asLong(),
                          option.path("name").asText(),
                          option.path("additional_price").decimalValue(),
                          ref,
                          null,
                          false,
                          !option.hasNonNull("menu_item_id")
                              || ref != null
                                  && catalog.products().containsKey(ref)
                                  && catalog.products().get(ref).available()));
                }
              groups.add(
                  new ItemCustomization.Group(
                      "modifier:" + group.path("id").asLong(),
                      group.path("name").asText(),
                      Math.max(
                          group.path("required").asBoolean() ? 1 : 0,
                          group.path("min_select").asInt()),
                      group.path("max_select").asInt(),
                      null,
                      choices));
            }
    } else groups.addAll(base.groups());
    var extras = new LinkedHashMap<String, ItemCustomization.Extra>();
    base.extras().forEach(e -> extras.put(e.key(), e));
    if (recipeDirty && recipe != null) {
      var draft = recipe.draft();
      extras.replaceAll(
          (key, e) ->
              new ItemCustomization.Extra(
                  e.key(),
                  e.name(),
                  e.price(),
                  e.available(),
                  0,
                  e.removable(),
                  e.swapGroup(),
                  e.origin()));
      for (String key : draft.selected()) {
        var e = extras.getOrDefault(key, catalog.extras().get(key));
        if (e == null) {
          String[] parts = key.split(":");
          String table =
              parts[0].equals("burger")
                  ? "burger_component"
                  : parts[0].equals("salad") ? "salad_ingredients" : "ingredient";
          String id =
              parts[0].equals("burger")
                  ? "component_id"
                  : parts[0].equals("salad") ? "id" : "ingredient_id";
          for (var row : rows(table))
            if (row.path(id).asLong() == Long.parseLong(parts[1]))
              e =
                  new ItemCustomization.Extra(
                      key,
                      row.path(parts[0].equals("salad") ? "ingredient_name" : "name").asText(),
                      null,
                      row.path("active").asBoolean(true),
                      0,
                      true,
                      "",
                      "Draft recipe");
        }
        if (e == null) continue;
        int quantity =
            base.extras().stream()
                .filter(original -> original.key().equals(key))
                .mapToInt(ItemCustomization.Extra::included)
                .filter(q -> q > 0)
                .findFirst()
                .orElse(1);
        boolean removable = e.removable();
        for (var row : recipeView.path("ingredients"))
          if (row.path("key").asText().equals(key))
            for (var origin : row.path("origins")) {
              if (draft.restoreInherited().contains(key)
                  && origin.path("table").asText().equals("burger_item_component_override"))
                continue;
              var properties = origin.path("properties");
              if (properties.hasNonNull("default_qty"))
                quantity = properties.path("default_qty").asInt();
              if (properties.hasNonNull("is_removable"))
                removable = properties.path("is_removable").asBoolean();
            }
        var properties = draft.properties().getOrDefault(key, Map.of());
        quantity = ((Number) properties.getOrDefault("default_qty", quantity)).intValue();
        removable = (Boolean) properties.getOrDefault("is_removable", removable);
        extras.put(
            key,
            new ItemCustomization.Extra(
                e.key(),
                e.name(),
                e.price(),
                e.available(),
                quantity,
                removable,
                e.swapGroup(),
                "Draft recipe"));
      }
    }
    return new ItemCustomization.Product(
        base.priceId(),
        base.name(),
        base.category(),
        base.priceRevision(),
        base.price(),
        base.available(),
        List.copyOf(extras.values()),
        groups,
        base.combo());
  }

  private void updateComboTab() {
    boolean enabled =
        prices.stream()
            .anyMatch(
                p ->
                    variantRules.getOrDefault(p.id(), catalog.rules()).combos().containsKey(p.id())
                        || catalog.products().containsKey(p.id())
                            && catalog.products().get(p.id()).combo() != null);
    if (enabled && comboTab == null) comboTab = tab("Combo Configuration", comboBox);
    if (!enabled && comboTab != null) {
      tabs.getTabs().remove(comboTab);
      comboTab = null;
    }
  }

  private void text(VBox box, String key, String label, ObjectNode row) {
    var input = new TextField(row.path(key).asText(""));
    input.setId("product-" + key);
    input.setMaxWidth(Double.MAX_VALUE);
    var error = new Label();
    error.setStyle("-fx-text-fill: #b42318;");
    errors.put(key, error);
    input
        .textProperty()
        .addListener(
            (o, a, b) -> {
              row.put(key, b);
              stageDetails();
              if (key.equals("name")) heading.setText(b);
            });
    box.getChildren().addAll(new Label(label), input, error);
  }

  private CheckBox check(VBox box, String key, String label, ObjectNode row) {
    var input = new CheckBox(label);
    input.setSelected(row.path(key).asBoolean());
    input.setOnAction(
        e -> {
          row.put(key, input.isSelected());
          stageDetails();
        });
    box.getChildren().add(input);
    return input;
  }

  private void integer(VBox box, String key, String label, ObjectNode row) {
    var input = new Spinner<Integer>(-100000, 100000, row.path(key).asInt());
    input
        .valueProperty()
        .addListener(
            (o, a, b) -> {
              row.put(key, b);
              stageDetails();
            });
    box.getChildren().addAll(new Label(label), input);
  }

  private void stageDetails() {
    stage(product.kind(), details, false);
  }

  private String rowKey(String table, JsonNode row) {
    var key = new ArrayList<Long>();
    state
        .path("schema")
        .path(table)
        .path("keys")
        .forEach(k -> key.add(row.path(k.asText()).asLong()));
    return table + key;
  }

  private void stage(String table, JsonNode row, boolean remove) {
    if (closed) return;
    var key = new ArrayList<Long>();
    state
        .path("schema")
        .path(table)
        .path("keys")
        .forEach(k -> key.add(row.path(k.asText()).asLong()));
    var original = new ArrayList<JsonNode>();
    originalState.path("tables").path(table).forEach(original::add);
    boolean existed = original.stream().anyMatch(r -> rowKey(table, r).equals(rowKey(table, row)));
    var data =
        JsonFiles.JSON.convertValue(
            row, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    if (remove && !existed) changes.remove(rowKey(table, row));
    else
      changes.put(
          rowKey(table, row),
          new CatalogueManagement.Change(table, existed ? key : null, data, remove));
    var all = rows(table);
    all.removeIf(r -> rowKey(table, r).equals(rowKey(table, row)));
    if (!remove) all.add(row.deepCopy());
    ((ObjectNode) state.path("tables")).set(table, JsonFiles.JSON.valueToTree(all));
    changed();
  }

  private List<JsonNode> priceRows(Product p) {
    return rows(p.kind().equals("pizza") ? "branch_pizza_price" : "branch_menu_item_price").stream()
        .filter(
            r -> r.path(p.kind().equals("pizza") ? "pizza_id" : "menu_item_id").asLong() == p.id())
        .toList();
  }

  private UUID priceIdentity(String kind, JsonNode price) {
    String table = kind.equals("pizza") ? "branch_pizza_price" : "branch_menu_item_price";
    String key = priceKey(kind, price).toString();
    if (state.path("priceIdentities").has(table + key))
      return UUID.fromString(state.path("priceIdentities").path(table + key).asText());
    return UUID.nameUUIDFromBytes((table + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private List<Long> priceKey(String kind, JsonNode row) {
    return kind.equals("pizza")
        ? List.of(
            row.path("branch_id").asLong(),
            row.path("pizza_id").asLong(),
            row.path("pizza_size_id").asLong())
        : List.of(row.path("branch_id").asLong(), row.path("menu_item_id").asLong());
  }

  private String variantName(List<Long> key) {
    if (key.size() == 2) return "Standard";
    return rows("pizza_size").stream()
        .filter(r -> r.path("pizza_size_id").asLong() == key.get(2))
        .findFirst()
        .map(r -> r.path("cm").asText() + " cm")
        .orElse("Size");
  }

  private BigDecimal priceAmount(List<Long> key) {
    return priceRows(product).stream()
        .filter(r -> priceKey(product.kind(), r).equals(key))
        .findFirst()
        .map(r -> r.path("price").decimalValue())
        .orElse(BigDecimal.ZERO.setScale(2));
  }

  private void fillPrices(VBox box) {
    box.getChildren().clear();
    prices.clear();
    var sizeIds =
        product.kind().equals("pizza")
            ? rows("pizza_allowed_size").stream()
                .filter(r -> r.path("pizza_id").asLong() == product.id())
                .map(r -> r.path("pizza_size_id").asLong())
                .toList()
            : List.of(0L);
    for (long size : sizeIds) {
      String table =
          product.kind().equals("pizza") ? "branch_pizza_price" : "branch_menu_item_price";
      var existing =
          priceRows(product).stream()
              .filter(r -> size == 0 || r.path("pizza_size_id").asLong() == size)
              .findFirst();
      var row =
          existing.isPresent()
              ? (ObjectNode) existing.get().deepCopy()
              : JsonFiles.JSON
                  .createObjectNode()
                  .put("branch_id", state.path("branchId").asLong())
                  .put(product.kind().equals("pizza") ? "pizza_id" : "menu_item_id", product.id())
                  .put("price", BigDecimal.ZERO.setScale(2));
      if (size != 0) row.put("pizza_size_id", size);
      var key = priceKey(product.kind(), row);
      var price = new Price(key, priceIdentity(product.kind(), row));
      prices.add(price);
      var input = new TextField(row.path("price").asText());
      input.setPrefWidth(150);
      input.setAccessibleText("Price for " + variantName(key));
      var error = new Label();
      error.setStyle("-fx-text-fill: #b42318;");
      errors.put("price" + key, error);
      input
          .textProperty()
          .addListener(
              (o, a, b) -> {
                changed();
                try {
                  var value = ItemCustomization.money(new BigDecimal(b.strip()));
                  row.put("price", value);
                  stage(table, row, false);
                  error.setText("");
                } catch (Exception invalid) {
                  error.setText("Enter a nonnegative price with at most two decimals");
                }
              });
      if (existing.isEmpty()) stage(table, row, false);
      box.getChildren()
          .addAll(
              new Label(variantName(key) + " · Store " + state.path("branchId").asText()),
              input,
              error);
    }
    if (prices.isEmpty())
      box.getChildren().add(new Label("Choose at least one size to configure pricing."));
  }

  private void rebuildPrices() {
    if (pricingBox != null) fillPrices(pricingBox);
    if (modifierBox != null) fillModifiers(modifierBox);
    feedback.setText("Size selection staged. Prices and settings save with this product.");
  }

  private JsonNode emptyRecipe(String family) {
    var view = JsonFiles.JSON.createObjectNode().put("family", family);
    var choices = view.putArray("ingredients");
    for (var row : rows(family.equals("burger") ? "burger_component" : "ingredient"))
      choices
          .addObject()
          .put(
              "key",
              family
                  + ":"
                  + row.path(family.equals("burger") ? "component_id" : "ingredient_id").asLong())
          .put("name", row.path("name").asText())
          .put("type", family.equals("burger") ? row.path("component_type").asText() : "ingredient")
          .put("available", row.path("active").asBoolean())
          .put("compatible", true)
          .put("selected", false)
          .putArray("origins");
    return view;
  }

  private void discardIngredient(JsonNode ingredient) {
    String[] key = ingredient.path("key").asText().split(":");
    String table = key[0].equals("burger") ? "burger_component" : "ingredient";
    String column = key[0].equals("burger") ? "component_id" : "ingredient_id";
    rows(table).stream()
        .filter(row -> row.path(column).asLong() == Long.parseLong(key[1]))
        .findFirst()
        .ifPresent(
            row -> {
              stage(table, row, true);
              long category = row.path("price_category_id").asLong();
              if (category < 0
                  && rows("ingredient").stream()
                      .noneMatch(i -> i.path("price_category_id").asLong() == category))
                rows("price_category").stream()
                    .filter(c -> c.path("price_category_id").asLong() == category)
                    .findFirst()
                    .ifPresent(c -> stage("price_category", c, true));
            });
  }

  private JsonNode newIngredient() {
    String family =
        recipe == null ? recipeView.path("family").asText("ingredient") : recipe.draft().family();
    var dialog = new Dialog<ButtonType>();
    dialog.setTitle("New reusable ingredient · staged with product");
    var add = new ButtonType("Add to Draft", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(add, ButtonType.CANCEL);
    if (getScene() != null) dialog.initOwner(getScene().getWindow());
    var name = new TextField();
    var choice = new ComboBox<String>();
    var categoryIds = new ArrayList<Long>();
    if (family.equals("burger"))
      choice.getItems().setAll("default_topping", "protein", "extra_topping");
    else
      for (var row : rows("price_category")) {
        choice.getItems().add(row.path("name").asText());
        categoryIds.add(row.path("price_category_id").asLong());
      }
    if (!choice.getItems().isEmpty()) choice.getSelectionModel().selectFirst();
    var createdCategories = new ArrayList<JsonNode>();
    var newCategory = new Button("New Price Category");
    newCategory.setVisible(!family.equals("burger"));
    newCategory.setManaged(!family.equals("burger"));
    newCategory.setOnAction(
        e -> {
          var prompt = new javafx.scene.control.TextInputDialog();
          prompt.setTitle("New ingredient price category");
          prompt.setHeaderText("This reusable category will be saved with the product");
          prompt
              .showAndWait()
              .filter(nameText -> !nameText.isBlank())
              .ifPresent(
                  nameText -> {
                    var category =
                        JsonFiles.JSON
                            .createObjectNode()
                            .put("price_category_id", nextDraft--)
                            .put("name", nameText.strip())
                            .put("sort_order", 0)
                            .put("active", true);
                    stage("price_category", category, false);
                    createdCategories.add(category);
                    categoryIds.add(category.path("price_category_id").asLong());
                    choice.getItems().add(nameText.strip());
                    choice.getSelectionModel().selectLast();
                  });
        });
    var message = new Label();
    dialog
        .getDialogPane()
        .setContent(
            new VBox(
                10,
                new Label("Ingredient name"),
                name,
                new Label(family.equals("burger") ? "Component type" : "Price category"),
                choice,
                newCategory,
                message));
    dialog
        .getDialogPane()
        .lookupButton(add)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            e -> {
              if (name.getText().isBlank() || choice.getValue() == null) {
                message.setText("Enter a name and select a type or price category.");
                e.consume();
              }
            });
    if (showDialog(dialog).orElse(ButtonType.CANCEL) != add) {
      createdCategories.forEach(c -> stage("price_category", c, true));
      return null;
    }
    long id = nextDraft--;
    String table = family.equals("burger") ? "burger_component" : "ingredient";
    var row =
        JsonFiles.JSON
            .createObjectNode()
            .put(family.equals("burger") ? "component_id" : "ingredient_id", id)
            .put("name", name.getText().strip())
            .put("active", true)
            .put("seasonal", false);
    if (family.equals("burger"))
      row.put("component_type", choice.getValue()).put("seasonal", false);
    else
      row.put("price_category_id", categoryIds.get(choice.getSelectionModel().getSelectedIndex()));
    stage(table, row, false);
    return JsonFiles.JSON
        .createObjectNode()
        .put("key", family + ":" + id)
        .put("type", family.equals("burger") ? choice.getValue() : "ingredient")
        .put("name", name.getText().strip())
        .put("available", true)
        .put("compatible", true)
        .put("selected", false);
  }

  private void addProduct() {
    if (state == null || !state.path("editable").asBoolean() || !confirmClose()) return;
    var selected = categories.getSelectionModel().getSelectedItem();
    var category = selected == null ? null : selected.getValue();
    var kind =
        new ChoiceDialog<>(
            category != null && category.kind().equals("pizza_category")
                ? "Pizza"
                : "Standard product",
            List.of("Standard product", "Pizza"));
    kind.setTitle("Add Product");
    kind.setHeaderText("Choose the product structure");
    var answer = showDialog(kind);
    if (answer.isEmpty()) return;
    String type = answer.get().equals("Pizza") ? "pizza" : "menu_item";
    var options =
        rows(type.equals("pizza") ? "pizza_category" : "menu_category").stream()
            .filter(r -> !r.path("archived").asBoolean())
            .toList();
    if (options.isEmpty()) {
      feedback.setText("Create a category first.");
      return;
    }
    clearDraft();
    state = originalState.deepCopy();
    details =
        JsonFiles.JSON
            .createObjectNode()
            .put(type.equals("pizza") ? "pizza_id" : "id", nextDraft--)
            .put("name", "")
            .put("description", "")
            .put("sort_order", 0)
            .put("active", true)
            .put(
                type.equals("pizza") ? "pizza_category_id" : "category_id",
                category != null
                        && category
                            .kind()
                            .equals(type.equals("pizza") ? "pizza_category" : "menu_category")
                    ? category.id()
                    : id(
                        options.getFirst(),
                        type.equals("pizza") ? "pizza_category" : "menu_category"));
    if (type.equals("menu_item")) details.put("is_300ml", false).put("is_2l", false);
    product = new Product(type, details);
    productRevision = "new";
    recipeView = emptyRecipe("ingredient");
    stageDetails();
    render();
    dirty = true;
    updateButtons();
  }

  private void save() {
    if (busy || product == null || conflict) return;
    boolean invalid = false;
    if (errors.containsKey("name")) errors.get("name").setText("");
    if (details.path("name").asText().isBlank() || details.path("name").asText().length() > 128) {
      errors.get("name").setText("Enter a product name (maximum 128 characters)");
      tabs.getSelectionModel().selectFirst();
      invalid = true;
    }
    for (var error : errors.values()) if (!error.getText().isBlank()) invalid = true;
    if (prices.isEmpty()) {
      feedback.setText("Choose at least one size and set a price.");
      return;
    }
    if (invalid) {
      feedback.setText("Correct the marked fields before saving. Your other edits are retained.");
      return;
    }
    if (pending == null) {
      var variants = new ArrayList<ProductEdits.Variant>();
      for (var price : prices)
        if (variantRules.containsKey(price.id())) {
          var rules = variantRules.get(price.id());
          variants.add(
              new ProductEdits.Variant(
                  price.key(), rules.items().get(price.id()), rules.combos().get(price.id())));
        }
      pending =
          new ProductEdits.Save(
              UUID.randomUUID(),
              originalState.path("revision").asLong(),
              productRevision,
              product.kind(),
              product.id(),
              List.copyOf(changes.values()),
              recipe == null || !recipeDirty ? null : recipe.draft(),
              variants);
    }
    var command = pending;
    work(
        () -> gateway.catalogue("products", "PUT", command, token),
        result -> {
          dirty = false;
          pending = null;
          status.setText("Product saved");
          long id = result.path("product").path("id").asLong(product.id());
          String kind = product.kind();
          // Aggregate reload obtains the exact committed source IDs and all associated records.
          open(
              new Product(
                  kind, details.deepCopy().put(kind.equals("pizza") ? "pizza_id" : "id", id)));
        });
  }

  private void sharedRecipe() {
    if (dirty) {
      feedback.setText("Save Product or Cancel before editing the shared recipe.");
      return;
    }
    var dialog = new Dialog<ButtonType>();
    dialog.setTitle("Edit Shared Recipe");
    dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    var view = recipeView.deepCopy();
    var choices = (com.fasterxml.jackson.databind.node.ArrayNode) view.path("ingredients");
    for (var ingredient : choices) {
      boolean shared = false;
      for (var origin : ingredient.path("origins"))
        if (origin.path("table").asText().equals("burger_recipe_component")) shared = true;
      ((ObjectNode) ingredient).put("selected", shared);
      var origins = JsonFiles.JSON.createArrayNode();
      ingredient
          .path("origins")
          .forEach(
              o -> {
                if (o.path("table").asText().equals("burger_recipe_component")) origins.add(o);
              });
      ((ObjectNode) ingredient).set("origins", origins);
    }
    var picker = new ProductIngredientPicker(view, () -> {}, () -> null);
    var commit = new Button("Save Shared Recipe");
    var message = new Label();
    commit.setOnAction(
        e -> {
          if (!confirm(
              "Change shared recipe?",
              "Affected products: "
                  + recipeView.path("affectedItems")
                  + ". Product-specific overrides will be preserved.")) return;
          var draft = picker.draft();
          var command =
              new Recipes.Save(
                  UUID.randomUUID(),
                  state.path("revision").asLong(),
                  new Recipes.Product(product.kind(), product.id()),
                  "burger",
                  draft.selected(),
                  draft.properties(),
                  true);
          commit.setDisable(true);
          executor.execute(
              () -> {
                try {
                  gateway.catalogue("recipes/shared", "PUT", command, token);
                  Platform.runLater(
                      () -> {
                        if (!closed) {
                          dialog.close();
                          open(product);
                        }
                      });
                } catch (Exception failure) {
                  Platform.runLater(
                      () -> {
                        commit.setDisable(false);
                        message.setText(
                            "Shared recipe save was not confirmed. Reload the product before retrying.");
                      });
                }
              });
        });
    dialog.getDialogPane().setContent(new VBox(12, picker, commit, message));
    dialog.getDialogPane().setPrefWidth(850);
    if (getScene() != null) dialog.initOwner(getScene().getWindow());
    showDialog(dialog);
  }

  private void categoryDialog(Category category) {
    if (state == null || !state.path("editable").asBoolean() || !confirmClose()) return;
    String initialTable = category == null ? "menu_category" : category.kind();
    String table = initialTable;
    var row =
        category == null
            ? JsonFiles.JSON
                .createObjectNode()
                .put("name", "")
                .put("sort_order", 0)
                .put("active", true)
                .put("archived", false)
                .putNull("parent_id")
            : (ObjectNode)
                rows(table).stream()
                    .filter(r -> id(r, initialTable) == category.id())
                    .findFirst()
                    .orElseThrow()
                    .deepCopy();
    var dialog = new Dialog<ButtonType>();
    dialog.setTitle(category == null ? "Add Category" : "Edit Category");
    var commit = new ButtonType("Save Category", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(commit, ButtonType.CANCEL);
    var name = new TextField(row.path("name").asText());
    var type = new ComboBox<String>();
    type.getItems().setAll("Product category", "Pizza category");
    type.setValue(table.equals("pizza_category") ? "Pizza category" : "Product category");
    type.setDisable(category != null);
    var parent = new ComboBox<Category>();
    Runnable populate =
        () -> {
          parent.getItems().setAll(new Category("", 0, "No parent"));
          String t = type.getValue().equals("Pizza category") ? "pizza_category" : "menu_category";
          rows(t).stream()
              .filter(
                  r ->
                      !r.path("archived").asBoolean()
                          && (category == null || id(r, t) != category.id()))
              .forEach(
                  r -> parent.getItems().add(new Category(t, id(r, t), r.path("name").asText())));
          parent.getSelectionModel().selectFirst();
          parent.getItems().stream()
              .filter(c -> c.id() == row.path("parent_id").asLong())
              .findFirst()
              .ifPresent(parent::setValue);
        };
    type.setOnAction(e -> populate.run());
    populate.run();
    var order = new Spinner<Integer>(-100000, 100000, row.path("sort_order").asInt());
    var archived = new CheckBox("Archive category");
    archived.setSelected(row.path("archived").asBoolean());
    var error = new Label();
    dialog
        .getDialogPane()
        .setContent(
            new VBox(
                10,
                new Label("Category name"),
                name,
                type,
                new Label("Parent category"),
                parent,
                new Label("Display order"),
                order,
                archived,
                error));
    dialog
        .getDialogPane()
        .lookupButton(commit)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            e -> {
              if (name.getText().isBlank()) {
                error.setText("Enter a category name.");
                e.consume();
              }
            });
    if (getScene() != null) dialog.initOwner(getScene().getWindow());
    if (showDialog(dialog).orElse(ButtonType.CANCEL) != commit) return;
    table = type.getValue().equals("Pizza category") ? "pizza_category" : "menu_category";
    row.put("name", name.getText().strip())
        .put("sort_order", order.getValue())
        .put("archived", archived.isSelected());
    if (archived.isSelected()) row.put("active", false);
    if (parent.getValue().id() == 0) row.putNull("parent_id");
    else row.put("parent_id", parent.getValue().id());
    var values =
        JsonFiles.JSON.convertValue(
            row, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    var change =
        new CatalogueManagement.Change(
            table, category == null ? null : List.of(category.id()), values, false);
    work(
        () ->
            gateway.catalogue(
                "records",
                "PUT",
                new CatalogueManagement.Edit(
                    UUID.randomUUID(), state.path("revision").asLong(), List.of(change), false),
                token),
        r -> load());
  }

  JsonNode savedState() {
    return originalState;
  }

  boolean confirmClose() {
    return !busy
        && (!dirty
            || confirm(
                "Unsaved product changes", "Discard pending changes in every product section?"));
  }

  private <T> java.util.Optional<T> showDialog(Dialog<T> dialog) {
    if (closed) return java.util.Optional.empty();
    dialogs.add(dialog);
    try {
      return dialog.showAndWait();
    } finally {
      dialogs.remove(dialog);
    }
  }

  private boolean confirm(String title, String text) {
    var alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
    alert.setTitle(title);
    alert.setHeaderText(title);
    if (getScene() != null) alert.initOwner(getScene().getWindow());
    return showDialog(alert).orElse(ButtonType.CANCEL) == ButtonType.OK;
  }

  private <T> T decode(JsonNode node, Class<T> type) {
    try {
      return JsonFiles.JSON.treeToValue(node, type);
    } catch (Exception failure) {
      throw new IllegalArgumentException("Unable to read product configuration", failure);
    }
  }

  @Override
  public void close() {
    closed = true;
    List.copyOf(dialogs).forEach(Dialog::close);
    if (recipe != null) recipe.close();
    workbenches.forEach(ItemWorkbench::close);
    changes.clear();
    state = null;
    product = null;
  }
}
