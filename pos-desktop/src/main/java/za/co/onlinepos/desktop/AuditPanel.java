package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import za.co.onlinepos.contracts.Auditing;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.TrustedStoreClient;

/** Read-only history and owner-approved policy editor; all I/O runs on workers. */
final class AuditPanel extends BorderPane implements AutoCloseable {
  private final ModuleGateway gateway;
  private final ExecutorService executor;
  private final String token;
  private final Consumer<Boolean> dirty;
  private final Runnable activity;
  private final Label feedback = new Label();
  private final boolean settingsMode;
  private final ArrayDeque<String> previous = new ArrayDeque<>();
  private final Map<String, CheckBox> toggles = new LinkedHashMap<>();
  private final TextField staff = new TextField(),
      terminal = new TextField(),
      transaction = new TextField(),
      from = new TextField(),
      to = new TextField();
  private final ComboBox<String> category = new ComboBox<>(), action = new ComboBox<>();
  private String cursor = "", query = "", next = "";
  private JsonNode original;
  private boolean closed, busy, conflict, disconnected;
  private long generation;
  private UUID command;
  private Dialog<?> dialog;
  private Button save;
  private Label metadata;

  AuditPanel(
      ModuleGateway gateway,
      ExecutorService executor,
      String token,
      boolean settingsMode,
      Consumer<Boolean> dirty,
      Runnable activity) {
    this.gateway = gateway;
    this.executor = executor;
    this.token = token;
    this.settingsMode = settingsMode;
    this.dirty = dirty;
    this.activity = activity;
    setId(settingsMode ? "audit-settings-panel" : "audit-history-panel");
    setPadding(new Insets(12));
    feedback.setId("audit-feedback");
    feedback.setWrapText(true);
    setBottom(feedback);
    addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> activity.run());
    addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> activity.run());
    if (settingsMode) loadSettings();
    else historyForm();
  }

  private void work(Callable<JsonNode> task, Consumer<JsonNode> success) {
    if (closed || busy) return;
    busy = true;
    long operation = ++generation;
    if (getCenter() != null) getCenter().setDisable(true);
    feedback.setText("Loading…");
    executor.execute(
        () -> {
          try {
            var value = task.call();
            Platform.runLater(
                () -> {
                  if (closed || generation != operation) return;
                  busy = false;
                  if (getCenter() != null) getCenter().setDisable(false);
                  feedback.setText("");
                  success.accept(value);
                });
          } catch (Exception failure) {
            Platform.runLater(
                () -> {
                  if (closed || generation != operation) return;
                  busy = false;
                  if (getCenter() != null) getCenter().setDisable(false);
                  int status =
                      failure instanceof TrustedStoreClient.ModuleFailure f ? f.status() : 503;
                  conflict = status == 409;
                  if (status >= 500) {
                    disconnected = true;
                    toggles.values().forEach(toggle -> toggle.setDisable(true));
                  }
                  if (settingsMode && save != null && (conflict || status >= 500))
                    save.setDisable(true);
                  feedback.setText(
                      status == 409
                          ? "Settings changed. Reload before saving; your edits have not been applied."
                          : status == 401 || status == 403
                              ? "Owner authorisation refused or session expired. Check your PIN and access."
                              : status == 429
                                  ? "Too many attempts. Wait five minutes before retrying."
                                  : status == 400
                                      ? "Check the filters and form values."
                                      : "Store unavailable. Changes have not been confirmed. Reload before saving.");
                });
          }
        });
  }

  private JsonNode get(String path) throws Exception {
    return gateway.admin("audit/" + path, "GET", null, token);
  }

  private static String enc(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }

  private void historyForm() {
    staff.setPromptText("Staff username (exact)");
    staff.setId("audit-staff");
    terminal.setPromptText("Terminal identity (exact)");
    terminal.setId("audit-terminal");
    transaction.setPromptText("Transaction UUID");
    transaction.setId("audit-transaction");
    from.setPromptText("From: yyyy-MM-ddTHH:mm");
    to.setPromptText("To: yyyy-MM-ddTHH:mm");
    from.setId("audit-from");
    to.setId("audit-to");
    category.getItems().addAll("", "Orders", "Catalogue", "Staff", "Security", "System");
    category.setValue("");
    category.setPromptText("Category");
    category.setId("audit-category");
    action.getItems().add("");
    for (var type : Auditing.EventType.values()) action.getItems().add(type.name());
    action.setValue("");
    action.setId("audit-action");
    var apply = new Button("Apply filters");
    apply.setId("audit-apply");
    apply.setOnAction(
        e -> {
          try {
            String start =
                from.getText().isBlank()
                    ? ""
                    : LocalDateTime.parse(from.getText())
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toString();
            String end =
                to.getText().isBlank()
                    ? ""
                    : LocalDateTime.parse(to.getText())
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toString();
            if (!start.isEmpty()
                && !end.isEmpty()
                && java.time.Instant.parse(start).isAfter(java.time.Instant.parse(end)))
              throw new IllegalArgumentException();
            if (!transaction.getText().isBlank()) UUID.fromString(transaction.getText().strip());
            query =
                "&staff="
                    + enc(staff.getText())
                    + "&terminal="
                    + enc(terminal.getText())
                    + "&category="
                    + enc(category.getValue())
                    + "&action="
                    + enc(action.getValue())
                    + (start.isEmpty() ? "" : "&from=" + enc(start))
                    + (end.isEmpty() ? "" : "&to=" + enc(end))
                    + (transaction.getText().isBlank()
                        ? ""
                        : "&transaction=" + enc(transaction.getText().strip()));
            previous.clear();
            cursor = "";
            loadHistory();
          } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            feedback.setText("Enter valid local date/time ranges and a valid transaction UUID.");
          }
        });
    var filters =
        new FlowPane(10, 10, staff, terminal, transaction, category, action, from, to, apply);
    setTop(
        new VBox(
            10,
            new Label("Audit History · current branch only"),
            new Label("Date/time filters use terminal time: " + ZoneId.systemDefault()),
            filters));
    loadHistory();
  }

  private void loadHistory() {
    work(() -> get("events?cursor=" + enc(cursor) + query), this::history);
  }

  private void history(JsonNode response) {
    var table = new TableView<JsonNode>();
    table.setId("admin-audit-table");
    String[] names = {"Timestamp", "Staff", "Action", "Transaction", "Terminal"},
        keys = {"occurredAt", "staff", "action", "transactionId", "terminal"};
    for (int i = 0; i < keys.length; i++) {
      String key = keys[i];
      var col = new TableColumn<JsonNode, String>(names[i]);
      col.setPrefWidth(i == 2 ? 210 : 165);
      col.setCellValueFactory(
          row ->
              new ReadOnlyStringWrapper(
                  row.getValue().path(key).isNull()
                      ? "Unknown"
                      : row.getValue().path(key).asText("Unknown")));
      table.getColumns().add(col);
    }
    response.path("items").forEach(table.getItems()::add);
    table.setPlaceholder(new Label("No audit events match these filters"));
    var details = new Button("View details");
    details.setId("audit-details");
    details.setDisable(true);
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((o, a, b) -> details.setDisable(b == null));
    details.setOnAction(
        e -> {
          var selected = table.getSelectionModel().getSelectedItem();
          if (selected != null)
            work(() -> get("events/" + selected.path("eventId").asText()), this::details);
        });
    next = response.path("nextCursor").asText();
    var before = new Button("Previous");
    before.setDisable(previous.isEmpty());
    before.setOnAction(
        e -> {
          cursor = previous.pop();
          loadHistory();
        });
    var after = new Button("Next");
    after.setId("audit-next");
    after.setDisable(!response.path("hasMore").asBoolean());
    after.setOnAction(
        e -> {
          previous.push(cursor);
          cursor = next;
          loadHistory();
        });
    var content =
        new VBox(
            12,
            new Label("Branch " + response.path("branchId").asText()),
            table,
            new HBox(10, before, after, details));
    VBox.setVgrow(table, Priority.ALWAYS);
    setCenter(content);
  }

  private void details(JsonNode event) {
    var popup = new Dialog<ButtonType>();
    dialog = popup;
    popup.setTitle("Audit event — read only");
    popup.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
    var text = new TextArea(event.toPrettyString());
    text.setEditable(false);
    text.setPrefSize(750, 480);
    popup.getDialogPane().setContent(text);
    popup.initOwner(getScene().getWindow());
    popup.showAndWait();
    dialog = null;
  }

  private void loadSettings() {
    work(
        () -> {
          var value = JsonFiles.JSON.createObjectNode();
          value.set("policy", get("settings"));
          value.set("types", get("types"));
          return value;
        },
        this::settings);
  }

  private void settings(JsonNode response) {
    original = response.path("policy");
    conflict = false;
    disconnected = false;
    command = null;
    dirty.accept(false);
    toggles.clear();
    metadata = new Label(metadataText(original));
    var content =
        new VBox(
            12,
            new Label("Security → Audit History → Tracking Settings"),
            new Label(
                "Applies to branch "
                    + original.path("branchId").asText()
                    + " only. Changes affect future events."),
            metadata);
    for (var type : response.path("types")) {
      String name = type.path("action").asText();
      boolean optional = type.path("optional").asBoolean();
      var toggle = new CheckBox(name + (optional ? "" : " · mandatory, always recorded"));
      toggle.setId("track-" + name);
      toggle.setSelected(!optional || original.path("settings").path(name).asBoolean(true));
      toggle.setDisable(!optional);
      if (optional) {
        toggles.put(name, toggle);
        toggle.selectedProperty().addListener((o, a, b) -> changed());
      }
      content.getChildren().add(toggle);
    }
    var all = new Button("Enable All");
    all.setOnAction(
        e -> {
          if (!disconnected) toggles.values().forEach(t -> t.setSelected(true));
        });
    var none = new Button("Disable Optional Tracking");
    none.setOnAction(
        e -> {
          if (!disconnected) toggles.values().forEach(t -> t.setSelected(false));
        });
    save = new Button("Save");
    save.setId("audit-settings-save");
    save.setDisable(true);
    save.setOnAction(e -> approve());
    var cancel = new Button("Cancel");
    cancel.setOnAction(e -> loadSettings());
    var reload = new Button("Reload");
    reload.setId("audit-settings-reload");
    reload.setOnAction(e -> loadSettings());
    boolean preview = gateway instanceof PreviewGateway;
    if (preview) {
      all.setDisable(true);
      none.setDisable(true);
      toggles.values().forEach(t -> t.setDisable(true));
      content.getChildren().add(new Label("Preview is read-only. No owner approval is available."));
    }
    content.getChildren().add(new FlowPane(10, 10, all, none, save, cancel, reload));
    var scroll = new ScrollPane(content);
    scroll.setFitToWidth(true);
    setCenter(scroll);
  }

  private static String metadataText(JsonNode policy) {
    return "Last changed: "
        + policy.path("updatedAt").asText("Never")
        + " · Approved by: "
        + policy.path("approvedByName").asText("Unknown");
  }

  private Map<String, Boolean> values() {
    var result = new LinkedHashMap<String, Boolean>();
    toggles.forEach((key, toggle) -> result.put(key, toggle.isSelected()));
    return result;
  }

  private void changed() {
    command = null;
    boolean modified = !JsonFiles.JSON.valueToTree(values()).equals(original.path("settings"));
    dirty.accept(modified);
    if (save != null)
      save.setDisable(!modified || conflict || disconnected || gateway instanceof PreviewGateway);
  }

  private void approve() {
    var popup = new Dialog<ButtonType>();
    dialog = popup;
    popup.setTitle("Owner authorisation");
    var ok = new ButtonType("Approve and save", ButtonBar.ButtonData.OK_DONE);
    popup.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
    var pin = new PasswordField();
    pin.setId("audit-owner-pin");
    pin.setPromptText("Owner's six-digit PIN");
    pin.setTextFormatter(
        new javafx.scene.control.TextFormatter<String>(
            change -> change.getControlNewText().matches("[0-9]{0,6}") ? change : null));
    popup
        .getDialogPane()
        .setContent(
            new VBox(12, new Label("The designated owner must approve these exact changes."), pin));
    popup
        .getDialogPane()
        .lookupButton(ok)
        .disableProperty()
        .bind(pin.textProperty().length().isNotEqualTo(6));
    popup
        .getDialogPane()
        .addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> activity.run());
    popup.initOwner(getScene().getWindow());
    boolean accepted = popup.showAndWait().orElse(ButtonType.CANCEL) == ok;
    String entered = accepted ? pin.getText() : "";
    pin.clear();
    dialog = null;
    if (!accepted) return;
    if (command == null) command = UUID.randomUUID();
    var edit =
        new Auditing.SettingsEdit(command, original.path("revision").asLong(), values(), entered);
    work(
        () -> gateway.admin("audit/settings", "PUT", edit, token),
        result -> {
          original = result;
          metadata.setText(metadataText(original));
          command = null;
          dirty.accept(false);
          save.setDisable(true);
          feedback.setText("Tracking settings saved and owner approval recorded.");
        });
  }

  @Override
  public void close() {
    closed = true;
    ++generation;
    if (dialog != null) dialog.close();
    toggles.clear();
    setCenter(null);
    setTop(null);
    dirty.accept(false);
  }
}
