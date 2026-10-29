package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import za.co.onlinepos.contracts.CatalogueManagement;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.TrustedStoreClient;

/** Native, schema-backed forms. Reference selectors display names, never database identifiers. */
final class CataloguePanel extends BorderPane implements AutoCloseable {
  private record Kind(String table, String label, String section) {
    @Override
    public String toString() {
      return label;
    }
  }

  private record Choice(JsonNode value, String label) {
    @Override
    public String toString() {
      return label;
    }
  }

  private static final List<Kind> KINDS =
      List.of(
          new Kind("menu_category", "Product categories", "Categories"),
          new Kind("pizza_category", "Pizza categories", "Categories"),
          new Kind("ingredient", "Ingredients", "Ingredients"),
          new Kind("burger_component", "Burger ingredients", "Ingredients"),
          new Kind("price_category", "Ingredient price categories", "Ingredients"),
          new Kind("burger_recipe", "Shared burger recipes", "Ingredients"),
          new Kind("burger_recipe_component", "Shared recipe ingredients", "Ingredients"),
          new Kind("modifier_group", "Modifier groups", "Modifiers & Extras"),
          new Kind("modifier_option", "Modifier options", "Modifiers & Extras"),
          new Kind("pizza_base_option", "Pizza bases", "Modifiers & Extras"),
          new Kind("branch_menu_item_price", "Product prices", "Store Pricing"),
          new Kind("branch_pizza_price", "Pizza prices by size", "Store Pricing"),
          new Kind("pizza_size", "Reusable pizza sizes", "Store Pricing"),
          new Kind("branch_extra_price", "Ingredient surcharges by size", "Store Pricing"),
          new Kind(
              "branch_burger_component_price", "Burger ingredient surcharges", "Store Pricing"),
          new Kind("branch_pizza_base_option_price", "Pizza base surcharges", "Store Pricing"));
  private static final java.util.Set<String> REMOVABLE =
      java.util.Set.of(
          "pizza_allowed_size",
          "pizza_default_ingredient",
          "burger_recipe_component",
          "burger_recipe_assignment",
          "burger_item_default_component",
          "menu_item_modifier_group",
          "modifier_option",
          "salad_ingredients",
          "branch_menu_item_price",
          "branch_pizza_price",
          "branch_extra_price",
          "branch_pizza_base_option_price",
          "branch_burger_component_price");
  private final ModuleGateway gateway;
  private final ExecutorService executor;
  private final String token;
  private final Consumer<String> lock;
  private final Runnable activity;
  private final VBox sidebar = new VBox(8);
  private final BorderPane body = new BorderPane();
  private final VBox form = new VBox(10);
  private final ScrollPane editorScroll = new ScrollPane(form);
  private final Label feedback = new Label();
  private final Label connection = new Label("Connecting to store…");
  private final Map<String, Node> inputs = new LinkedHashMap<>();
  private final TableView<JsonNode> list = new TableView<>();
  private final ComboBox<Kind> kind = new ComboBox<>();
  private final TextField search = new TextField();
  private final ComboBox<String> status = new ComboBox<>();
  private final ComboBox<Choice> filter = new ComboBox<>();
  private final Label pageLabel = new Label();
  private final Map<String, ToggleButton> sections = new LinkedHashMap<>();
  private JsonNode state, selected;
  private Kind current = KINDS.getFirst();
  private boolean busy, closed, dirty, populating;
  private int page;
  private long generation;
  private CatalogueManagement.Edit pending;
  private Dialog<?> dialog;
  private ItemWorkbench itemWorkbench;
  private RecipeEditor recipeEditor;
  private boolean firstCategory;
  private ProductWorkspace productWorkspace;
  private boolean productsMode = true;
  private String navigationSection = "Products";

