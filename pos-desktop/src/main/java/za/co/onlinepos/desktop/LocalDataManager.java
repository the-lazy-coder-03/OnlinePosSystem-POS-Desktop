package za.co.onlinepos.desktop;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import za.co.onlinepos.contracts.DevData;
import za.co.onlinepos.installation.JsonFiles;

/** One real H2 workflow. This screen does not expose arbitrary SQL or protected tables. */
final class LocalDataManager extends BorderPane implements AutoCloseable {
  private final DevGateway gateway;
  private final ExecutorService executor;
  private final Label connection = new Label("H2 · Checking connection…"),
      notice = new Label(),
      nameError = new Label(),
      orderError = new Label(),
      pageLabel = new Label();
  private final TableView<DevData.Category> table = new TableView<>();
  private final TextField search = new TextField(),
      name = new TextField(),
      order = new TextField("0");
  private final ComboBox<String> state = new ComboBox<>();
  private final CheckBox active = new CheckBox("Active"),
      archived = new CheckBox("Archived (clear to restore)");
  private final Button refresh = new Button("Refresh"),
      add = new Button("Add"),
      edit = new Button("Edit"),
      save = new Button("Save"),
      cancel = new Button("Cancel"),
      delete = new Button("Delete"),
      previous = new Button("Previous"),
      next = new Button("Next");
  private final VBox editor = new VBox(8);
  private final Runnable back;
  private DevData.Category current;
  private DevData.Save retry;
  private boolean dirty, busy, closed, filling, conflict;
  private int page;
  private long generation;

