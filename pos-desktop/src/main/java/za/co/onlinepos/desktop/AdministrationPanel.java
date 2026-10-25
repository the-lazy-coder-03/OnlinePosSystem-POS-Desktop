package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import za.co.onlinepos.contracts.Administration;
import za.co.onlinepos.contracts.PosModule;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.TrustedStoreClient;

/** Session-local forms; closing clears inputs and ignores all outstanding responses. */
final class AdministrationPanel extends BorderPane implements AutoCloseable {
  private AuditPanel auditPanel;
  private final ModuleGateway gateway;
  private final ExecutorService executor;
  private final String token;
  private final Consumer<String> lock;
  private final Runnable activity;
  private final VBox navigation = new VBox(8);
  private final BorderPane body = new BorderPane();
  private final Label feedback = new Label();
  private final Label connection = new Label("Checking store connection…");
  private final Map<String, ToggleButton> tabs = new HashMap<>();
  private JsonNode context;
  private boolean closed, busy, dirty, populating;
  private long generation;
  private String section = "Overview";
  private UUID command;
  private Dialog<?> dialog;
  private int staffPage, auditPage;
  private String staffSearch = "";
  private String auditActor = "", auditAction = "", auditFrom = "", auditTo = "";

  AdministrationPanel(
      ModuleGateway gateway,
      ExecutorService executor,
      String token,
      String store,
      String username,
      Consumer<String> lock) {
    this(gateway, executor, token, store, username, lock, () -> {});
  }

  AdministrationPanel(
      ModuleGateway gateway,
      ExecutorService executor,
      String token,
      String store,
      String username,
      Consumer<String> lock,
      Runnable activity) {
    this.activity = activity;
    this.gateway = gateway;
    this.executor = executor;
    this.token = token;
    this.lock = lock;
    setId("administration-panel");
    getStyleClass().add("admin-panel");
    var heading = new Label("Administration");
    heading.getStyleClass().add("home-title");
    var home = new Button("Home / Lock");
    home.setId("home-lock");
    home.setOnAction(e -> lock.accept(""));
    var spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    var header =
        new HBox(
            20,
            new VBox(4, heading, new Label(store + " · Staff: " + username), connection),
            spacer,
            home);
    header.setAlignment(Pos.CENTER_LEFT);
    header.setPadding(new Insets(18, 24, 18, 24));
    setTop(header);
    navigation.getStyleClass().add("admin-sidebar");
    navigation.setMinWidth(220);
    navigation.setPrefWidth(220);
    setLeft(navigation);
    body.setPadding(new Insets(22));
    setCenter(body);
    feedback.setWrapText(true);
    feedback.setId("admin-feedback");
    feedback.setPadding(new Insets(10, 24, 14, 24));
    setBottom(feedback);
    refreshContext();
  }

  private void work(Callable<JsonNode> task, Consumer<JsonNode> success) {
    if (closed || busy) return;
    busy = true;
    long operation = ++generation;
    navigation.setDisable(true);
    body.setDisable(true);
    feedback.setText("Working…");
    executor.execute(
        () -> {
          try {
            var result = task.call();
            Platform.runLater(
                () -> {
                  if (closed || operation != generation) return;
                  busy = false;
                  navigation.setDisable(false);
                  body.setDisable(false);
                  connection.setText("Store connected");
                  feedback.setText("");
                  success.accept(result);
                });
          } catch (Exception failure) {
            Platform.runLater(
                () -> {
                  if (closed || operation != generation) return;
                  busy = false;
                  navigation.setDisable(false);
                  body.setDisable(false);
                  if (failure instanceof TrustedStoreClient.ModuleFailure f) {
                    if (f.status() == 401 || f.status() == 403) {
                      lock.accept("Administration access changed. Enter your PIN again.");
                      return;
                    }
                    if (f.status() == 409) {
                      feedback.setText(
                          "This record changed or a value is unavailable. Reload before retrying.");
                      return;
                    }
                    if (f.status() == 400) {
                      feedback.setText("Check the form values and try again.");
                      return;
                    }
                  }
                  connection.setText("Store unavailable");
                  feedback.setText(
                      "Unable to save or load data. Check the store connection before retrying.");
                });
          }
        });
  }

