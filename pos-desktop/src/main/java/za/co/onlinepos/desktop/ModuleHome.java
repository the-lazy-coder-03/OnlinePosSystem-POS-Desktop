package za.co.onlinepos.desktop;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import za.co.onlinepos.contracts.PosModule;
import za.co.onlinepos.installation.Installation;
import za.co.onlinepos.installation.TrustedStoreClient;

final class ModuleHome extends BorderPane implements AutoCloseable {
  private final ExecutorService executor;
  private final java.util.function.LongSupplier clock;
  private final ModuleGateway gateway;
  private final String storeLabel;
  private AdministrationPanel administration;
  private CataloguePanel catalogue;
  private final Label status = new Label("Checking store connection…");
  private final Label notice = new Label();
  private final GridPane grid = new GridPane();
  private final VBox header;
  private final Timeline timer;
  private String token;
  private long lastActivity;
  private long lastRefresh;
  private boolean pending;
  private boolean closed;
  private long generation;
  private Dialog<ButtonType> pinDialog;

  ModuleHome(Installation installation, ModuleGateway gateway, ExecutorService executor) {
    this(installation, gateway, executor, System::nanoTime);
  }

  ModuleHome(
      Installation installation,
      ModuleGateway gateway,
      ExecutorService executor,
      java.util.function.LongSupplier clock) {
    this.clock = clock;
    this.gateway = gateway;
    this.storeLabel =
        za.co.onlinepos.contracts.Branch.fromId(installation.branchId()).displayName()
            + " · "
            + installation.terminalName();
    this.executor = executor;
    getStyleClass().add("module-home");
    var title = new Label("OnlinePOS");
    title.getStyleClass().add("home-title");
    var identity =
        new Label("Store " + installation.branchId() + " · " + installation.terminalName());
    header = new VBox(6, title, identity, status, notice);
    header.setPadding(new Insets(24));
    setTop(header);
    grid.setId("module-grid");
    grid.setHgap(18);
    grid.setVgap(18);
    grid.setPadding(new Insets(12, 24, 24, 24));
    for (int i = 0; i < 3; i++) {
      var col = new ColumnConstraints();
      col.setPercentWidth(100.0 / 3);
      col.setHgrow(Priority.ALWAYS);
      grid.getColumnConstraints().add(col);
      var row = new RowConstraints();
      row.setPercentHeight(100.0 / 3);
      row.setVgrow(Priority.ALWAYS);
      grid.getRowConstraints().add(row);
    }
    for (int i = 0; i < 9; i++) {
      if (i < PosModule.values().length) {
        var module = PosModule.values()[i];
        var button = new Button();
        button.setId("module-" + module.name());
        button.setAccessibleText(module.label());
        var icon = new javafx.scene.shape.SVGPath();
        icon.setContent(
            switch (module) {
              case SALES_ORDERS ->
                  "M2 3 H5 L8 16 H20 L23 7 H6 M10 21 A1 1 0 1 0 10 19 A1 1 0 1 0 10 21 M19 21 A1 1 0 1 0 19 19 A1 1 0 1 0 19 21";
              case CUSTOMERS ->
                  "M16 7 A4 4 0 1 0 8 7 A4 4 0 1 0 16 7 M4 22 V19 Q4 14 12 14 Q20 14 20 19 V22";
              case CATALOGUE -> "M3 3 H21 V23 H3 Z M7 8 H17 M7 13 H17 M7 18 H17";
              case CASH -> "M2 5 H22 V21 H2 Z M2 5 L18 1 V5 M16 11 H22 V17 H16 Z";
              case REPORTS -> "M2 2 V23 H24 M6 19 V13 H9 V19 M13 19 V8 H16 V19 M20 19 V3 H23 V19";
              case ADMINISTRATION ->
                  "M12 2 V5 M12 19 V22 M2 12 H5 M19 12 H22 M5 5 L7 7 M17 17 L19 19 M5 19 L7 17 M17 7 L19 5 M19 12 A7 7 0 1 0 5 12 A7 7 0 1 0 19 12 M15 12 A3 3 0 1 0 9 12 A3 3 0 1 0 15 12";
            });
        icon.getStyleClass().add("module-icon");
        var iconBox = new javafx.scene.layout.StackPane(icon);
        iconBox.setMinSize(36, 36);
        iconBox.setPrefSize(36, 36);
        var label = new Label(module.label());
        label.setWrapText(true);
        label.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        var graphic = new VBox(14, iconBox, label);
        graphic.setAlignment(Pos.CENTER);
        graphic.setMouseTransparent(true);
        button.setGraphic(graphic);
        button.getStyleClass().add("module-tile");
        button.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        button.setWrapText(true);
        button.setOnAction(
            event -> {
              if (module == PosModule.SALES_ORDERS) openSales();
              else if (gateway instanceof PreviewGateway) openPreview(module);
              else prompt(module);
            });
        final int index = i;
        button.setOnKeyPressed(
            event -> {
              int next =
                  switch (event.getCode()) {
                    case RIGHT -> index + 1;
                    case LEFT -> index - 1;
                    case DOWN -> index + 3;
                    case UP -> index - 3;
                    default -> -1;
                  };
              if (next >= 0 && next < 6) {
                grid.getChildren().get(next).requestFocus();
                event.consume();
              }
            });
        grid.add(button, i % 3, i / 3);
      } else {
        var blank = new Region();
        blank.setId("module-blank-" + i);
        blank.setMouseTransparent(true);
        blank.setFocusTraversable(false);
        grid.add(blank, i % 3, i / 3);
      }
    }
    setCenter(grid);
    addEventFilter(KeyEvent.KEY_PRESSED, event -> activity());
    addEventFilter(MouseEvent.MOUSE_PRESSED, event -> activity());
    addEventFilter(javafx.scene.input.TouchEvent.TOUCH_PRESSED, event -> activity());
    timer = new Timeline(new KeyFrame(Duration.seconds(1), event -> tick()));
    timer.setCycleCount(Timeline.INDEFINITE);
    timer.play();
    checkConnection();
  }

