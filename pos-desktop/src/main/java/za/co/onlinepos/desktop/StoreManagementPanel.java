package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Existing Settings extension. Explicit development actor; no owner identity is fabricated. */
final class StoreManagementPanel extends BorderPane implements AutoCloseable {
  private final ModuleGateway gateway;
  private final String token;
  private final ExecutorService executor;
  private final Runnable back;
  private final Label feedback = new Label();
  private final TextField business = new TextField(),
      name = new TextField(),
      code = new TextField(),
      address = new TextField(),
      phone = new TextField(),
      email = new TextField();
  private final CheckBox active = new CheckBox("Store active");
  private final ComboBox<JsonNode> existing = new ComboBox<>();
  private final Button save = new Button("Save"), cancel = new Button("Back to grid");
  private final VBox form = new VBox(8);
  private JsonNode state;
  private boolean busy, closed, dirty, filling;
  private UUID pending;

  StoreManagementPanel(ModuleGateway gateway, ExecutorService executor, Runnable back) {
    this(gateway, executor, null, back);
  }

  StoreManagementPanel(
      ModuleGateway gateway, ExecutorService executor, String token, Runnable back) {
    this.gateway = gateway;
    this.token = token;
    this.executor = executor;
    this.back = back;
    setPadding(new Insets(24));
    setId("store-management");
    feedback.setWrapText(true);
    feedback.setId("store-feedback");
    name.setId("store-name");
    business.setId("business-name");
    code.setId("store-code");
    save.setId("store-save");
    cancel.setId(token == null ? "home-lock" : "store-back");
    cancel.setText("Back");
    setTop(
        new VBox(
            8,
            new Label("Settings · Store Management"),
            new Label("Standalone store · cloud integration disabled"),
            new Label(
                gateway instanceof DevGateway
                    ? "Development actor — Owner authentication is not implemented here yet."
                    : "Designated local Owner · authenticated Administration session")));
    setCenter(new ScrollPane(form));
    var reload = new Button("Reload");
    reload.setOnAction(
        e -> {
          if (confirmClose()) load();
        });
    setBottom(new VBox(8, feedback, new HBox(10, save, reload, cancel)));
    for (var field : List.of(business, name, code, address, phone, email))
      field.textProperty().addListener((o, a, b) -> changed());
    active.selectedProperty().addListener((o, a, b) -> changed());
    save.setOnAction(e -> save());
    cancel.setOnAction(
        e -> {
          if (confirmClose()) {
            close();
            back.run();
          }
        });
    load();
  }

  private void changed() {
    if (!filling) {
      dirty = true;
      pending = null;
    }
  }

  private void load() {
    work(() -> request("GET", null), this::render);
  }

  private void render(JsonNode value) {
    filling = true;
    state = value;
    form.getChildren().clear();
    boolean configured = value.path("configured").asBoolean();
    if (!configured) {
      form.getChildren()
          .addAll(
              new Label("Business name"),
              business,
              new Label("Store name"),
              name,
              new Label("Store code"),
              code);
      var choices = value.path("stores");
      if (!choices.isEmpty()) {
        existing.getItems().clear();
        choices.forEach(existing.getItems()::add);
        existing.setConverter(
            new javafx.util.StringConverter<>() {
              public String toString(JsonNode row) {
                return row == null
                    ? ""
                    : row.path("name").asText() + " (#" + row.path("branch_id").asText() + ")";
              }

              public JsonNode fromString(String text) {
                return null;
              }
            });
        var adopt = new Button("Use selected existing store (preserve data)");
        adopt.setOnAction(
            e -> {
              if (existing.getValue() != null)
                work(
                    () ->
                        ((DevGateway) gateway)
                            .call(
                                "/grid/store/adopt",
                                "POST",
                                Map.of(
                                    "commandId",
                                    UUID.randomUUID(),
                                    "branchId",
                                    existing.getValue().path("branch_id").asInt(),
                                    "businessName",
                                    business.getText())),
                    this::render);
            });
        form.getChildren()
            .addAll(
                new Label("Existing stores require an explicit selection; no identity is guessed."),
                existing,
                adopt);
      }
      feedback.setText(
          "Create this Master's local store. No cloud account or internet is required.");
    } else {
      var row = value.path("store");
      business.setText(value.path("business").path(0).path("name").asText(""));
      name.setText(row.path("name").asText());
      code.setText(row.path("code").asText(""));
      address.setText(row.path("address").asText());
      phone.setText(row.path("phone").asText());
      email.setText(row.path("email").asText());
      active.setSelected(row.path("active").asBoolean());
      form.getChildren()
          .addAll(
              new Label("Store UUID: " + row.path("store_uuid").asText()),
              new Label("Business name"),
              business,
              new Label("Store name"),
              name,
              new Label("Code"),
              code,
              new Label("Address"),
              address,
              new Label("Phone"),
              phone,
              new Label("Email"),
              email,
              active);
      feedback.setText(
          "Store configuration loaded from the local Master. Other stores configure their own Masters independently.");
    }
    filling = false;
    dirty = false;
    pending = null;
  }