  private JsonNode get(String path) throws Exception {
    return gateway.admin(path, "GET", null, token);
  }

  private void refreshContext() {
    work(
        () -> get("overview"),
        value -> {
          context = value;
          buildNavigation();
          overview();
        });
  }

  private boolean allowed(String permission) {
    return context.path("owner").asBoolean()
        || values(context.path("permissions")).contains(permission);
  }

  private void buildNavigation() {
    navigation.getChildren().clear();
    tabs.clear();
    addTab("Overview", null);
    if (allowed("staff.manage")) addTab("Staff", "staff.manage");
    if (allowed("rbac.manage")) addTab("Roles & Permissions", "rbac.manage");
    if (allowed("audit.view")) addTab("Audit History", "audit.view");
    addTab("Security / Audit Tracking", null);
  }

  private void addTab(String name, String permission) {
    var button = new ToggleButton(name);
    button.setId("admin-tab-" + name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]+", "-"));
    button.setAccessibleText(name);
    button.setMaxWidth(Double.MAX_VALUE);
    button.getStyleClass().add("admin-nav");
    button.setOnAction(
        e -> {
          if (name.equals(section)) {
            selectTab(section);
            return;
          }
          if (!discard()) {
            selectTab(section);
            return;
          }
          if (auditPanel != null) {
            auditPanel.close();
            auditPanel = null;
          }
          section = name;
          selectTab(name);
          switch (name) {
            case "Overview" -> refreshContext();
            case "Staff" -> {
              staffPage = 0;
              loadStaff(null);
            }
            case "Roles & Permissions" -> loadRoles(null);
            case "Audit History" -> showAudit(false);
            case "Security / Audit Tracking" -> showAudit(true);
            default -> throw new IllegalStateException();
          }
        });
    tabs.put(name, button);
    navigation.getChildren().add(button);
  }

  private void selectTab(String name) {
    tabs.forEach((key, tab) -> tab.setSelected(key.equals(name)));
  }