  private void checkConnection() {
    executor.execute(
        () -> {
          boolean connected;
          try {
            gateway.check();
            connected = true;
          } catch (Exception e) {
            connected = false;
          }
          final boolean up = connected;
          Platform.runLater(
              () -> {
                if (!closed)
                  status.setText(
                      up ? "Store connected" : "Store unavailable · check the Master connection");
              });
        });
  }

  private void openSales() {
    if (pending || closed || token != null) return;
    notice.setText("");
    showModule(PosModule.SALES_ORDERS, null);
  }

  void openPreview(PosModule module) {
    if (!(gateway instanceof PreviewGateway))
      throw new IllegalStateException("Preview requires an isolated sample gateway");
    token = "preview-only";
    lastActivity = clock.getAsLong();
    lastRefresh = lastActivity;
    showModule(module, "Preview user — sample data only");
  }

  private void prompt(PosModule module) {
    if (pending || closed || token != null) return;
    notice.setText("");
    var pin = new PasswordField();
    pin.setId("staff-pin");
    pin.setPromptText("Six-digit staff PIN");
    pin.setAccessibleText("Staff PIN");
    pin.setTextFormatter(
        new TextFormatter<String>(
            change -> change.getControlNewText().matches("[0-9]{0,6}") ? change : null));
    var keypad = new GridPane();
    keypad.setHgap(8);
    keypad.setVgap(8);
    String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "Clear", "0", "⌫"};
    for (int i = 0; i < keys.length; i++) {
      String key = keys[i];
      var button = new Button(key);
      button.setMinSize(72, 52);
      button.setFocusTraversable(true);
      button.setOnAction(
          e -> {
            if (key.equals("Clear")) pin.clear();
            else if (key.equals("⌫")) {
              if (!pin.getText().isEmpty()) pin.deleteText(pin.getLength() - 1, pin.getLength());
            } else pin.appendText(key);
          });
      keypad.add(button, i % 3, i / 3);
    }
    var dialog = new Dialog<ButtonType>();
    pinDialog = dialog;
    dialog.setTitle(module.label() + " · Staff access");
    if (getScene() != null) dialog.initOwner(getScene().getWindow());
    var enter = new ButtonType("Open module", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(enter, ButtonType.CANCEL);
    dialog.getDialogPane().setContent(new VBox(12, new Label("Enter your staff PIN"), pin, keypad));
    dialog
        .getDialogPane()
        .lookupButton(enter)
        .disableProperty()
        .bind(pin.textProperty().length().isNotEqualTo(6));
    dialog.setOnShown(event -> pin.requestFocus());
    dialog.setOnHidden(
        event -> {
          pinDialog = null;
          if (dialog.getResult() != enter) {
            pin.clear();
            return;
          }
          char[] value = pin.getText().toCharArray();
          pin.clear();
          pending = true;
          grid.setDisable(true);
          notice.setText("Checking access…");
          long attempt = ++generation;
          executor.execute(
              () -> {
                try {
                  var result = gateway.enter(module, value);
                  String session = result.path("token").asText();
                  Platform.runLater(
                      () -> {
                        if (closed || attempt != generation) {
                          revoke(session);
                          return;
                        }
                        pending = false;
                        grid.setDisable(false);
                        token = session;
                        lastActivity = clock.getAsLong();
                        lastRefresh = lastActivity;
                        status.setText("Store connected");
                        notice.setText("");
                        showModule(module, result.path("username").asText());
                      });
                } catch (Exception failure) {
                  Platform.runLater(
                      () -> {
                        if (closed || attempt != generation) return;
                        pending = false;
                        grid.setDisable(false);
                        notice.setText(
                            failure instanceof TrustedStoreClient.ModuleFailure f
                                    && f.status() < 500
                                ? (f.status() == 429
                                    ? "Too many attempts. Please wait before trying again."
                                    : "Access refused.")
                                : "Unable to connect or PIN access is not configured. Contact an administrator.");
                      });
                } finally {
                  Arrays.fill(value, '\0');
                }
              });
        });
    dialog.show();
  }