  CataloguePanel(
      ModuleGateway gateway,
      ExecutorService executor,
      String token,
      String store,
      String username,
      Consumer<String> lock,
      Runnable activity) {
    this.gateway = gateway;
    this.executor = executor;
    this.token = token;
    this.lock = lock;
    this.activity = activity;
    setId("catalogue-panel");
    getStyleClass().add("admin-panel");
    var title = new Label("Menu Management");
    title.getStyleClass().add("home-title");
    var help = new Button("Help");
    help.setId("catalogue-help");
    help.setOnAction(e -> showHelp());
    var home = new Button("Home / Lock");
    home.setId("home-lock");
    home.setOnAction(
        e -> {
          if (confirmClose()) lock.accept("");
        });
    var spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    var header =
        new HBox(
            20,
            new VBox(4, title, new Label(store + " · Staff: " + username), connection),
            spacer,
            help,
            home);
    header.setAlignment(Pos.CENTER_LEFT);
    header.setPadding(new Insets(18, 24, 18, 24));
    setTop(header);
    sidebar.getStyleClass().add("admin-sidebar");
    sidebar.setMinWidth(205);
    sidebar.setPrefWidth(205);
    var productNav = new ToggleButton("Products");
    productNav.getStyleClass().add("admin-nav");
    sections.put("Products", productNav);
    productNav.setId("catalogue-products");
    productNav.setMaxWidth(Double.MAX_VALUE);
    productNav.setOnAction(
        e -> {
          if (discard()) showProducts(false);
        });
    sidebar.getChildren().add(productNav);
    for (String section :
        List.of("Categories", "Ingredients", "Modifiers & Extras", "Store Pricing")) {
      var tab = new ToggleButton(section);
      tab.getStyleClass().add("admin-nav");
      tab.setId("catalogue-" + section.toLowerCase(java.util.Locale.ROOT).split(" ")[0]);
      tab.setMaxWidth(Double.MAX_VALUE);
      tab.setWrapText(true);
      tab.setOnAction(
          e -> {
            if (discard()) {
              productsMode = false;
              open(
                  KINDS.stream()
                      .filter(k -> k.section().equals(section))
                      .findFirst()
                      .orElseThrow());
            }
            selectSidebar();
          });
      sidebar.getChildren().add(tab);
      sections.put(section, tab);
    }
    var combos = new ToggleButton("Combos");
    combos.getStyleClass().add("admin-nav");
    sections.put("Combos", combos);
    combos.setId("catalogue-combos");
    combos.setMaxWidth(Double.MAX_VALUE);
    combos.setOnAction(
        e -> {
          if (discard()) showProducts(true);
        });
    sidebar.getChildren().add(combos);
    setLeft(sidebar);
    body.setPadding(new Insets(18));
    setCenter(body);
    feedback.setWrapText(true);
    feedback.setId("catalogue-feedback");
    feedback.setPadding(new Insets(10, 24, 14, 24));
    setBottom(feedback);
    var reload = new Button("Connect / Reload");
    reload.setOnAction(e -> refresh());
    body.setCenter(reload);
    addEventFilter(KeyEvent.ANY, e -> activity.run());
    addEventFilter(MouseEvent.MOUSE_PRESSED, e -> activity.run());
    addEventFilter(TouchEvent.TOUCH_PRESSED, e -> activity.run());
    search.textProperty().addListener((o, a, b) -> filterChanged(() -> search.setText(a)));
    status.valueProperty().addListener((o, a, b) -> filterChanged(() -> status.setValue(a)));
    filter.valueProperty().addListener((o, a, b) -> filterChanged(() -> filter.setValue(a)));
    status.setId("catalogue-status");
    filter.setId("catalogue-filter");
    list.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (populating || b == null || b.equals(selected)) return;
              if (discard()) editForm(b);
              else {
                populating = true;
                list.getSelectionModel().select(a);
                populating = false;
              }
            });
    refresh();
  }

  private void filterChanged(Runnable restore) {
    if (populating || state == null || closed) return;
    if (discard()) {
      page = 0;
      rows();
    } else {
      populating = true;
      restore.run();
      populating = false;
    }
  }

  private void work(Callable<JsonNode> task, Consumer<JsonNode> success) {
    if (busy || closed) return;
    busy = true;
    long operation = ++generation;
    body.setDisable(true);
    sidebar.setDisable(true);
    feedback.setText("Working…");
    executor.execute(
        () -> {
          try {
            var result = task.call();
            Platform.runLater(
                () -> {
                  if (closed || operation != generation) return;
                  busy = false;
                  body.setDisable(false);
                  sidebar.setDisable(false);
                  connection.setText("Store connected");
                  feedback.setText("");
                  success.accept(result);
                });
          } catch (Exception failure) {
            Platform.runLater(
                () -> {
                  if (closed || operation != generation) return;
                  busy = false;
                  body.setDisable(false);
                  sidebar.setDisable(false);
                  if (failure instanceof TrustedStoreClient.ModuleFailure f) {
                    if (f.status() == 401 || f.status() == 403) {
                      lock.accept("Catalogue access changed. Enter your PIN again.");
                      return;
                    }
                    if (f.status() == 409) {
                      feedback.setText(
                          state == null
                              ? "The store catalogue must be imported before editing. Import it, then reconnect."
                              : "The catalogue changed. Reload to review current values before saving.");
                      return;
                    }
                    if (f.status() == 400) {
                      feedback.setText(
                          "Check required fields, active references, prices, allowed sizes and selection limits.");
                      return;
                    }
                  }
                  connection.setText("Store unavailable");
                  feedback.setText(
                      "Connection failed. Save is unconfirmed. Reconnect or retry the unchanged save.");
                });
          }
        });
  }

  private void refresh() {
    if (!discard()) return;
    work(
        () -> gateway.catalogue("state", "GET", null, token),
        result -> {
          state = result;
          pending = null;
          if (productsMode) showProducts(false);
          else open(current);
          if (firstCategory) {
            firstCategory = false;
            addFirstCategory();
          }
          if (!state.path("warnings").isEmpty())
            feedback.setText(
                state.path("warnings").get(0).asText()
                    + " ("
                    + state.path("warnings").size()
                    + " review notices)");
        });
  }

  private void showProducts(boolean combos) {
    productsMode = true;
    navigationSection = combos ? "Combos" : "Products";
    productWorkspace = new ProductWorkspace(gateway, executor, token);
    productWorkspace.combosOnly(combos);
    body.setCenter(productWorkspace);
    selectSidebar();
  }

  void addFirstCategory() {
    if (state == null) {
      firstCategory = true;
      return;
    }
    open(KINDS.stream().filter(k -> k.table().equals("menu_category")).findFirst().orElseThrow());
    editForm(null);
  }

  private void selectSidebar() {
    sections.forEach((name, tab) -> tab.setSelected(navigationSection.equals(name)));
  }

  private void open(Kind target) {
    if (state == null) return;
    populating = true;
    current = target;
    navigationSection = current.section();
    dirty = false;
    selected = null;
    pending = null;
    page = 0;
    selectSidebar();
    kind.getItems()
        .setAll(KINDS.stream().filter(k -> k.section().equals(current.section())).toList());
    kind.setValue(current);
    kind.setId("catalogue-kind");
    kind.setOnAction(
        e -> {
          if (populating || kind.getValue() == null || kind.getValue().equals(current)) return;
          if (discard()) open(kind.getValue());
          else {
            populating = true;
            kind.setValue(current);
            populating = false;
          }
        });
    search.setText("");
    search.setPromptText("Search names, descriptions or related items");
    search.setId("catalogue-search");
    status.getItems().setAll("All statuses", "Active", "Inactive");
    status.setValue("All statuses");
    setupFilter();
    var reload = new Button("Reload");
    reload.setId("catalogue-reload");
    reload.setOnAction(e -> refresh());
    var controls = new FlowPane(10, 10, kind, search, status, filter, reload);
    if (!state.path("warnings").isEmpty()) {
      var review = new Button("Review notices (" + state.path("warnings").size() + ")");
      review.setOnAction(e -> showNotices());
      controls.getChildren().add(review);
    }
    if (state.path("editable").asBoolean()) {
      var add = new Button("Add new");
      add.setId("catalogue-add");
      add.setOnAction(
          e -> {
            if (discard()) {
              list.getSelectionModel().clearSelection();
              editForm(null);
            }
          });
      controls.getChildren().add(add);
    }
    var instructions = new Label(instructions());
    instructions.setWrapText(true);
    var top = new VBox(10, controls, instructions);
    top.setPadding(new Insets(0, 0, 14, 0));
    body.setTop(top);
    list.setId("catalogue-list");
    list.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    list.getColumns().clear();
    column("Name / assignment", r -> display(current.table(), r));
    column("Status / details", this::summary);
    list.setPlaceholder(
        new Label("No records match. Add a new record if you have editing access."));
    var previous = new Button("Previous");
    previous.setOnAction(
        e -> {
          if (page > 0 && discard()) {
            page--;
            rows();
          }
        });
    var next = new Button("Next");
    next.setOnAction(
        e -> {
          if (discard() && (page + 1) * 50 < filtered().size()) {
            page++;
            rows();
          }
        });
    var listing = new VBox(10, list, new HBox(10, previous, pageLabel, next));
    VBox.setVgrow(list, Priority.ALWAYS);
    listing.setMinWidth(240);
    editorScroll.setFitToWidth(true);
    editorScroll.setMinWidth(250);
    form.setMinWidth(0);
    form.setPadding(new Insets(4, 12, 14, 12));
    var split = new javafx.scene.control.SplitPane(listing, editorScroll);
    split.setDividerPositions(0.5);
    body.setCenter(split);
    editorScroll.setVvalue(0);
    editorScroll.setHvalue(0);
    populating = false;
    rows();
  }

  private void column(String name, java.util.function.Function<JsonNode, String> value) {
    var column = new TableColumn<JsonNode, String>(name);
    column.setCellValueFactory(c -> new ReadOnlyStringWrapper(value.apply(c.getValue())));
    column.setPrefWidth(260);
    list.getColumns().add(column);
  }

  private String instructions() {
    return switch (current.table()) {
      case "pizza" ->
          "Create the pizza, then configure allowed sizes, prices and default ingredients using the selector above.";
      case "menu_item" ->
          "Create the item, then configure its price, burger recipe or extras. Sales operations are still being built.";
      case "burger_recipe", "burger_recipe_component" ->
          "Shared recipe: changes affect every assigned burger. You will review the affected items before saving.";
      case "burger_item_default_component" ->
          "Individual item components are stored separately from the shared recipe. Review both when configuring a burger.";
      case "branch_pizza_price" ->
          "Add the allowed pizza size first, then set its price. Prices are shared across this store's terminals.";
      default ->
          "Changes apply to this store. Deactivate records to retain their history; remove assignments only when intended.";
    };
  }

  private List<JsonNode> records(String table) {
    var result = new ArrayList<JsonNode>();
    state.path("tables").path(table).forEach(result::add);
    return result;
  }

  private String label(String column) {
    return switch (column) {
      case "is_300ml" -> "300 ml item";
      case "is_2l" -> "2 litre item";
      case "cm" -> "Size (cm)";
      case "default_qty" -> "Default quantity";
      case "min_select" -> "Minimum selections";
      case "max_select" -> "Maximum selections";
      case "sort_order" -> "Display order";
      case "is_removable" -> "Customer may remove";
      case "burger_id", "menu_item_id", "salad_id" -> "Menu item";
      case "ingredient_id" -> "Ingredient";
      case "component_id" -> "Burger component";
      case "recipe_id" -> "Shared recipe";
      case "category_id" -> "Menu category";
      case "price_category_id" -> "Ingredient price category";
      case "group_id" -> "Modifier group";
      default -> {
        String text = column.replace("_id", "").replace('_', ' ');
        yield Character.toUpperCase(text.charAt(0)) + text.substring(1);
      }
    };
  }

  private String display(String table, JsonNode row) {
    for (String name : List.of("name", "ingredient_name"))
      if (row.hasNonNull(name)) return row.get(name).asText();
    if (table.equals("pizza_size")) return row.path("cm").longValue() + " cm";
    var parts = new ArrayList<String>();
    state
        .path("schema")
        .path(table)
        .path("references")
        .properties()
        .forEach(
            ref -> {
              if (!ref.getKey().equals("branch_id") && row.hasNonNull(ref.getKey()))
                parts.add(referenceLabel(ref.getValue(), row.get(ref.getKey())));
            });
    return String.join(" / ", parts);
  }

  private String referenceLabel(JsonNode reference, JsonNode value) {
    return records(reference.get(0).asText()).stream()
        .filter(r -> r.path(reference.get(1).asText()).equals(value))
        .map(r -> display(reference.get(0).asText(), r))
        .findFirst()
        .orElse("Unavailable record");
  }

  private String summary(JsonNode row) {
    var parts = new ArrayList<String>();
    if (row.has("active")) parts.add(row.path("active").asBoolean() ? "Active" : "Inactive");
    for (String field :
        List.of("price", "additional_price", "default_qty", "component_type", "description"))
      if (row.hasNonNull(field))
        parts.add(
            label(field)
                + ": "
                + fieldText(
                    field.equals("default_qty")
                        ? "INT"
                        : field.equals("price") || field.equals("additional_price")
                            ? "NUMERIC"
                            : "TEXT",
                    row.get(field)));
    return String.join(" · ", parts);
  }

  private String filterColumn() {
    return state.path("schema").path(current.table()).path("references").properties().stream()
        .map(Map.Entry::getKey)
        .filter(c -> !c.equals("branch_id"))
        .findFirst()
        .orElse(null);
  }

  private void setupFilter() {
    filter.getItems().clear();
    filter.getItems().add(new Choice(null, "All related records"));
    var column = filterColumn();
    filter.setVisible(column != null);
    filter.setManaged(column != null);
    if (column != null) {
      var ref = state.path("schema").path(current.table()).path("references").path(column);
      records(ref.get(0).asText()).stream()
          .sorted(java.util.Comparator.comparing(r -> display(ref.get(0).asText(), r)))
          .forEach(
              r ->
                  filter
                      .getItems()
                      .add(
                          new Choice(r.get(ref.get(1).asText()), display(ref.get(0).asText(), r))));
      filter.setAccessibleText("Filter by " + label(column));
    }
    filter.getSelectionModel().selectFirst();
  }

  private List<JsonNode> filtered() {
    String query = search.getText().strip().toLowerCase(java.util.Locale.ROOT);
    String column = filterColumn();
    return records(current.table()).stream()
        .filter(
            r ->
                (display(current.table(), r) + " " + summary(r))
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains(query))
        .filter(
            r ->
                !r.has("active")
                    || "All statuses".equals(status.getValue())
                    || r.path("active").asBoolean() == "Active".equals(status.getValue()))
        .filter(
            r ->
                column == null
                    || filter.getValue() == null
                    || filter.getValue().value() == null
                    || r.path(column).equals(filter.getValue().value()))
        .sorted(java.util.Comparator.comparing(r -> display(current.table(), r)))
        .toList();
  }

  private void rows() {
    populating = true;
    var data = filtered();
    int start = Math.min(page * 50, data.size()), end = Math.min(start + 50, data.size());
    list.getItems().setAll(data.subList(start, end));
    list.getSelectionModel().clearSelection();
    selected = null;
    form.getChildren().setAll(new Label("Select a record to view its details."));
    inputs.clear();
    dirty = false;
    pending = null;
    pageLabel.setText((data.isEmpty() ? 0 : start + 1) + "–" + end + " of " + data.size());
    populating = false;
  }

  private boolean keyColumn(String column) {
    for (var key : state.path("schema").path(current.table()).path("keys"))
      if (key.asText().equals(column)) return true;
    return false;
  }

  private boolean generatedColumn(String column) {
    var spec = state.path("schema").path(current.table());
    return spec.path("keys").size() == 1
        && keyColumn(column)
        && !spec.path("references").has(column);
  }

  private void changed() {
    if (!populating) {
      dirty = true;
      pending = null;
    }
  }

  private void editForm(JsonNode row) {
    populating = true;
    editorScroll.setVvalue(0);
    editorScroll.setHvalue(0);
    selected = row;
    dirty = false;
    pending = null;
    inputs.clear();
    form.getChildren().clear();
    var title = new Label(row == null ? "New " + current.label() : display(current.table(), row));
    title.getStyleClass().add("admin-section-title");
    title.setMaxWidth(Double.MAX_VALUE);
    title.setAlignment(Pos.CENTER_LEFT);
    title.setWrapText(true);
    title.setMinWidth(0);
    form.getChildren().add(title);
    var spec = state.path("schema").path(current.table());
    boolean editable = state.path("editable").asBoolean();
    var fieldOrder =
        List.of(
            "name",
            "ingredient_name",
            "description",
            "category_id",
            "pizza_category_id",
            "price_category_id",
            "pizza_id",
            "menu_item_id",
            "burger_id",
            "salad_id",
            "recipe_id",
            "group_id",
            "ingredient_id",
            "component_id",
            "pizza_size_id",
            "pizza_base_option_id",
            "component_type",
            "cm",
            "price",
            "additional_price",
            "default_qty",
            "protein_required",
            "protein_quantity_required",
            "required",
            "min_select",
            "max_select",
            "is_removable",
            "sort_order",
            "active",
            "seasonal",
            "is_300ml",
            "is_2l");
    var fields = new ArrayList<>(spec.path("columns").properties());
    fields.sort(
        java.util.Comparator.comparingInt(
            f -> {
              int index = fieldOrder.indexOf(f.getKey());
              return index < 0 ? 100 : index;
            }));
    for (var field : fields) {
      String column = field.getKey();
      if (column.equals("branch_id") || generatedColumn(column)) continue;
      var value = row == null ? null : row.get(column);
      var caption =
          new Label(label(column) + (field.getValue().path("required").asBoolean() ? " *" : ""));
      Node input;
      if (!editable) {
        input =
            new Label(
                spec.path("references").has(column) && value != null && !value.isNull()
                    ? referenceLabel(spec.path("references").get(column), value)
                    : value == null || value.isNull()
                        ? "—"
                        : fieldText(field.getValue().path("type").asText(), value));
        ((Label) input).setWrapText(true);
      } else if (spec.path("references").has(column)) {
        var ref = spec.path("references").get(column);
        var select = new ComboBox<Choice>();
        select.setMaxWidth(Double.MAX_VALUE);
        select.setMinWidth(0);
        if (!field.getValue().path("required").asBoolean())
          select.getItems().add(new Choice(null, "None"));
        records(ref.get(0).asText()).stream()
            .filter(
                r ->
                    !r.has("active")
                        || r.path("active").asBoolean()
                        || value != null && r.get(ref.get(1).asText()).equals(value))
            .sorted(java.util.Comparator.comparing(r -> display(ref.get(0).asText(), r)))
            .forEach(
                r ->
                    select
                        .getItems()
                        .add(
                            new Choice(
                                r.get(ref.get(1).asText()),
                                display(ref.get(0).asText(), r)
                                    + (r.has("active") && !r.path("active").asBoolean()
                                        ? " (inactive)"
                                        : ""))));
        if (value != null)
          select.getItems().stream()
              .filter(c -> value.equals(c.value()))
              .findFirst()
              .ifPresent(select::setValue);
        else if (column.equals(filterColumn())
            && filter.getValue() != null
            && filter.getValue().value() != null)
          select.getItems().stream()
              .filter(c -> filter.getValue().value().equals(c.value()))
              .findFirst()
              .ifPresent(select::setValue);
        select.setDisable(row != null && keyColumn(column));
        select.valueProperty().addListener((o, a, b) -> changed());
        input = select;
      } else if (field.getValue().path("type").asText().equals("BOOLEAN")) {
        var check = new CheckBox();
        check.setSelected(
            value == null
                ? column.equals("active") || column.equals("is_removable")
                : value.asBoolean());
        check.selectedProperty().addListener((o, a, b) -> changed());
        input = check;
      } else {
        String type = field.getValue().path("type").asText();
        boolean number = List.of("INT", "NUMERIC", "DOUBLE").contains(type);
        String initial =
            value == null || value.isNull()
                ? number ? defaultNumber(column) : ""
                : fieldText(type, value);
        TextInputControl text;
        if (column.equals("description")) {
          var area = new TextArea(initial);
          area.setPrefRowCount(3);
          area.setWrapText(true);
          text = area;
        } else text = new TextField(initial);
        text.setPromptText(number ? "Number" : label(column));
        text.textProperty().addListener((o, a, b) -> changed());
        input = text;
      }
      input.setId("catalogue-field-" + column);
      input.setAccessibleText(label(column));
      caption.setLabelFor(input);
      form.getChildren().addAll(caption, input);
      if (editable) inputs.put(column, input);
    }
    if (row != null && List.of("pizza", "menu_item").contains(current.table())) {
      var recipe = new Button("Ingredients / Recipe");
      recipe.setId("catalogue-recipe");
      var product =
          new za.co.onlinepos.contracts.Recipes.Product(
              current.table(),
              row.path(current.table().equals("pizza") ? "pizza_id" : "id").asLong());
      recipe.setOnAction(
          e -> {
            if (!discard()) return;
            recipeEditor =
                new RecipeEditor(
                    gateway,
                    executor,
                    token,
                    product,
                    () ->
                        feedback.setText(
                            "Recipe saved. Reload the catalogue to see updated records."));
            var back = new Button("Back to product");
            back.setOnAction(
                event -> {
                  if (recipeEditor.confirmClose()) {
                    recipeEditor.close();
                    recipeEditor = null;
                    open(current);
                  }
                });
            body.setCenter(new VBox(10, back, recipeEditor));
          });
      form.getChildren().add(recipe);
    }
    var affected = affected(row);
    if (!affected.isEmpty()) {
      var warning = new Label("Shared recipe affects: " + String.join(", ", affected));
      warning.setWrapText(true);
      warning.setId("catalogue-affected");
      form.getChildren().add(warning);
    }
    if (editable) {
      var save = new Button("Save");
      save.setId("catalogue-save");
      save.setDefaultButton(true);
      save.setOnAction(e -> save(false));
      var actions = new FlowPane(10, 10, save);
      if (row != null && REMOVABLE.contains(current.table())) {
        var remove = new Button("Remove assignment");
        remove.setId("catalogue-remove");
        remove.setOnAction(
            e -> {
              if (confirm(
                  "Remove assignment?",
                  "This removes the selected relationship or price from the current catalogue. Existing draft history is retained."))
                save(true);
            });
        actions.getChildren().add(remove);
      }
      form.getChildren().add(actions);
    } else
      form.getChildren()
          .add(new Label("Read-only. Catalogue editing permission is required to make changes."));
    populating = false;
  }

  private String fieldText(String type, JsonNode value) {
    return switch (type) {
      case "INT" -> Long.toString(value.longValue());
      case "NUMERIC", "DOUBLE" -> value.decimalValue().setScale(2).toPlainString();
      default -> value.asText();
    };
  }

  private String defaultNumber(String column) {
    return List.of("default_qty", "protein_quantity_required", "max_select", "cm").contains(column)
        ? "1"
        : "0";
  }

  private List<String> affected(JsonNode row) {
    if (row == null
        || !List.of("burger_recipe", "burger_recipe_component").contains(current.table()))
      return List.of();
    return records("burger_recipe_assignment").stream()
        .filter(a -> a.path("recipe_id").equals(row.path("recipe_id")))
        .map(
            a ->
                records("menu_item").stream()
                    .filter(i -> i.path("id").equals(a.path("burger_id")))
                    .map(i -> i.path("name").asText())
                    .findFirst()
                    .orElse("Menu item"))
        .sorted()
        .toList();
  }

  private void save(boolean remove) {
    try {
      if (pending == null || pending.changes().getFirst().remove() != remove) {
        var data = new LinkedHashMap<String, Object>();
        if (selected != null)
          selected.properties().forEach(e -> data.put(e.getKey(), e.getValue()));
        if (!remove)
          for (var field : inputs.entrySet()) {
            var control = field.getValue();
            Object value;
            if (control instanceof CheckBox check) value = check.isSelected();
            else if (control instanceof ComboBox<?> select)
              value = select.getValue() == null ? null : ((Choice) select.getValue()).value();
            else {
              String text = ((TextInputControl) control).getText();
              if (!field.getKey().equals("description")) text = text.strip();
              String type =
                  state
                      .path("schema")
                      .path(current.table())
                      .path("columns")
                      .path(field.getKey())
                      .path("type")
                      .asText();
              if (text.isEmpty()) value = null;
              else
                value =
                    switch (type) {
                      case "INT" -> Long.parseLong(text);
                      case "NUMERIC", "DOUBLE" -> new BigDecimal(text);
                      default -> text;
                    };
            }
            if (value == null
                && state
                    .path("schema")
                    .path(current.table())
                    .path("columns")
                    .path(field.getKey())
                    .path("required")
                    .asBoolean())
              throw new IllegalArgumentException("Please complete " + label(field.getKey()) + ".");
            data.put(field.getKey(), value);
          }
        JsonNode proposed = JsonFiles.JSON.valueToTree(data);
        var affected = affected(selected != null ? selected : proposed);
        if (!affected.isEmpty()
            && !confirm(
                "Change shared recipe?",
                "These items use this recipe: "
                    + String.join(", ", affected)
                    + ". Save the shared change?")) return;
        List<Long> key = null;
        if (selected != null) {
          key = new ArrayList<>();
          for (var k : state.path("schema").path(current.table()).path("keys"))
            key.add(selected.get(k.asText()).longValue());
        }
        pending =
            new CatalogueManagement.Edit(
                UUID.randomUUID(),
                state.path("revision").asLong(),
                List.of(new CatalogueManagement.Change(current.table(), key, data, remove)),
                !affected.isEmpty());
      }
      var request = pending;
      work(
          () -> gateway.catalogue("records", "PUT", request, token),
          result -> {
            dirty = false;
            pending = null;
            form.getChildren().clear();
            inputs.clear();
            selected = null;
            work(
                () -> gateway.catalogue("state", "GET", null, token),
                fresh -> {
                  state = fresh;
                  open(current);
                  feedback.setText(
                      "Saved to this store."
                          + (fresh.path("warnings").isEmpty()
                              ? ""
                              : " Review notices: " + fresh.path("warnings").size()));
                });
          });
    } catch (IllegalArgumentException e) {
      feedback.setText("Check required fields and numeric values before saving.");
    }
  }

  private void showHelp() {
    var help = new Dialog<Void>();
    help.setTitle("Menu Management Help");
    help.setHeaderText("Find out what each workspace and product section does");
    help.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    help.setResizable(true);
    if (getScene() != null) help.initOwner(getScene().getWindow());
    var searchHelp = new TextField();
    searchHelp.setPromptText("Search help, for example: ingredients, prices or save");
    searchHelp.setId("catalogue-help-search");
    var topics = new VBox(16);
    topics.setPadding(new Insets(12));
    var entries =
        List.of(
            new String[] {
              "Products",
              "Start here to create or edit a product. Select a category in the tree, search the product list, then select a product to open its editor. Pizza sizes are grouped under their product. Add Product starts a new draft; Reload reads the latest saved data."
            },
            new String[] {
              "Save Product, Cancel and unsaved changes",
              "Edit several product sections, then click Save Product once to save them together. Switching product tabs keeps your draft. Cancel discards the product draft. Switching products or leaving the workspace asks before discarding unsaved changes. If saving fails, your draft stays open: review the message and retry. After a conflict, reload the latest saved product before editing again."
            },
            new String[] {
              "General Details",
              "Set the product name, description, category and display order. Display order controls where the product appears. Standard products may also have 300 ml and 2 litre flags. Configure Ingredients / Recipe adds recipe controls when this product needs them; choose the appropriate ingredient family."
            },
            new String[] {
              "Ingredients / Recipe",
              "Choose the ingredients included in this product. The picker supports search, filters and selecting several ingredients at once. Select All and Clear apply to the filtered list. Review quantities, removability and display order where offered. Unavailable linked ingredients remain visible for review. Applying the picker updates your draft; Save Product commits it. Removing a link does not delete the reusable ingredient."
            },
            new String[] {
              "New ingredients and ingredient price categories",
              "Create a missing ingredient from the product ingredient picker. It stays in the draft until Save Product succeeds. A price category groups ingredients for surcharge pricing. To change an existing reusable ingredient for every product that uses it, use the Ingredients workspace."
            },
            new String[] {
              "Shared burger recipes and Restore Inherited",
              "A burger can inherit ingredients from a named shared recipe, with its own additions, exclusions and property overrides. Inherited means the value comes from the shared recipe; added means it belongs to this product; overridden means this product changes a shared value; excluded means this product omits it. Normal product edits affect only this product. Restore Inherited removes its local override or exclusion. Edit Shared Recipe is a separate save that can affect every linked product; save or discard your product draft first and review the affected products."
            },
            new String[] {
              "Sizes / Variants",
              "For pizzas, select which reusable sizes this product offers. Each size has its own store price and customization settings. Removing a size also removes its store price when the product is saved. Standard products have one price; their size flags are in General Details."
            },
            new String[] {
              "Modifiers / Extras",
              "Attach reusable modifier groups such as side choices or sauces. Required groups and minimum/maximum selections control what the cashier must choose. Options are the choices within each group. Configure the offered extras and customization rules for each priced variant. Product changes are staged until Save Product."
            },
            new String[] {
              "Pricing",
              "Set the selling price for this store, including a separate price for each offered pizza size. Extra ingredient and base surcharges are managed in Store Pricing. Review all offered sizes before saving."
            },
            new String[] {
              "Availability and Archive Product",
              "Available for sale controls whether the product can be sold. Archive Product stages making it inactive; click Save Product to apply that change. Historical orders are retained."
            },
            new String[] {
              "Combos and Combo Configuration",
              "The Combos workspace shows combo products. Combo Configuration appears when the product is configured as a combo. Define its slots, permitted choices and selection limits there. These settings are part of Save Product. The cashier builds customer orders in the sales screen."
            },
            new String[] {
              "Categories",
              "Organize products into categories and nested child categories. Add Category creates a category; Edit Category changes its name, parent, display order or archive status. Move a product by changing its category in General Details. A category cannot be its own ancestor. Before archiving a category, move or archive its products and child categories. Category changes are separate from a product save."
            },
            new String[] {
              "Ingredients workspace",
              "Manage reusable ingredients, burger ingredients, ingredient price categories and shared burger recipes. Global edits can affect products that use these records. Inactive ingredients are retained so existing links can still be reviewed. Use the product editor to change only one product's recipe."
            },
            new String[] {
              "Modifiers & Extras workspace",
              "Manage reusable modifier groups, their options and pizza bases. These global records can be used by multiple products. Attach the relevant groups and configure product-specific behavior in the product editor."
            },
            new String[] {
              "Store Pricing workspace",
              "Manage this store's product prices, pizza size prices, reusable pizza sizes, ingredient surcharges, burger ingredient surcharges and pizza base surcharges. These are separate global edits; Save Product applies only the open product draft."
            },
            new String[] {
              "Global record controls and Home / Lock",
              "In a global workspace, choose a record type, then use search and the available filters to find a record. Select it to edit its fields; Save applies that record's changes. Reference fields select another reusable record by name. Required fields must be completed. Home / Lock leaves Menu Management and locks the session, checking for unsaved changes first."
            });
    var empty = new Label("No matching help topics. Try another search.");
    Runnable filterHelp =
        () -> {
          String query = searchHelp.getText().strip().toLowerCase(java.util.Locale.ROOT);
          topics.getChildren().clear();
          for (var entry : entries) {
            if (!(entry[0] + " " + entry[1]).toLowerCase(java.util.Locale.ROOT).contains(query))
              continue;
            var heading = new Label(entry[0]);
            heading.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");
            var description = new Label(entry[1]);
            description.setWrapText(true);
            description.setMaxWidth(Double.MAX_VALUE);
            topics.getChildren().add(new VBox(5, heading, description));
          }
          if (topics.getChildren().isEmpty()) topics.getChildren().add(empty);
        };
    searchHelp.textProperty().addListener((o, a, b) -> filterHelp.run());
    filterHelp.run();
    var scroll = new ScrollPane(topics);
    scroll.setFitToWidth(true);
    scroll.setPrefViewportHeight(470);
    scroll.setPrefViewportWidth(720);
    VBox.setVgrow(scroll, Priority.ALWAYS);
    help.getDialogPane().setContent(new VBox(12, searchHelp, scroll));
    help.getDialogPane().addEventFilter(KeyEvent.ANY, e -> activity.run());
    help.getDialogPane().addEventFilter(MouseEvent.MOUSE_PRESSED, e -> activity.run());
    help.getDialogPane().addEventFilter(TouchEvent.TOUCH_PRESSED, e -> activity.run());
    dialog = help;
    help.setOnHidden(
        e -> {
          if (dialog == help) dialog = null;
        });
    help.show();
    searchHelp.requestFocus();
  }

  private void showNotices() {
    var text =
        new Label(
            String.join(
                "\n\n", state.path("warnings").valueStream().map(JsonNode::asText).toList()));
    text.setWrapText(true);
    text.setMaxWidth(650);
    var scroll = new ScrollPane(text);
    scroll.setFitToWidth(true);
    scroll.setPrefViewportHeight(400);
    var notice = new Alert(Alert.AlertType.INFORMATION);
    notice.setTitle("Catalogue review notices");
    notice.setHeaderText("Existing recipes with inactive ingredients");
    notice.getDialogPane().setContent(scroll);
    if (getScene() != null) notice.initOwner(getScene().getWindow());
    notice.getDialogPane().addEventFilter(KeyEvent.ANY, e -> activity.run());
    notice.getDialogPane().addEventFilter(MouseEvent.MOUSE_PRESSED, e -> activity.run());
    notice.getDialogPane().addEventFilter(TouchEvent.TOUCH_PRESSED, e -> activity.run());
    dialog = notice;
    notice.showAndWait();
    dialog = null;
  }

  private boolean discard() {
    if (productWorkspace != null) {
      if (!productWorkspace.confirmClose()) return false;
      if (productWorkspace.savedState() != null) state = productWorkspace.savedState();
      productWorkspace.close();
      productWorkspace = null;
    }
    if (recipeEditor != null) {
      if (!recipeEditor.confirmClose()) return false;
      recipeEditor.close();
      recipeEditor = null;
    }

    if (itemWorkbench != null) {
      if (itemWorkbench.dirty()) {
        var confirm =
            new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "Discard staged item settings or an unconfirmed draft?",
                ButtonType.YES,
                ButtonType.CANCEL);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.YES) return false;
      }
      itemWorkbench.close();
      itemWorkbench = null;
    }
    if (!dirty) return true;
    boolean result = confirm("Unsaved changes", "Discard the unsaved changes and continue?");
    if (result) {
      dirty = false;
      pending = null;
    }
    return result;
  }

  private boolean confirm(String title, String message) {
    var alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
    alert.setTitle(title);
    alert.setHeaderText(title);
    if (getScene() != null) alert.initOwner(getScene().getWindow());
    alert.getDialogPane().addEventFilter(KeyEvent.ANY, e -> activity.run());
    alert.getDialogPane().addEventFilter(MouseEvent.MOUSE_PRESSED, e -> activity.run());
    alert.getDialogPane().addEventFilter(TouchEvent.TOUCH_PRESSED, e -> activity.run());
    dialog = alert;
    var result = alert.showAndWait();
    dialog = null;
    return !closed
        && result.filter(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE).isPresent();
  }

  boolean confirmClose() {
    return !busy && discard();
  }

  @Override
  public void close() {
    if (productWorkspace != null) productWorkspace.close();
    if (itemWorkbench != null) itemWorkbench.close();
    if (recipeEditor != null) recipeEditor.close();
    closed = true;
    ++generation;
    if (dialog != null) dialog.close();
    inputs
        .values()
        .forEach(
            n -> {
              if (n instanceof TextInputControl text) text.clear();
            });
    inputs.clear();
    form.getChildren().clear();
    list.getItems().clear();
    state = null;
    selected = null;
    pending = null;
    dirty = false;
  }
}