  private void save() {
    if (state == null || busy) return;
    if (business.getText().isBlank()
        || business.getText().strip().length() > 200
        || name.getText().isBlank()
        || name.getText().strip().length() > 200
        || code.getText().isBlank()
        || code.getText().strip().length() > 40) {
      feedback.setText(
          "Enter a business name, store name (up to 200 characters) and code (up to 40 characters).");
      return;
    }
    if (pending == null) pending = UUID.randomUUID();
    boolean configured = state.path("configured").asBoolean();
    var body = new LinkedHashMap<String, Object>();
    body.put("commandId", pending);
    body.put("businessName", business.getText());
    if (configured) {
      body.put("expectedRevision", state.path("store").path("revision").asLong());
      body.put("name", name.getText());
      body.put("code", code.getText());
      body.put("address", address.getText());
      body.put("phone", phone.getText());
      body.put("email", email.getText());
      body.put("active", active.isSelected());
    } else {
      body.put("businessName", business.getText());
      body.put("storeName", name.getText());
      body.put("code", code.getText());
    }
    work(
        () -> request(configured ? "PUT" : "POST", body),
        value -> {
          render(value);
          feedback.setText("Local store saved on the Master.");
        });
  }

  private void work(Callable<JsonNode> task, java.util.function.Consumer<JsonNode> done) {
    if (busy || closed) return;
    busy = true;
    form.setDisable(true);
    save.setDisable(true);
    feedback.setText("Working…");
    executor.execute(
        () -> {
          try {
            var result = task.call();
            Platform.runLater(
                () -> {
                  if (closed) return;
                  unlock();
                  done.accept(result);
                });
          } catch (Exception error) {
            Platform.runLater(
                () -> {
                  if (closed) return;
                  unlock();
                  int status =
                      error instanceof DevGateway.Failure f
                          ? f.status
                          : error
                                  instanceof
                                  za.co.onlinepos.installation.TrustedStoreClient.ModuleFailure f
                              ? f.status()
                              : 503;
                  feedback.setText(
                      status == 409
                          ? "Store details changed. Reload and review before saving; edits are retained."
                          : status == 401 || status == 403
                              ? "Owner authorization is required. Reopen Administration with a current Owner session."
                              : status == 400
                                  ? "Check business/store names, code and contact details."
                                  : "Connection failed. Edits are retained; retry safely.");
                  if (status == 409 || status == 401 || status == 403) save.setDisable(true);
                });
          }
        });
  }

  private JsonNode request(String method, Object body) throws Exception {
    if (gateway instanceof DevGateway dev) return dev.call("/grid/store", method, body);
    return gateway.admin("store", method, body, token);
  }

  private void unlock() {
    busy = false;
    form.setDisable(false);
    save.setDisable(false);
  }

  boolean confirmClose() {
    return !busy
        && (!dirty
            || new Alert(
                        Alert.AlertType.CONFIRMATION,
                        "Discard unsaved store details?",
                        ButtonType.OK,
                        ButtonType.CANCEL)
                    .showAndWait()
                    .orElse(ButtonType.CANCEL)
                == ButtonType.OK);
  }

  public void close() {
    closed = true;
  }
}