  private void showModule(PosModule module, String username) {
    if (module == PosModule.CUSTOMERS) {
      try {
        var page = za.co.onlinepos.desktop.customers.CustomersPage.load();
        var back = new Button("Home / Lock");
        back.setId("home-lock");
        back.setOnAction(event -> lock(""));
        var bar = new javafx.scene.layout.HBox(16, back, new Label("Staff: " + username));
        bar.setPadding(new Insets(12, 24, 12, 24));
        setTop(bar);
        setCenter(page);
      } catch (java.io.IOException failure) {
        lock("Unable to open customer profiles.");
      }
      return;
    }
    if (module == PosModule.CATALOGUE) {
      catalogue =
          new CataloguePanel(
              gateway, executor, token, storeLabel, username, this::lock, this::activity);
      setTop(null);
      setCenter(catalogue);
      return;
    }
    if (module == PosModule.ADMINISTRATION) {
      administration =
          new AdministrationPanel(
              gateway, executor, token, storeLabel, username, this::lock, this::activity);
      setTop(null);
      setCenter(administration);
      return;
    }
    var heading = new Label(module.label());
    heading.getStyleClass().add("home-title");
    var lock = new Button("Home / Lock");
    lock.setId("home-lock");
    lock.setOnAction(event -> lock(""));
    var content =
        new VBox(
            24,
            heading,
            new Label(username == null ? "Sales / Orders" : "Staff: " + username),
            new Label(
                "This module is still being built. Operational functions are not yet available."),
            lock);
    content.setAlignment(Pos.CENTER);
    content.setPadding(new Insets(24));
    setCenter(content);
    lock.requestFocus();
  }

  private void activity() {
    if (token != null) lastActivity = clock.getAsLong();
  }

  void tick() {
    if (closed) return;
    long now = clock.getAsLong();
    if (token == null) {
      if (now - lastRefresh > java.time.Duration.ofSeconds(30).toNanos()) {
        lastRefresh = now;
        checkConnection();
      }
      return;
    }
    if (now - lastActivity >= java.time.Duration.ofMinutes(5).toNanos()) {
      lock("Locked after inactivity.");
      return;
    }
    if (!pending && now - lastRefresh >= java.time.Duration.ofSeconds(15).toNanos()) {
      pending = true;
      lastRefresh = now;
      String current = token;
      long attempt = generation;
      // Polling does not extend server expiry; only actual user input permits activity refresh.
      boolean touched = lastActivity > now - java.time.Duration.ofSeconds(20).toNanos();
      executor.execute(
          () -> {
            boolean valid;
            try {
              gateway.request(touched ? "activity" : "me", touched ? "POST" : "GET", null, current);
              valid = true;
            } catch (Exception e) {
              valid = false;
            }
            final boolean accepted = valid;
            Platform.runLater(
                () -> {
                  if (closed || attempt != generation) return;
                  pending = false;
                  if (!accepted) lock("Access expired or store unavailable. Enter your PIN again.");
                });
          });
    }
  }

  private void revoke(String session) {
    executor.execute(
        () -> {
          try {
            gateway.request("logout", "POST", null, session);
          } catch (Exception ignored) {
            /* Local access already cleared; server expires in five minutes. */
          }
        });
  }

  private void lock(String message) {
    if (catalogue != null) {
      catalogue.close();
      catalogue = null;
    }
    if (administration != null) {
      administration.close();
      administration = null;
    }
    ++generation;
    String previous = token;
    token = null;
    pending = false;
    grid.setDisable(false);
    setTop(header);
    setCenter(grid);
    notice.setText(message);
    if (previous != null) revoke(previous);
    grid.getChildren().getFirst().requestFocus();
  }

  @Override
  public void close() {
    closed = true;
    timer.stop();
    if (pinDialog != null) pinDialog.close();
    lock("");
  }

  void verifySmokeScenario() {
    if (grid.getChildren().size() != 9
        || grid.getChildren().stream().filter(Button.class::isInstance).count() != 6)
      throw new IllegalStateException("Invalid module grid");
    if (grid.getChildren().subList(6, 9).stream()
        .anyMatch(n -> n.isFocusTraversable() || !n.isMouseTransparent()))
      throw new IllegalStateException("Interactive blank grid cells");
  }
}
