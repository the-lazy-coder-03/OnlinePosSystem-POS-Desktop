package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import za.co.onlinepos.contracts.CustomerProfile;
import za.co.onlinepos.contracts.Customers;
import za.co.onlinepos.installation.TrustedStoreClient;

/** Persistent customer UI; the gateway is the only I/O boundary. */
final class CustomersPanel extends BorderPane implements AutoCloseable {
  private final ModuleGateway gateway;
  private final ExecutorService executor;
  private final String token;
  private final Runnable activity;
  private final TableView<JsonNode> table = new TableView<>();
  private final TextField search = new TextField();
  private final CheckBox archived = new CheckBox("Archived customers");
  private final Label feedback = new Label(),
      details = new Label("Select a customer"),
      sync = new Label();
  private final Button add = new Button("Add customer"),
      edit = new Button("Edit profile"),
      archive = new Button("Archive"),
      ban = new Button("Ban"),
      history = new Button("Draft / status history"),
      previous = new Button("Previous"),
      next = new Button("Next");
  private final PauseTransition debounce = new PauseTransition(Duration.millis(300));
  private final ArrayDeque<String> cursors = new ArrayDeque<>();
  private JsonNode context, selected;
  private String cursor = "", nextCursor = "";
  private boolean closed, busy;
  private long generation;
  private Dialog<?> dialog;
  private final VBox directory = new VBox(12);
  private final TableView<JsonNode> conflicts = new TableView<>();
  private int conflictPage;