  private void observeActivity(Dialog<?> popup) {
    popup
        .getDialogPane()
        .addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> activity.run());
    popup
        .getDialogPane()
        .addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> activity.run());
    popup
        .getDialogPane()
        .addEventFilter(javafx.scene.input.TouchEvent.TOUCH_PRESSED, event -> activity.run());
  }

  private boolean discard() {
    if (!dirty) return true;
    var alert =
        new Alert(
            Alert.AlertType.CONFIRMATION,
            "Discard unsaved changes?",
            ButtonType.YES,
            ButtonType.CANCEL);
    dialog = alert;
    observeActivity(alert);
    if (getScene() != null) alert.initOwner(getScene().getWindow());
    boolean confirmed = alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.YES;
    dialog = null;
    if (confirmed) {
      dirty = false;
      command = null;
    }
    return confirmed;
  }

  private void changed() {
    if (!populating) {
      dirty = true;
      command = null;
    }
  }

  private void overview() {
    section = "Overview";
    selectTab(section);
    dirty = false;
    var content =
        new VBox(
            18, title("Overview"), new Label("Manage staff and store access from the sidebar."));
    if (!context.path("ownerConfigured").asBoolean()) {
      var notice =
          new Label(
              "A store owner has not been designated. Use protected local administration to configure the owner before setting staff PINs.");
      notice.setWrapText(true);
      notice.getStyleClass().add("admin-warning");
      content.getChildren().add(notice);
    } else if (context.path("owner").asBoolean())
      content
          .getChildren()
          .add(
              new Label(
                  "You are the designated store owner. Only you can set and reset staff PINs."));
    if (context.has("staffCount"))
      content
          .getChildren()
          .add(
              new Label(
                  "Staff accounts: "
                      + context.path("staffCount").asInt()
                      + " · Awaiting PIN setup: "
                      + context.path("pendingPins").asInt()));
    if (context.has("roleCount"))
      content.getChildren().add(new Label("Roles: " + context.path("roleCount").asInt()));
    body.setCenter(content);
  }

  private Label title(String text) {
    var label = new Label(text);
    label.getStyleClass().add("admin-section-title");
    return label;
  }

  private static Set<String> values(JsonNode array) {
    var result = new TreeSet<String>();
    array.forEach(v -> result.add(v.asText()));
    return result;
  }

  private TableView<JsonNode> table(String id, String... fields) {
    var table = new TableView<JsonNode>();
    table.setId(id);
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    for (String field : fields) {
      String[] pair = field.split(":", 2);
      var column = new TableColumn<JsonNode, String>(pair[0]);
      String key = pair[1];
      column.setCellValueFactory(
          row -> {
            var value = row.getValue().path(key);
            return new ReadOnlyStringWrapper(
                value.isArray()
                    ? String.join(", ", values(value))
                    : value.isBoolean() ? (value.asBoolean() ? "Yes" : "No") : value.asText());
          });
      table.getColumns().add(column);
    }
    table.setPlaceholder(new Label("No records found."));
    table.setMinHeight(200);
    return table;
  }

  private javafx.scene.layout.FlowPane flow(Node... children) {
    var row = new javafx.scene.layout.FlowPane(10, 8, children);
    row.setPrefWrapLength(400);
    return row;
  }

  private ScrollPane scroll(Node content) {
    var scroll = new ScrollPane(content);
    scroll.setFitToWidth(true);
    scroll.setMinWidth(280);
    return scroll;
  }

  private void loadStaff(UUID selected) {
    section = "Staff";
    selectTab(section);
    work(
        () -> {
          var result = JsonFiles.JSON.createObjectNode();
          result.set("options", get("staff/options"));
          result.set("data", get("staff?search=" + encode(staffSearch) + "&page=" + staffPage));
          return result;
        },
        result -> staffScreen(result, selected));
  }

  private void staffScreen(JsonNode result, UUID selected) {
    dirty = false;
    command = null;
    var table =
        table(
            "admin-staff-table",
            "Username:username",
            "Roles:roles",
            "Enabled:enabled",
            "PIN set:pinConfigured",
            "Owner:owner");
    result.path("data").path("items").forEach(table.getItems()::add);
    var search = new TextField(staffSearch);
    search.setPromptText("Search username");
    search.setId("admin-staff-search");
    var find = new Button("Search");
    find.setOnAction(
        e -> {
          if (discard()) {
            staffSearch = search.getText();
            staffPage = 0;
            loadStaff(null);
          }
        });
    search.setOnAction(e -> find.fire());
    var reload = new Button("Reload");
    reload.setOnAction(
        e -> {
          if (discard()) loadStaff(null);
        });
    var previous = new Button("Previous");
    previous.setDisable(staffPage == 0);
    previous.setOnAction(
        e -> {
          if (discard()) {
            staffPage--;
            loadStaff(null);
          }
        });
    var next = new Button("Next");
    next.setDisable(!result.path("data").path("hasMore").asBoolean());
    next.setOnAction(
        e -> {
          if (discard()) {
            staffPage++;
            loadStaff(null);
          }
        });
    var editor = new VBox(12);
    editor.setPadding(new Insets(0, 0, 0, 20));
    var create = new Button("New staff");
    create.setId("admin-new-staff");
    create.setDisable(result.path("options").isEmpty());
    create.setOnAction(
        e -> {
          if (discard()) {
            populating = true;
            table.getSelectionModel().clearSelection();
            populating = false;
            staffForm(editor, null, result.path("options"));
          }
        });
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (obs, old, row) -> {
              if (populating || row == null) return;
              if (!discard()) {
                populating = true;
                table.getSelectionModel().select(old);
                populating = false;
                return;
              }
              staffForm(editor, row, result.path("options"));
            });
    var listing =
        new VBox(
            12,
            flow(search, find, reload, create),
            table,
            new HBox(10, previous, new Label("Page " + (staffPage + 1)), next));
    VBox.setVgrow(table, Priority.ALWAYS);
    var content = new BorderPane();
    content.setCenter(listing);
    var formScroll = scroll(editor);
    formScroll.setPrefWidth(340);
    content.setRight(formScroll);
    body.setCenter(new VBox(14, title("Staff"), content));
    VBox.setVgrow(content, Priority.ALWAYS);
    editor.getChildren().add(new Label("Select an account or create new staff."));
    if (selected != null)
      table.getItems().stream()
          .filter(row -> row.path("staffId").asText().equals(selected.toString()))
          .findFirst()
          .ifPresent(table.getSelectionModel()::select);
  }

  private void staffForm(VBox editor, JsonNode row, JsonNode options) {
    populating = true;
    dirty = false;
    command = null;
    editor.getChildren().clear();
    UUID id = row == null ? UUID.randomUUID() : UUID.fromString(row.path("staffId").asText());
    long revision = row == null ? -1 : row.path("revision").asLong();
    boolean editable = row == null || row.path("editable").asBoolean();
    var username = new TextField(row == null ? "" : row.path("username").asText());
    username.setId("admin-staff-username");
    username.setPromptText("lowercase username");
    username.setDisable(!editable);
    var enabled = new CheckBox("Account enabled");
    enabled.setSelected(row == null || row.path("enabled").asBoolean());
    enabled.setDisable(!editable || (row != null && row.path("owner").asBoolean()));
    var roleChecks = new ArrayList<CheckBox>();
    Set<String> selected = row == null ? Set.of() : values(row.path("roles"));
    var roleNames = new TreeSet<String>();
    options.forEach(option -> roleNames.add(option.path("name").asText()));
    roleNames.addAll(selected);
    var roles = new VBox(8);
    for (String name : roleNames) {
      var check = new CheckBox(name);
      check.setSelected(selected.contains(name));
      check.setDisable(!editable);
      check.selectedProperty().addListener((o, a, b) -> changed());
      roleChecks.add(check);
      roles.getChildren().add(check);
    }
    username.textProperty().addListener((o, a, b) -> changed());
    enabled.selectedProperty().addListener((o, a, b) -> changed());
    var save = new Button("Save staff");
    save.setId("admin-save-staff");
    save.getStyleClass().add("primary");
    save.setDisable(!editable);
    save.setOnAction(
        e -> {
          var assigned = new TreeSet<String>();
          roleChecks.stream()
              .filter(CheckBox::isSelected)
              .forEach(check -> assigned.add(check.getText()));
          if (!username.getText().matches("[a-z0-9._-]{3,64}") || assigned.isEmpty()) {
            feedback.setText(
                "Use a lowercase username of 3–64 characters and select at least one role.");
            return;
          }
          if (command == null) command = UUID.randomUUID();
          var edit =
              new Administration.StaffEdit(
                  command, id, username.getText(), enabled.isSelected(), assigned, revision);
          work(
              () -> gateway.admin("staff", "PUT", edit, token),
              reply -> {
                dirty = false;
                command = null;
                if (reauthenticate(reply)) return;
                loadStaff(id);
              });
        });
    editor
        .getChildren()
        .addAll(
            title(row == null ? "New staff" : "Edit staff"),
            new Label("Username"),
            username,
            enabled,
            new Label("Assigned roles"),
            roles,
            new Label(
                row != null && row.path("passwordLoginEnabled").asBoolean()
                    ? "Existing password recovery is preserved."
                    : "PIN access only. The owner must configure a PIN."),
            save);
    if (!editable) {
      var warning = new Label("Your permissions do not allow editing this account.");
      warning.setWrapText(true);
      editor.getChildren().add(warning);
    }
    if (context.path("owner").asBoolean() && row != null) {
      var pin = new Button(row.path("pinConfigured").asBoolean() ? "Reset PIN" : "Set PIN");
      pin.setId("admin-reset-pin");
      pin.setOnAction(
          e -> {
            if (discard()) pinDialog(id, revision, row.path("username").asText());
          });
      editor.getChildren().add(pin);
    }
    populating = false;
  }

  private void pinDialog(UUID id, long revision, String username) {
    var first = new PasswordField();
    first.setId("admin-new-pin");
    first.setPromptText("New six-digit PIN");
    var second = new PasswordField();
    second.setId("admin-confirm-pin");
    second.setPromptText("Confirm PIN");
    for (var field : List.of(first, second))
      field.setTextFormatter(
          new TextFormatter<String>(
              change -> change.getControlNewText().matches("[0-9]{0,6}") ? change : null));
    var popup = new Dialog<ButtonType>();
    dialog = popup;
    observeActivity(popup);
    popup.setTitle("Set PIN · " + username);
    if (getScene() != null) popup.initOwner(getScene().getWindow());
    var save = new ButtonType("Set PIN", ButtonBar.ButtonData.OK_DONE);
    popup.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
    popup
        .getDialogPane()
        .setContent(
            new VBox(
                12,
                new Label("Existing PINs cannot be displayed. Enter matching new PINs."),
                first,
                second));
    var button = (Button) popup.getDialogPane().lookupButton(save);
    button
        .disableProperty()
        .bind(
            first
                .textProperty()
                .length()
                .isNotEqualTo(6)
                .or(first.textProperty().isNotEqualTo(second.textProperty())));
    button.addEventFilter(
        javafx.event.ActionEvent.ACTION,
        e -> {
          e.consume();
          var edit = new Administration.PinEdit(UUID.randomUUID(), revision, first.getText());
          first.clear();
          second.clear();
          popup.close();
          work(
              () -> gateway.admin("staff/" + id + "/pin", "PUT", edit, token),
              reply -> {
                if (!reauthenticate(reply)) loadStaff(id);
              });
        });
    popup.setOnHidden(
        e -> {
          first.clear();
          second.clear();
          dialog = null;
        });
    popup.setOnShown(e -> first.requestFocus());
    popup.show();
  }

  private boolean reauthenticate(JsonNode reply) {
    if (reply.path("reauthenticate").asBoolean()) {
      lock.accept("Changes saved. Enter your PIN again.");
      return true;
    }
    feedback.setText("Changes saved.");
    return false;
  }

  private void loadRoles(String selected) {
    section = "Roles & Permissions";
    selectTab(section);
    work(() -> get("roles"), result -> roleScreen(result, selected));
  }

  private void roleScreen(JsonNode rows, String selected) {
    dirty = false;
    command = null;
    var table = table("admin-roles-table", "Role:name", "Permissions:permissions");
    rows.forEach(table.getItems()::add);
    var editor = new VBox(12);
    editor.setPadding(new Insets(0, 0, 0, 20));
    var create = new Button("New role");
    create.setId("admin-new-role");
    create.setOnAction(
        e -> {
          if (discard()) {
            populating = true;
            table.getSelectionModel().clearSelection();
            populating = false;
            roleForm(editor, null);
          }
        });
    var reload = new Button("Reload");
    reload.setOnAction(
        e -> {
          if (discard()) loadRoles(null);
        });
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, old, row) -> {
              if (populating || row == null) return;
              if (!discard()) {
                populating = true;
                table.getSelectionModel().select(old);
                populating = false;
                return;
              }
              roleForm(editor, row);
            });
    var listing = new VBox(12, new HBox(10, create, reload), table);
    VBox.setVgrow(table, Priority.ALWAYS);
    var content = new BorderPane();
    content.setCenter(listing);
    var form = scroll(editor);
    form.setPrefWidth(370);
    content.setRight(form);
    body.setCenter(new VBox(14, title("Roles & Permissions"), content));
    VBox.setVgrow(content, Priority.ALWAYS);
    editor.getChildren().add(new Label("Select a role or create a new one."));
    if (selected != null)
      table.getItems().stream()
          .filter(row -> row.path("name").asText().equals(selected))
          .findFirst()
          .ifPresent(table.getSelectionModel()::select);
  }

  private void roleForm(VBox editor, JsonNode row) {
    populating = true;
    dirty = false;
    command = null;
    editor.getChildren().clear();
    var name = new TextField(row == null ? "" : row.path("name").asText());
    name.setId("admin-role-name");
    name.setDisable(row != null);
    var implemented = values(context.path("implementedPermissions"));
    var held = values(context.path("permissions"));
    var selected = row == null ? Set.<String>of() : values(row.path("permissions"));
    boolean supported = implemented.containsAll(selected);
    boolean editable = (row == null || row.path("editable").asBoolean()) && supported;
    var checks = new ArrayList<CheckBox>();
    var modules = new VBox(8);
    var actions = new VBox(8);
    for (String permission : implemented) {
      var check = new CheckBox(permissionLabel(permission));
      check.setUserData(permission);
      check.setSelected(selected.contains(permission));
      check.setDisable(
          !editable || (!context.path("owner").asBoolean() && !held.contains(permission)));
      check.selectedProperty().addListener((o, a, b) -> changed());
      checks.add(check);
      (permission.startsWith("module.") ? modules : actions).getChildren().add(check);
    }
    name.textProperty().addListener((o, a, b) -> changed());
    var save = new Button("Save role");
    save.setId("admin-save-role");
    save.getStyleClass().add("primary");
    save.setDisable(!editable);
    save.setOnAction(
        e -> {
          if (!name.getText().matches("[A-Z][A-Z0-9_]{0,63}")) {
            feedback.setText("Use an uppercase role name with letters, numbers or underscores.");
            return;
          }
          var permissions = new TreeSet<String>();
          checks.stream()
              .filter(CheckBox::isSelected)
              .forEach(c -> permissions.add((String) c.getUserData()));
          if (command == null) command = UUID.randomUUID();
          var edit =
              new Administration.RoleEdit(
                  command,
                  name.getText(),
                  permissions,
                  row == null ? -1 : row.path("revision").asLong());
          work(
              () -> gateway.admin("roles", "PUT", edit, token),
              reply -> {
                dirty = false;
                command = null;
                if (!reauthenticate(reply)) loadRoles(edit.name());
              });
        });
    editor
        .getChildren()
        .addAll(
            title(row == null ? "New role" : "Edit role"),
            new Label("Role name"),
            name,
            new Label("Module access"),
            modules,
            new Label("Administrative actions"),
            actions,
            save);
    if (!supported)
      editor
          .getChildren()
          .add(
              new Label(
                  "This role contains reserved permissions managed through protected local administration."));
    else if (!editable)
      editor.getChildren().add(new Label("Your permissions do not allow editing this role."));
    populating = false;
  }

  private static String permissionLabel(String permission) {
    for (var module : PosModule.values())
      if (module.permission().equals(permission)) return "Open " + module.label();
    return switch (permission) {
      case "staff.manage" -> "Manage staff";
      case "rbac.manage" -> "Manage roles and permissions";
      case "audit.view" -> "View audit history";
      case "catalogue.manage" -> "Manage catalogue items and ingredients";
      default -> permission;
    };
  }

  private void showAudit(boolean settings) {
    section = settings ? "Security / Audit Tracking" : "Audit History";
    selectTab(section);
    dirty = false;
    auditPanel =
        new AuditPanel(gateway, executor, token, settings, value -> dirty = value, activity);
    body.setCenter(auditPanel);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    closed = true;
    if (auditPanel != null) {
      auditPanel.close();
      auditPanel = null;
    }
    ++generation;
    dirty = false;
    command = null;
    if (dialog != null) {
      dialog.close();
      dialog = null;
    }
    for (Node node : body.lookupAll(".text-input"))
      if (node instanceof TextInputControl input) input.clear();
    body.setCenter(null);
    navigation.getChildren().clear();
    setTop(null);
    setBottom(null);
  }
}