  LocalDataManager(DevGateway gateway, ExecutorService executor, Runnable back) {
    this.gateway = gateway;
    this.executor = executor;
    this.back = back;
    setPadding(new Insets(20));
    getStyleClass().add("local-data-manager");
    var title = new Label("Local Data Manager");
    title.getStyleClass().add("home-title");
    var leave = new Button("Back to grid");
    leave.setOnAction(
        event -> {
          if (discard()) {
            close();
            back.run();
          }
        });
    setTop(
        new VBox(
            8,
            new HBox(18, title, leave),
            connection,
            new Label("Development only · category Delete disables records"),
            notice));
    var entities = new ListView<String>();
    entities.getItems().add("Menu Categories");
    entities.getSelectionModel().selectFirst();
    entities.setPrefWidth(170);
    setLeft(entities);
    search.setPromptText("Search category names");
    search.setId("data-search");
    state.getItems().addAll("Active", "Disabled", "Archived", "All");
    state.setValue("Active");
    state.setId("data-state");
    var find = new Button("Search");
    find.setOnAction(event -> refresh(0));
    search.setOnAction(event -> refresh(0));
    state.setOnAction(event -> refresh(0));
    column("ID", row -> Integer.toString(row.id()), 65);
    column("Name", DevData.Category::name, 240);
    column("Sort order", row -> Integer.toString(row.sortOrder()), 100);
    column(
        "Status", row -> row.archived() ? "Archived" : row.active() ? "Active" : "Disabled", 100);
    column("Revision", row -> Long.toString(row.revision()), 80);
    table.setId("data-categories");
    table.setPlaceholder(new Label("No categories. Use Add to create the first one."));
    table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> buttons());
    var tools =
        new javafx.scene.layout.FlowPane(8, 8, search, state, find, refresh, add, edit, delete);
    var centre = new VBox(12, tools, table, new HBox(10, previous, pageLabel, next));
    VBox.setVgrow(table, Priority.ALWAYS);
    centre.setPadding(new Insets(14));
    setCenter(centre);
    name.setId("category-name");
    order.setId("category-sort-order");
    active.setId("category-active");
    save.setId("category-save");
    cancel.setId("category-cancel");
    add.setId("category-add");
    edit.setId("category-edit");
    delete.setId("category-delete");
    refresh.setId("category-refresh");
    nameError.getStyleClass().add("field-error");
    orderError.getStyleClass().add("field-error");
    editor
        .getChildren()
        .addAll(
            new Label("Category editor"),
            new Label("Name"),
            name,
            nameError,
            new Label("Sort order"),
            order,
            orderError,
            active,
            archived,
            new HBox(8, save, cancel));
    editor.setPadding(new Insets(14));
    editor.setPrefWidth(270);
    setRight(editor);
    name.textProperty().addListener((o, a, b) -> changed());
    order.textProperty().addListener((o, a, b) -> changed());
    archived
        .selectedProperty()
        .addListener(
            (o, a, b) -> {
              if (b) active.setSelected(false);
              changed();
            });
    active.selectedProperty().addListener((o, a, b) -> changed());
    add.setOnAction(
        event -> {
          if (discard()) fill(null);
        });
    edit.setOnAction(
        event -> {
          if (discard()) fill(table.getSelectionModel().getSelectedItem());
        });
    cancel.setOnAction(
        event -> {
          if (discard()) clear();
        });
    refresh.setOnAction(event -> refresh(page));
    previous.setOnAction(event -> refresh(Math.max(0, page - 1)));
    next.setOnAction(event -> refresh(page + 1));
    save.setOnAction(event -> save());
    delete.setOnAction(event -> disable());
    clear();
    refresh(0);
  }

  private void column(
      String text, java.util.function.Function<DevData.Category, String> value, int width) {
    var column = new TableColumn<DevData.Category, String>(text);
    column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
    column.setPrefWidth(width);
    table.getColumns().add(column);
  }

  private void changed() {
    if (!filling) {
      dirty = true;
      retry = null;
      buttons();
    }
  }

  private void fill(DevData.Category value) {
    filling = true;
    current = value;
    name.setText(value == null ? "" : value.name());
    order.setText(value == null ? "0" : Integer.toString(value.sortOrder()));
    active.setSelected(value == null || value.active());
    archived.setSelected(value != null && value.archived());
    filling = false;
    dirty = false;
    conflict = false;
    retry = null;
    editor.setDisable(false);
    nameError.setText("");
    orderError.setText("");
    buttons();
  }

  private void clear() {
    fill(null);
    editor.setDisable(true);
  }

  private void buttons() {
    refresh.setDisable(busy);
    add.setDisable(busy);
    edit.setDisable(busy || table.getSelectionModel().getSelectedItem() == null);
    delete.setDisable(
        busy
            || table.getSelectionModel().getSelectedItem() == null
            || !table.getSelectionModel().getSelectedItem().active());
    save.setDisable(busy || conflict);
    cancel.setDisable(busy);
    previous.setDisable(busy || page == 0);
    next.setDisable(busy || table.getItems().size() < 50);
    search.setDisable(busy);
    state.setDisable(busy);
    name.setDisable(busy);
    order.setDisable(busy);
    active.setDisable(busy || archived.isSelected());
    archived.setDisable(busy);
  }

  boolean discard() {
    if (busy) return false;
    if (!dirty) return true;
    var dialog =
        new Alert(
            Alert.AlertType.CONFIRMATION,
            "Discard unsaved category changes?",
            ButtonType.YES,
            ButtonType.NO);
    return dialog.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
  }

  private <T> void work(Callable<T> task, Consumer<T> done) {
    busy = true;
    buttons();
    long attempt = ++generation;
    executor.execute(
        () -> {
          try {
            T result = task.call();
            Platform.runLater(
                () -> {
                  if (closed || attempt != generation) return;
                  busy = false;
                  done.accept(result);
                  buttons();
                });
          } catch (Exception failure) {
            Platform.runLater(
                () -> {
                  if (closed || attempt != generation) return;
                  busy = false;
                  connection.setText("H2 · Operation not confirmed");
                  notice.setText(
                      failure instanceof DevGateway.Failure
                          ? failure.getMessage()
                          : "Connection unavailable. Your edits are retained; retry when connected.");
                  if (failure instanceof DevGateway.Failure f) {
                    if (f.field.equals("name")) nameError.setText(f.getMessage());
                    if (f.field.equals("sortOrder")) orderError.setText(f.getMessage());
                    if (f.status == 409 && !f.code.equals("DUPLICATE")) conflict = true;
                  }
                  buttons();
                });
          }
        });
  }

  private void refresh(int requested) {
    if (!discard()) return;
    String query = search.getText();
    String selectedState = state.getValue().toLowerCase(java.util.Locale.ROOT);
    work(
        () -> {
          gateway.status();
          return JsonFiles.JSON.treeToValue(
              gateway.call(
                  "/categories?search="
                      + URLEncoder.encode(query, StandardCharsets.UTF_8)
                      + "&state="
                      + selectedState
                      + "&page="
                      + requested
                      + "&size=50",
                  "GET",
                  null),
              DevData.Page.class);
        },
        result -> {
          page = result.page();
          table.getItems().setAll(result.records());
          pageLabel.setText("Page " + (page + 1) + " · " + result.total() + " categories");
          connection.setText("H2 · Connected · development");
          notice.setText("");
          clear();
        });
  }

  private void save() {
    nameError.setText("");
    orderError.setText("");
    if (name.getText().strip().isEmpty() || name.getText().strip().length() > 200) {
      nameError.setText("Enter a name of 1–200 characters");
      return;
    }
    int position;
    try {
      position = Integer.parseInt(order.getText().strip());
    } catch (NumberFormatException invalid) {
      orderError.setText("Enter an integer sort order");
      return;
    }
    if (retry == null)
      retry =
          new DevData.Save(
              UUID.randomUUID(),
              current == null ? null : current.revision(),
              name.getText(),
              position,
              active.isSelected(),
              archived.isSelected());
    submit(current == null ? null : current.id(), retry);
  }

  private void submit(Integer id, DevData.Save command) {
    work(
        () ->
            JsonFiles.JSON.treeToValue(
                gateway.call(
                    id == null ? "/categories" : "/categories/" + id,
                    id == null ? "POST" : "PUT",
                    command),
                DevData.Category.class),
        result -> {
          dirty = false;
          retry = null;
          fill(result);
          connection.setText("H2 · Connected · development");
          notice.setText("Saved to H2.");
          updateTable(result);
        });
  }

  private void updateTable(
      DevData.Category result) { // Refresh server state without discarding a confirmed editor.
    String query = search.getText();
    String selectedState = state.getValue().toLowerCase(java.util.Locale.ROOT);
    work(
        () ->
            JsonFiles.JSON.treeToValue(
                gateway.call(
                    "/categories?search="
                        + URLEncoder.encode(query, StandardCharsets.UTF_8)
                        + "&state="
                        + selectedState
                        + "&page="
                        + page
                        + "&size=50",
                    "GET",
                    null),
                DevData.Page.class),
        list -> {
          table.getItems().setAll(list.records());
          pageLabel.setText("Page " + (page + 1) + " · " + list.total() + " categories");
          notice.setText("Saved category #" + result.id() + " to H2.");
        });
  }

  private void disable() {
    var row = table.getSelectionModel().getSelectedItem();
    if (row == null || !discard()) return;
    var confirm =
        new Alert(
            Alert.AlertType.CONFIRMATION,
            "Disable ‘" + row.name() + "’? Existing product references will be preserved.",
            ButtonType.YES,
            ButtonType.NO);
    if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
    fill(row);
    active.setSelected(false);
    retry = new DevData.Save(UUID.randomUUID(), row.revision(), row.name(), row.sortOrder(), false);
    submit(row.id(), retry);
  }

  @Override
  public void close() {
    closed = true;
    ++generation;
  }
}
