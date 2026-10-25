package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.Administration;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.TrustedStoreClient;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class AdministrationPanelTest {
  private static final UUID OWNER = UUID.randomUUID(), CASHIER = UUID.randomUUID();
  private static final Set<String> ALL =
      Set.of(
          "module.sales_orders",
          "module.customers",
          "module.catalogue",
          "module.cash",
          "module.reports",
          "module.administration",
          "staff.manage",
          "rbac.manage",
          "audit.view");

  @BeforeAll
  static void startFx() throws Exception {
    FxTestSupport.start();
  }

  private static <T> T fx(Callable<T> action) throws Exception {
    var value = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            value.complete(action.call());
          } catch (Exception e) {
            value.completeExceptionally(e);
          }
        });
    return value.get(15, TimeUnit.SECONDS);
  }

  private static void await(Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (fx(condition)) return;
      Thread.sleep(20);
    }
    fail("Administration transition timed out");
  }

  private static JsonNode tree(Object value) {
    return JsonFiles.JSON.valueToTree(value);
  }

  static final class Gateway extends ModuleGateway {
    final List<JsonNode> staff = new ArrayList<>(), roles = new ArrayList<>();
    final List<UUID> commands = new ArrayList<>();
    final List<String> paths = new ArrayList<>();
    final boolean owner;
    final Set<String> permissions;
    final boolean configured;
    volatile boolean offline;
    volatile int pinSaves;

    Gateway(boolean owner, Set<String> permissions, boolean configured) {
      super(Path.of("unused-admin-test"));
      this.owner = owner;
      this.permissions = permissions;
      this.configured = configured;
      staff.add(row(OWNER, "owner", List.of("MANAGER"), true, configured));
      staff.add(row(CASHIER, "cashier", List.of("CASHIER"), false, false));
      roles.add(
          tree(Map.of("name", "MANAGER", "revision", 0, "permissions", ALL, "editable", owner)));
      roles.add(
          tree(
              Map.of(
                  "name",
                  "CASHIER",
                  "revision",
                  0,
                  "permissions",
                  List.of("module.sales_orders"),
                  "editable",
                  true)));
    }

    private JsonNode row(
        UUID id, String username, List<String> assigned, boolean pin, boolean isOwner) {
      return tree(
          Map.of(
              "staffId",
              id,
              "username",
              username,
              "roles",
              assigned,
              "enabled",
              true,
              "revision",
              0,
              "pinConfigured",
              pin,
              "passwordLoginEnabled",
              id.equals(OWNER),
              "owner",
              isOwner,
              "editable",
              owner || !isOwner));
    }

    @Override
    public void check() {}

    @Override
    public JsonNode enter(za.co.onlinepos.contracts.PosModule module, char[] pin) {
      java.util.Arrays.fill(pin, '\0');
      return tree(Map.of("token", "test-session", "username", "owner"));
    }

    @Override
    public JsonNode request(String endpoint, String method, byte[] body, String token) {
      return tree(Map.of());
    }

    @Override
    public synchronized JsonNode admin(String path, String method, Object payload, String token)
        throws Exception {
      paths.add(path);
      if (method.equals("PUT") && payload instanceof Administration.StaffEdit edit)
        commands.add(edit.commandId());
      if (offline) throw new TrustedStoreClient.ModuleFailure(503);
      if (path.equals("overview"))
        return tree(
            Map.of(
                "username",
                "owner",
                "owner",
                owner,
                "ownerConfigured",
                configured,
                "permissions",
                permissions,
                "implementedPermissions",
                ALL,
                "staffCount",
                2,
                "pendingPins",
                1,
                "roleCount",
                2));
      if (method.equals("GET") && path.equals("staff/options")) return tree(roles);
      if (method.equals("GET") && path.startsWith("staff?"))
        return tree(Map.of("items", staff, "hasMore", false, "page", 0));
      if (method.equals("GET") && path.equals("roles")) return tree(roles);
      if (method.equals("GET") && path.startsWith("audit?"))
        return tree(
            Map.of(
                "items",
                List.of(
                    Map.of(
                        "occurredAt",
                        "2026-10-25T10:00:00Z",
                        "actor",
                        "owner",
                        "action",
                        "ADMIN_STAFF_CHANGED",
                        "targetName",
                        "cashier")),
                "page",
                0,
                "hasMore",
                false));
      if (payload instanceof Administration.StaffEdit edit) {
        staff.removeIf(r -> r.path("staffId").asText().equals(edit.staffId().toString()));
        staff.add(row(edit.staffId(), edit.username(), List.copyOf(edit.roles()), false, false));
        return tree(Map.of("reauthenticate", false));
      }
      if (payload instanceof Administration.RoleEdit edit) {
        roles.removeIf(r -> r.path("name").asText().equals(edit.name()));
        roles.add(
            tree(
                Map.of(
                    "name",
                    edit.name(),
                    "permissions",
                    edit.permissions(),
                    "revision",
                    0,
                    "editable",
                    true)));
        return tree(Map.of("reauthenticate", false));
      }
      if (payload instanceof Administration.PinEdit edit) {
        assertEquals(6, edit.pin().length());
        pinSaves++;
        return tree(Map.of("reauthenticate", path.contains(OWNER.toString())));
      }
      throw new IllegalArgumentException("Unexpected test request");
    }
  }

  record View(
      AdministrationPanel panel,
      Stage stage,
      ExecutorService executor,
      AtomicReference<String> locked)
      implements AutoCloseable {
    @Override
    public void close() throws Exception {
      fx(
          () -> {
            panel.close();
            stage.close();
            return null;
          });
      executor.close();
    }
  }

  private View open(Gateway gateway) throws Exception {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    var locked = new AtomicReference<String>();
    var holder = new AtomicReference<AdministrationPanel>();
    var panel =
        fx(
            () -> {
              var p =
                  new AdministrationPanel(
                      gateway,
                      executor,
                      "test-session",
                      "Kenridge · Front counter",
                      "owner",
                      message -> {
                        locked.set(message);
                        holder.get().close();
                      });
              holder.set(p);
              return p;
            });
    var stage =
        fx(
            () -> {
              var window = new Stage();
              var scene = new Scene(panel, 1250, 850);
              scene
                  .getStylesheets()
                  .add(
                      java.util.Objects.requireNonNull(
                              AdministrationPanel.class.getResource("setup.css"))
                          .toExternalForm());
              window.setScene(scene);
              window.show();
              return window;
            });
    await(
        () ->
            panel.lookup("#admin-tab-overview") != null
                && !panel.lookup("#admin-tab-overview").isDisabled());
    return new View(panel, stage, executor, locked);
  }

  private void click(View view, String id) throws Exception {
    fx(
        () -> {
          var node = view.panel.lookup(id);
          if (node instanceof Button button) button.fire();
          else ((ToggleButton) node).fire();
          view.panel.applyCss();
          view.panel.layout();
          return null;
        });
  }

  @SuppressWarnings("unchecked")
  private TableView<JsonNode> table(View view, String id) {
    return (TableView<JsonNode>) view.panel.lookup(id);
  }

  private void select(View view, String username) throws Exception {
    fx(
        () -> {
          var table = table(view, "#admin-staff-table");
          table
              .getSelectionModel()
              .select(
                  table.getItems().stream()
                      .filter(r -> r.path("username").asText().equals(username))
                      .findFirst()
                      .orElseThrow());
          view.panel.applyCss();
          view.panel.layout();
          return null;
        });
    await(() -> view.panel.lookup("#admin-staff-username") != null);
  }

  private DialogPane dialog() {
    return Window.getWindows().stream()
        .filter(Window::isShowing)
        .map(w -> w.getScene().getRoot())
        .filter(DialogPane.class::isInstance)
        .map(DialogPane.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private boolean dialogVisible() {
    return Window.getWindows().stream()
        .filter(Window::isShowing)
        .anyMatch(w -> w.getScene().getRoot() instanceof DialogPane);
  }

  private void dismiss(ButtonType type) throws Exception {
    fx(
        () -> {
          ((Button) dialog().lookupButton(type)).fire();
          return null;
        });
  }

  private void screenshot(View view, String name) throws Exception {
    fx(
        () -> {
          view.panel.applyCss();
          view.panel.layout();
          var parameters = new javafx.scene.SnapshotParameters();
          parameters.setViewport(
              new javafx.geometry.Rectangle2D(0, 0, view.panel.getWidth(), view.panel.getHeight()));
          var image = view.panel.snapshot(parameters, null);
          var output =
              new java.awt.image.BufferedImage(
                  (int) image.getWidth(),
                  (int) image.getHeight(),
                  java.awt.image.BufferedImage.TYPE_INT_ARGB);
          for (int y = 0; y < output.getHeight(); y++)
            for (int x = 0; x < output.getWidth(); x++)
              output.setRGB(x, y, image.getPixelReader().getArgb(x, y));
          javax.imageio.ImageIO.write(output, "png", Path.of("target", name + ".png").toFile());
          return null;
        });
  }

  @Test
  void sidebarFormsUnsavedWarningRoleSavePinClearingAndReadOnlyAudit() throws Exception {
    var gateway = new Gateway(true, ALL, true);
    try (var view = open(gateway)) {
      screenshot(view, "admin-overview");
      click(view, "#admin-tab-staff");
      await(
          () ->
              view.panel.lookup("#admin-staff-table") != null
                  && !view.panel.lookup("#admin-staff-table").isDisabled());
      select(view, "cashier");
      fx(
          () -> {
            ((TextField) view.panel.lookup("#admin-staff-username")).setText("edited.cashier");
            return null;
          });
      Platform.runLater(
          () -> ((ToggleButton) view.panel.lookup("#admin-tab-roles-permissions")).fire());
      await(this::dialogVisible);
      dismiss(ButtonType.CANCEL);
      assertNotNull(fx(() -> view.panel.lookup("#admin-staff-table")));
      assertEquals(
          "edited.cashier",
          fx(() -> ((TextField) view.panel.lookup("#admin-staff-username")).getText()));
      Platform.runLater(
          () -> ((ToggleButton) view.panel.lookup("#admin-tab-roles-permissions")).fire());
      await(this::dialogVisible);
      dismiss(ButtonType.YES);
      await(
          () ->
              view.panel.lookup("#admin-roles-table") != null
                  && !view.panel.lookup("#admin-roles-table").isDisabled());
      click(view, "#admin-new-role");
      fx(
          () -> {
            ((TextField) view.panel.lookup("#admin-role-name")).setText("SUPERVISOR");
            view.panel.lookupAll(".check-box").stream()
                .map(CheckBox.class::cast)
                .filter(c -> "module.reports".equals(c.getUserData()))
                .findFirst()
                .orElseThrow()
                .setSelected(true);
            return null;
          });
      click(view, "#admin-save-role");
      await(
          () ->
              table(view, "#admin-roles-table").getItems().stream()
                  .anyMatch(r -> r.path("name").asText().equals("SUPERVISOR")));
      screenshot(view, "admin-roles");
      click(view, "#admin-tab-audit-history");
      await(
          () ->
              view.panel.lookup("#admin-audit-table") != null
                  && !view.panel.lookup("#admin-audit-table").isDisabled());
      screenshot(view, "admin-audit");
      assertNull(fx(() -> view.panel.lookup("#admin-save-role")));
      click(view, "#admin-tab-staff");
      await(
          () ->
              view.panel.lookup("#admin-staff-table") != null
                  && !view.panel.lookup("#admin-staff-table").isDisabled());
      select(view, "cashier");
      screenshot(view, "admin-staff");
      fx(
          () -> {
            view.stage.setWidth(900);
            view.panel.applyCss();
            view.panel.layout();
            return null;
          });
      await(() -> view.panel.getWidth() <= 900);
      fx(
          () -> {
            view.panel.applyCss();
            view.panel.layout();
            var form = view.panel.lookup("#admin-staff-username");
            var listing = view.panel.lookup("#admin-staff-table");
            assertTrue(
                listing.localToScene(listing.getBoundsInLocal()).getMaxX()
                    <= form.localToScene(form.getBoundsInLocal()).getMinX());
            assertTrue(
                form.localToScene(form.getBoundsInLocal()).getMaxX() <= view.panel.getWidth() + 1);
            return null;
          });
      screenshot(view, "admin-staff-compact");
      fx(
          () -> {
            view.stage.setWidth(1250);
            view.panel.applyCss();
            view.panel.layout();
            return null;
          });
      click(view, "#admin-reset-pin");
      var fields =
          fx(
              () -> {
                var pane = dialog();
                var first = (PasswordField) pane.lookup("#admin-new-pin");
                var second = (PasswordField) pane.lookup("#admin-confirm-pin");
                first.setText("000123");
                second.setText("000123");
                var save =
                    pane.getButtonTypes().stream()
                        .filter(t -> t.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                        .findFirst()
                        .orElseThrow();
                ((Button) pane.lookupButton(save)).fire();
                return List.of(first, second);
              });
      await(
          () ->
              gateway.pinSaves == 1
                  && view.panel.lookup("#admin-reset-pin") != null
                  && !view.panel.lookup("#admin-reset-pin").isDisabled());
      assertTrue(fx(() -> fields.stream().allMatch(f -> f.getText().isEmpty())));
      click(view, "#admin-reset-pin");
      var pending =
          fx(
              () -> {
                var field = (PasswordField) dialog().lookup("#admin-new-pin");
                field.setText("123456");
                return field;
              });
      click(view, "#home-lock");
      assertEquals("", fx(pending::getText));
      assertFalse(fx(this::dialogVisible));
    }
  }

  @Test
  void restrictedSectionsAreHiddenAndNoOwnerMeansNoPinControls() throws Exception {
    var permissions = Set.of("module.administration", "audit.view");
    var gateway = new Gateway(false, permissions, false);
    try (var view = open(gateway)) {
      assertNull(fx(() -> view.panel.lookup("#admin-tab-staff")));
      assertNull(fx(() -> view.panel.lookup("#admin-tab-roles-permissions")));
      assertNotNull(fx(() -> view.panel.lookup("#admin-tab-audit-history")));
      assertTrue(
          fx(
              () ->
                  view.panel.lookupAll(".label").stream()
                      .filter(Label.class::isInstance)
                      .map(Label.class::cast)
                      .anyMatch(
                          label -> label.getText().contains("owner has not been designated"))));
      click(view, "#admin-tab-audit-history");
      await(() -> view.panel.lookup("#admin-audit-table") != null);
      assertTrue(
          gateway.paths.stream().noneMatch(p -> p.startsWith("staff") || p.startsWith("roles")));
    }
    var staffGateway = new Gateway(false, ALL, false);
    try (var view = open(staffGateway)) {
      click(view, "#admin-tab-staff");
      await(() -> view.panel.lookup("#admin-staff-table") != null);
      select(view, "cashier");
      assertNull(fx(() -> view.panel.lookup("#admin-reset-pin")));
    }
  }

  @Test
  void failedSavePreservesFormAndCommandIdentityAndSelfPinResetLocks() throws Exception {
    var gateway = new Gateway(true, ALL, true);
    try (var view = open(gateway)) {
      click(view, "#admin-tab-staff");
      await(() -> view.panel.lookup("#admin-staff-table") != null);
      click(view, "#admin-new-staff");
      fx(
          () -> {
            ((TextField) view.panel.lookup("#admin-staff-username")).setText("new.staff");
            view.panel.lookupAll(".check-box").stream()
                .map(CheckBox.class::cast)
                .filter(c -> c.getText().equals("CASHIER"))
                .findFirst()
                .orElseThrow()
                .setSelected(true);
            return null;
          });
      gateway.offline = true;
      click(view, "#admin-save-staff");
      await(() -> ((Label) view.panel.lookup("#admin-feedback")).getText().startsWith("Unable"));
      assertEquals(
          "new.staff",
          fx(() -> ((TextField) view.panel.lookup("#admin-staff-username")).getText()));
      gateway.offline = false;
      click(view, "#admin-save-staff");
      await(
          () ->
              table(view, "#admin-staff-table").getItems().stream()
                  .anyMatch(r -> r.path("username").asText().equals("new.staff")));
      assertEquals(2, gateway.commands.size());
      assertEquals(gateway.commands.getFirst(), gateway.commands.getLast());
      select(view, "owner");
      click(view, "#admin-reset-pin");
      fx(
          () -> {
            var pane = dialog();
            ((PasswordField) pane.lookup("#admin-new-pin")).setText("123456");
            ((PasswordField) pane.lookup("#admin-confirm-pin")).setText("123456");
            var save =
                pane.getButtonTypes().stream()
                    .filter(t -> t.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                    .findFirst()
                    .orElseThrow();
            ((Button) pane.lookupButton(save)).fire();
            return null;
          });
      await(() -> view.locked.get() != null);
      assertTrue(view.locked.get().contains("Changes saved"));
      assertNull(fx(() -> view.panel.lookup("#admin-staff-table")));
    }
  }

  @Test
  void administrationUsesHomeSessionAndDialogActivityThenClearsOnIdleLock() throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var time = new java.util.concurrent.atomic.AtomicLong(1);
      var gateway = new Gateway(true, ALL, true);
      var installation =
          new za.co.onlinepos.installation.Installation(
              1,
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              1,
              za.co.onlinepos.contracts.InstallationRole.MASTER,
              "Front counter",
              "INSTALLED");
      var home = fx(() -> new ModuleHome(installation, gateway, executor, time::get));
      var stage =
          fx(
              () -> {
                var window = new Stage();
                window.setScene(new Scene(home, 1250, 850));
                window.show();
                return window;
              });
      try {
        fx(
            () -> {
              ((Button) home.lookup("#module-ADMINISTRATION")).fire();
              var pane = dialog();
              ((PasswordField) pane.lookup("#staff-pin")).setText("012345");
              var open =
                  pane.getButtonTypes().stream()
                      .filter(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                      .findFirst()
                      .orElseThrow();
              ((Button) pane.lookupButton(open)).fire();
              return null;
            });
        await(
            () ->
                home.lookup("#admin-tab-staff") != null
                    && !home.lookup("#admin-tab-staff").isDisabled());
        assertNull(fx(home::getTop));
        fx(
            () -> {
              ((ToggleButton) home.lookup("#admin-tab-staff")).fire();
              return null;
            });
        await(() -> home.lookup("#admin-staff-table") != null);
        fx(
            () -> {
              @SuppressWarnings("unchecked")
              var staff = (TableView<JsonNode>) home.lookup("#admin-staff-table");
              staff
                  .getSelectionModel()
                  .select(
                      staff.getItems().stream()
                          .filter(row -> row.path("username").asText().equals("cashier"))
                          .findFirst()
                          .orElseThrow());
              home.applyCss();
              home.layout();
              ((Button) home.lookup("#admin-reset-pin")).fire();
              return null;
            });
        time.set(TimeUnit.MINUTES.toNanos(1));
        var input =
            fx(
                () -> {
                  var field = (PasswordField) dialog().lookup("#admin-new-pin");
                  field.setText("123456");
                  field.fireEvent(
                      new javafx.scene.input.KeyEvent(
                          javafx.scene.input.KeyEvent.KEY_PRESSED,
                          "",
                          "1",
                          javafx.scene.input.KeyCode.DIGIT1,
                          false,
                          false,
                          false,
                          false));
                  return field;
                });
        time.set(TimeUnit.MINUTES.toNanos(5) + 1);
        fx(
            () -> {
              home.tick();
              return null;
            });
        assertTrue(fx(this::dialogVisible));
        time.set(TimeUnit.MINUTES.toNanos(6) + 2);
        fx(
            () -> {
              home.tick();
              return null;
            });
        assertFalse(fx(this::dialogVisible));
        assertEquals("", fx(input::getText));
        assertNotNull(fx(home::getTop));
        assertNotNull(fx(() -> home.lookup("#module-grid")));
      } finally {
        fx(
            () -> {
              home.close();
              stage.close();
              return null;
            });
      }
    }
  }
}