  CustomersPanel(
      ModuleGateway gateway,
      ExecutorService executor,
      String token,
      Runnable home,
      Runnable activity) {
    this.gateway = gateway;
    this.executor = executor;
    this.token = token;
    this.activity = activity;
    setId("customers-panel");
    setPadding(new Insets(20));
    var title = new Label("Customers");
    title.getStyleClass().add("home-title");
    var back = new Button("Home / Lock");
    back.setId("home-lock");
    back.setOnAction(
        e -> {
          if (!(gateway instanceof DevGateway) || confirmClose()) home.run();
        });
    setTop(
        new VBox(
            8,
            new HBox(20, title, back),
            new Label(
                gateway instanceof PreviewGateway
                    ? "Preview — fictional customers; changes reset on exit"
                    : "Customer profiles saved on the store Master"),
            sync));
    feedback.setId("customers-feedback");
    feedback.setWrapText(true);
    setBottom(feedback);
    addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> activity.run());
    addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> activity.run());
    work(() -> gateway.customers("/context", "GET", null, token), this::setup);
  }

  private boolean allowed(String permission) {
    return context.path("permissions").valueStream().anyMatch(n -> n.asText().equals(permission));
  }

  private void work(Callable<JsonNode> task, Consumer<JsonNode> success) {
    if (closed || busy) return;
    busy = true;
    long operation = ++generation;
    if (getCenter() != null) getCenter().setDisable(true);
    feedback.setText("Working…");
    executor.execute(
        () -> {
          try {
            var result = task.call();
            Platform.runLater(
                () -> {
                  if (closed || generation != operation) return;
                  busy = false;
                  if (getCenter() != null) getCenter().setDisable(false);
                  feedback.setText("");
                  success.accept(result);
                });
          } catch (Exception failure) {
            Platform.runLater(
                () -> {
                  if (closed || generation != operation) return;
                  busy = false;
                  if (getCenter() != null) getCenter().setDisable(false);
                  int status =
                      failure instanceof TrustedStoreClient.ModuleFailure f ? f.status() : 503;
                  feedback.setText(
                      status == 409
                          ? "This customer changed or a contact already exists. Reload, or review Account Conflicts."
                          : status == 401 || status == 403
                              ? "Your session or customer permissions changed. Return Home and enter your PIN again."
                              : status == 400
                                  ? "Check the customer details and try again."
                                  : status == 404
                                      ? "Customer no longer available. Refresh the directory."
                                      : "Store unavailable. Your changes have not been confirmed; retry when connected.");
                });
          }
        });
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private static TableColumn<JsonNode, String> column(
      String title, java.util.function.Function<JsonNode, String> value, double width) {
    var col = new TableColumn<JsonNode, String>(title);
    col.setPrefWidth(width);
    col.setCellValueFactory(c -> new ReadOnlyStringWrapper(value.apply(c.getValue())));
    return col;
  }

  private void setup(JsonNode value) {
    context = value;
    table.setId("customers");
    search.setId("search");
    archived.setId("customer-archived");
    previous.setId("customer-previous");
    next.setId("customer-next");
    search.setPromptText("Search name, either phone or email");
    table.getColumns().add(column("Customer", n -> n.path("name").asText(), 190));
    table.getColumns().add(column("Phone", n -> n.path("profile").path("phone1").asText(), 140));
    table.getColumns().add(column("Phone 2", n -> n.path("profile").path("phone2").asText(), 140));
    table.getColumns().add(column("Address", CustomersPanel::address, 330));
    table
        .getColumns()
        .add(
            column(
                "Status",
                n ->
                    n.path("banned").asBoolean()
                        ? "BANNED"
                        : n.path("archived").asBoolean() ? "Archived" : "Active",
                110));
    table.setPlaceholder(
        new Label("No customers match. Add a customer to start your store directory."));
    table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> select(b));
    debounce.setOnFinished(
        e -> {
          cursor = "";
          cursors.clear();
          load();
        });
    search.textProperty().addListener((o, a, b) -> debounce.playFromStart());
    archived.setOnAction(
        e -> {
          cursor = "";
          cursors.clear();
          load();
        });
    var lookup = new Button("Exact phone lookup");
    lookup.setId("customer-lookup");
    lookup.setOnAction(
        e -> {
          String phone = search.getText();
          work(
              () -> gateway.customers("/lookup?phone=" + enc(phone), "GET", null, token),
              r -> {
                if (r.path("status").asText().equals("FOUND")) select(r.path("customer"));
                else feedback.setText(r.path("message").asText());
              });
        });
    add.setId("customer-add");
    edit.setId("customer-edit");
    archive.setId("customer-archive");
    ban.setId("customer-ban");
    history.setId("customer-history");
    add.setVisible(allowed("customers.manage"));
    add.setManaged(add.isVisible());
    add.setOnAction(e -> form(null, null));
    edit.setOnAction(
        e -> {
          if (selected != null) form(selected, null);
        });
    archive.setOnAction(e -> status(false));
    ban.setOnAction(e -> status(true));
    history.setOnAction(e -> history(0));
    var reload = new Button("Reload");
    reload.setOnAction(e -> load());
    previous.setOnAction(
        e -> {
          cursor = cursors.pop();
          load();
        });
    next.setOnAction(
        e -> {
          cursors.push(cursor);
          cursor = nextCursor;
          load();
        });
    details.setId("customer-details");
    details.setWrapText(true);
    directory
        .getChildren()
        .addAll(
            new FlowPane(10, 10, search, archived, lookup, add, reload),
            table,
            new HBox(10, previous, next),
            details,
            new FlowPane(10, 10, edit, archive, ban, history));
    VBox.setVgrow(table, Priority.ALWAYS);
    var tabs = new TabPane();
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    tabs.getTabs().add(new Tab("Customer profiles", directory));
    if (allowed("customers.resolve")) {
      var tab = new Tab("Account Conflicts");
      tab.setId("customer-conflicts-tab");
      tab.setContent(conflictView());
      tab.setOnSelectionChanged(
          e -> {
            if (tab.isSelected()) loadConflicts();
          });
      tabs.getTabs().add(tab);
    }
    setCenter(tabs);
    select(null);
    load();
  }

  private static String address(JsonNode n) {
    var p = n.path("profile");
    if (!p.path("formatted_address").asText().isBlank())
      return p.path("formatted_address").asText();
    return java.util.stream.Stream.of(
            "house_number", "street", "complex_name", "area", "city", "postal_code")
        .map(k -> p.path(k).asText())
        .filter(v -> !v.isBlank())
        .collect(java.util.stream.Collectors.joining(", "));
  }

  private void load() {
    if (context == null) return;
    String requestedQuery = search.getText();
    boolean requestedArchived = archived.isSelected();
    String requestedCursor = cursor;
    work(
        () -> {
          var result = za.co.onlinepos.installation.JsonFiles.JSON.createObjectNode();
          result.set(
              "list",
              gateway.customers(
                  "?query="
                      + enc(requestedQuery)
                      + "&archived="
                      + requestedArchived
                      + "&cursor="
                      + enc(requestedCursor),
                  "GET",
                  null,
                  token));
          result.set("sync", gateway.customers("/sync", "GET", null, token));
          return result;
        },
        r -> {
          table.getItems().clear();
          r.path("list").path("items").forEach(table.getItems()::add);
          nextCursor = r.path("list").path("nextCursor").asText();
          next.setDisable(!r.path("list").path("hasMore").asBoolean());
          previous.setDisable(cursors.isEmpty());
          sync.setText(
              r.path("sync").path("message").asText()
                  + " Pending: "
                  + r.path("sync").path("pending").asInt());
          select(null);
        });
  }

  private void select(JsonNode row) {
    selected = row;
    boolean none = row == null;
    edit.setDisable(none || !allowed("customers.manage"));
    archive.setDisable(none || !allowed("customers.archive"));
    ban.setDisable(none || !allowed("customers.ban"));
    history.setDisable(none);
    if (none) {
      details.setText("Select a customer to view contact details and history.");
      return;
    }
    archive.setText(row.path("archived").asBoolean() ? "Restore" : "Archive");
    ban.setText(row.path("banned").asBoolean() ? "Unban" : "Ban");
    details.setText(
        row.path("name").asText()
            + "\n"
            + row.path("profile").path("phone1").asText()
            + " · "
            + address(row)
            + "\nPreferred store: "
            + row.path("profile").path("preferred_store").asText()
            + " (customer-controlled)"
            + (row.path("banned").asBoolean() ? "\nBANNED — " + row.path("banReason").asText() : "")
            + "\nSync: "
            + row.path("syncState").asText()
            + " · Local ID: "
            + row.path("id").asText());
  }

  private void status(boolean isBan) {
    if (selected == null) return;
    var row = selected;
    boolean value = !row.path(isBan ? "banned" : "archived").asBoolean();
    String reason = "";
    if (isBan && value) {
      var popup = new Dialog<ButtonType>();
      dialog = popup;
      popup.setTitle("Ban customer");
      var input = new TextArea();
      input.setId("customer-ban-reason");
      input.setPromptText("Reason required (up to 2000 characters)");
      popup.getDialogPane().setContent(input);
      popup.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
      popup
          .getDialogPane()
          .lookupButton(ButtonType.OK)
          .disableProperty()
          .bind(input.textProperty().isEmpty());
      popup.initOwner(getScene().getWindow());
      if (popup.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
        dialog = null;
        return;
      }
      reason = input.getText();
      dialog = null;
      if (reason.isBlank() || reason.length() > 2000) {
        feedback.setText("Enter a ban reason of at most 2000 characters.");
        return;
      }
    } else {
      var confirm =
          new Alert(
              Alert.AlertType.CONFIRMATION,
              (isBan ? "Unban" : value ? "Archive" : "Restore")
                  + " "
                  + row.path("name").asText()
                  + "?",
              ButtonType.YES,
              ButtonType.CANCEL);
      dialog = confirm;
      confirm.initOwner(getScene().getWindow());
      if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.YES) {
        dialog = null;
        return;
      }
      dialog = null;
    }
    var request =
        new Customers.Status(UUID.randomUUID(), row.path("revision").asLong(), value, reason);
    work(
        () ->
            gateway.customers(
                "/" + row.path("id").asText() + (isBan ? "/ban" : "/archive"),
                "PUT",
                request,
                token),
        r -> {
          int index = table.getItems().indexOf(row);
          if (index >= 0) table.getItems().set(index, r);
          select(r);
          feedback.setText(
              isBan
                  ? "Saved locally. Website and other branches have not confirmed this change."
                  : "Customer directory status saved.");
        });
  }

  private void form(JsonNode row, JsonNode conflict) {
    var popup = new Dialog<ButtonType>();
    dialog = popup;
    popup.setTitle(
        conflict != null
            ? "Keep accounts separate — correct contact details"
            : row == null ? "Add customer" : "Edit profile");
    popup.initOwner(getScene().getWindow());
    popup.setResizable(true);
    popup.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    var grid = new GridPane();
    grid.setHgap(14);
    grid.setVgap(10);
    grid.setPadding(new Insets(16));
    var inputs = new LinkedHashMap<String, TextField>();
    var base = new LinkedHashMap<String, String>();
    JsonNode profile =
        conflict != null
            ? row == null ? conflict.path("payload").path("fields") : row.path("profile")
            : row == null
                ? za.co.onlinepos.installation.JsonFiles.JSON.createObjectNode()
                : row.path("profile");
    for (String key : CustomerProfile.FIELDS.keySet()) base.put(key, profile.path(key).asText(""));
    if (row == null && conflict == null)
      base.put("preferred_store", context.path("branchName").asText());
    int index = 0;
    for (var field : CustomerProfile.FIELDS.entrySet()) {
      if (Set.of("country", "province", "latitude", "longitude").contains(field.getKey())) continue;
      var input = new TextField(base.get(field.getKey()));
      input.setId("customer-field-" + field.getKey());
      input.setPrefWidth(320);
      input.setEditable(!field.getKey().equals("preferred_store"));
      grid.addRow(index++, new Label(field.getValue()), input);
      inputs.put(field.getKey(), input);
    }
    var error = new Label();
    error.setId("customer-form-error");
    error.setWrapText(true);
    grid.add(error, 0, index, 2, 1);
    var scroll = new ScrollPane(grid);
    scroll.setFitToWidth(true);
    scroll.setPrefViewportHeight(500);
    scroll.setPrefViewportWidth(530);
    popup.getDialogPane().setContent(scroll);
    UUID command = UUID.randomUUID();
    UUID id = row == null ? UUID.randomUUID() : UUID.fromString(row.path("id").asText());
    popup
        .getDialogPane()
        .lookupButton(ButtonType.OK)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            e -> {
              e.consume();
              var values = new LinkedHashMap<>(base);
              inputs.forEach((key, input) -> values.put(key, input.getText()));
              try {
                var validated = Customers.validate(values);
                if (conflict != null) {
                  validated.remove("preferred_store");
                  var request =
                      new Customers.Resolution(
                          command,
                          conflict.path("revision").asLong(),
                          "SEPARATE",
                          null,
                          validated,
                          row == null ? -1 : row.path("revision").asLong());
                  saveForm(
                      popup, error, "/conflicts/" + conflict.path("id").asText(), request, true);
                } else
                  saveForm(
                      popup,
                      error,
                      "/" + id,
                      new Customers.Edit(
                          command, id, row == null ? -1 : row.path("revision").asLong(), validated),
                      false);
              } catch (CustomerProfile.FieldException failure) {
                error.setText(failure.getMessage());
                if (inputs.containsKey(failure.field())) inputs.get(failure.field()).requestFocus();
              } catch (IllegalArgumentException failure) {
                error.setText("Check the customer fields.");
              }
            });
    popup.showAndWait();
    dialog = null;
  }

  private void saveForm(
      Dialog<ButtonType> popup, Label error, String path, Object request, boolean conflict) {
    if (busy) return;
    busy = true;
    popup.getDialogPane().lookupButton(ButtonType.OK).setDisable(true);
    popup.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(true);
    popup.getDialogPane().getContent().setDisable(true);
    error.setText("Saving…");
    long operation = ++generation;
    executor.execute(
        () -> {
          try {
            var result = gateway.customers(path, "PUT", request, token);
            Platform.runLater(
                () -> {
                  if (closed || generation != operation) return;
                  busy = false;
                  popup.setResult(ButtonType.OK);
                  popup.close();
                  if (conflict) loadConflicts();
                  else {
                    select(result);
                    load();
                  }
                  feedback.setText("Saved on the store Master.");
                });
          } catch (Exception failure) {
            Platform.runLater(
                () -> {
                  if (closed || generation != operation) return;
                  busy = false;
                  popup.getDialogPane().lookupButton(ButtonType.OK).setDisable(false);
                  popup.getDialogPane().lookupButton(ButtonType.CANCEL).setDisable(false);
                  popup.getDialogPane().getContent().setDisable(false);
                  int status =
                      failure instanceof TrustedStoreClient.ModuleFailure f ? f.status() : 503;
                  error.setText(
                      status == 409
                          ? "Record changed or contact already exists. Cancel and reload, or use Account Conflicts."
                          : status == 401 || status == 403
                              ? "Customer permission or preferred store change refused."
                              : "Save not confirmed. Check the connection and retry; this command will not be duplicated.");
                });
          }
        });
  }

  private void history(int page) {
    if (selected == null) return;
    String id = selected.path("id").asText();
    work(
        () -> gateway.customers("/" + id + "/history?page=" + page, "GET", null, token),
        result -> {
          var popup = new Dialog<ButtonType>();
          dialog = popup;
          popup.setTitle("Linked drafts and status history — read only");
          popup.initOwner(getScene().getWindow());
          popup.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
          popup.setResizable(true);
          var drafts = new TableView<JsonNode>();
          drafts.setId("customer-linked-drafts");
          drafts.getColumns().add(column("Created (UTC)", r -> date(r.path("createdAt")), 170));
          drafts
              .getColumns()
              .add(column("Draft reference", r -> r.path("reference").asText(), 260));
          drafts.getColumns().add(column("Status", r -> r.path("state").asText(), 120));
          drafts
              .getColumns()
              .add(
                  column(
                      "Total",
                      r -> "R " + r.path("total").decimalValue().setScale(2).toPlainString(),
                      110));
          drafts.setPlaceholder(
              new Label(
                  "No linked drafts. Completed sales and payments are not available in this module."));
          result.path("items").forEach(drafts.getItems()::add);
          drafts.setPrefHeight(230);
          var statuses = new TableView<JsonNode>();
          statuses.setId("customer-status-history");
          statuses.getColumns().add(column("Changed (UTC)", r -> date(r.path("occurredAt")), 170));
          statuses.getColumns().add(column("Action", r -> r.path("action").asText(), 140));
          statuses
              .getColumns()
              .add(
                  column(
                      "Responsible staff", r -> r.path("staffId").asText("Unknown / remote"), 260));
          statuses.getColumns().add(column("Reason", r -> r.path("reason").asText(), 260));
          statuses.setPlaceholder(new Label("No recorded status changes"));
          result.path("statusHistory").forEach(statuses.getItems()::add);
          statuses.setPrefHeight(190);
          var text =
              new VBox(
                  8,
                  new Label("Linked drafts — including cancelled drafts"),
                  drafts,
                  new Label("Customer status history"),
                  statuses);
          text.setPrefWidth(880);
          var before = new Button("Previous");
          before.setDisable(page == 0);
          var after = new Button("Next");
          after.setDisable(!result.path("hasMore").asBoolean());
          before.setOnAction(
              e -> {
                popup.close();
                history(page - 1);
              });
          after.setOnAction(
              e -> {
                popup.close();
                history(page + 1);
              });
          popup.getDialogPane().setContent(new VBox(10, text, new HBox(10, before, after)));
          popup.showAndWait();
          dialog = null;
        });
  }

  private static String date(JsonNode value) {
    if (value.isMissingNode() || value.isNull()) return "Unknown";
    try {
      var instant =
          value.isNumber()
              ? java.time.Instant.ofEpochMilli(value.asLong())
              : java.time.Instant.parse(value.asText());
      return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
          .withZone(java.time.ZoneOffset.UTC)
          .format(instant);
    } catch (java.time.DateTimeException invalid) {
      return value.asText();
    }
  }

  private VBox conflictView() {
    conflicts.setId("customer-conflicts");
    conflicts
        .getColumns()
        .add(
            column(
                "Customer",
                r ->
                    (r.path("payload").path("fields").path("first_name").asText()
                            + " "
                            + r.path("payload").path("fields").path("last_name").asText())
                        .strip(),
                200));
    conflicts
        .getColumns()
        .add(column("Phone", r -> r.path("payload").path("fields").path("phone1").asText(), 160));
    conflicts.getColumns().add(column("Reason", r -> r.path("reason").asText(), 230));
    conflicts.getColumns().add(column("Conflict ID", r -> r.path("id").asText(), 320));
    conflicts.setPlaceholder(new Label("No unresolved account conflicts"));
    var link = new Button("Link correct account");
    var separate = new Button("Keep separate / correct contacts");
    link.setOnAction(
        e -> {
          var row = conflicts.getSelectionModel().getSelectedItem();
          if (row != null) link(row);
        });
    separate.setOnAction(
        e -> {
          var row = conflicts.getSelectionModel().getSelectedItem();
          if (row != null) {
            String proposed = row.path("payload").path("proposedId").asText();
            work(
                () -> {
                  try {
                    return gateway.customers("/" + proposed, "GET", null, token);
                  } catch (TrustedStoreClient.ModuleFailure failure) {
                    if (failure.status() == 404)
                      return za.co.onlinepos.installation.JsonFiles.JSON.nullNode();
                    throw failure;
                  }
                },
                current -> form(current.isNull() ? null : current, row));
          }
        });
    var reload = new Button("Reload conflicts");
    reload.setOnAction(e -> loadConflicts());
    var before = new Button("Previous");
    before.setOnAction(
        e -> {
          if (conflictPage > 0) {
            conflictPage--;
            loadConflicts();
          }
        });
    var after = new Button("Next");
    after.setId("customer-conflict-next");
    after.setOnAction(
        e -> {
          conflictPage++;
          loadConflicts();
        });
    var content =
        new VBox(
            12,
            new Label("Resolve contact collisions without deleting identities or order history."),
            conflicts,
            new FlowPane(10, 10, link, separate, reload, before, after));
    VBox.setVgrow(conflicts, Priority.ALWAYS);
    return content;
  }

  private void loadConflicts() {
    work(
        () -> gateway.customers("/conflicts?page=" + conflictPage, "GET", null, token),
        r -> {
          conflicts.getItems().clear();
          r.path("items").forEach(conflicts.getItems()::add);
          var after = (Button) lookup("#customer-conflict-next");
          if (after != null) after.setDisable(!r.path("hasMore").asBoolean());
        });
  }

  private void link(JsonNode conflict) {
    work(
        () -> {
          var result = za.co.onlinepos.installation.JsonFiles.JSON.createArrayNode();
          for (var id : conflict.path("payload").path("matches"))
            result.add(gateway.customers("/" + id.asText(), "GET", null, token));
          return result;
        },
        candidates -> {
          var popup = new Dialog<ButtonType>();
          dialog = popup;
          popup.setTitle("Link the correct customer identity");
          var choices = new ComboBox<JsonNode>();
          candidates.forEach(choices.getItems()::add);
          choices.setConverter(
              new javafx.util.StringConverter<>() {
                public String toString(JsonNode n) {
                  return n == null
                      ? ""
                      : n.path("name").asText() + " · " + n.path("profile").path("phone1").asText();
                }

                public JsonNode fromString(String s) {
                  return null;
                }
              });
          popup.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
          popup
              .getDialogPane()
              .lookupButton(ButtonType.OK)
              .disableProperty()
              .bind(choices.valueProperty().isNull());
          popup
              .getDialogPane()
              .setContent(
                  new VBox(
                      12,
                      new Label(
                          "Existing order references are preserved. Website linking remains pending acknowledgement."),
                      choices));
          popup.initOwner(getScene().getWindow());
          if (popup.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            var selectedTarget = choices.getValue();
            var request =
                new Customers.Resolution(
                    UUID.randomUUID(),
                    conflict.path("revision").asLong(),
                    "LINK",
                    UUID.fromString(selectedTarget.path("id").asText()),
                    Map.of());
            work(
                () ->
                    gateway.customers(
                        "/conflicts/" + conflict.path("id").asText(), "PUT", request, token),
                r -> loadConflicts());
          }
          dialog = null;
        });
  }

  boolean confirmClose() {
    return !busy && dialog == null;
  }

  @Override
  public void close() {
    closed = true;
    ++generation;
    debounce.stop();
    if (dialog != null) dialog.close();
    table.getItems().clear();
    conflicts.getItems().clear();
    selected = null;
    setCenter(null);
    setTop(null);
    setBottom(null);
  }
}
