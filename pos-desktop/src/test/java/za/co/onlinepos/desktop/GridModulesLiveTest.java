package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.InstallationRole;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.installation.Installation;
import za.co.onlinepos.installation.JsonFiles;

@EnabledIfEnvironmentVariable(named = "POS_TEST_DEV_CONNECTION", matches = ".+")
class GridModulesLiveTest {
  @BeforeAll
  static void start() throws Exception {
    FxTestSupport.start();
  }

  static <T> T fx(Callable<T> task) throws Exception {
    var result = new CompletableFuture<T>();
    Platform.runLater(
        () -> {
          try {
            result.complete(task.call());
          } catch (Exception | AssertionError failure) {
            result.completeExceptionally(failure);
          }
        });
    return result.get(20, TimeUnit.SECONDS);
  }

  static void await(Callable<Boolean> task) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < end) {
      if (fx(task)) return;
      Thread.sleep(30);
    }
    fail("H2 grid workflow timed out");
  }

  static DialogPane dialog() {
    return (DialogPane)
        Window.getWindows().stream()
            .filter(w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane)
            .findFirst()
            .orElseThrow()
            .getScene()
            .getRoot();
  }

  @Test
  void customersCatalogueConfiguredDraftAndAdministrationUseRealH2() throws Exception {
    var config =
        JsonFiles.JSON.readTree(
            Files.readString(Path.of(System.getenv("POS_TEST_DEV_CONNECTION"))));
    var gateway =
        new DevGateway(
            config.path("token").asText(),
            config.path("instanceId").asText(),
            config.path("port").asInt());
    // Real empty-install setup and owner-created catalogue; production has no sample importer.
    if (!gateway.call("/grid/store", "GET", null).path("configured").asBoolean()) {
      gateway.call(
          "/grid/store",
          "POST",
          Map.of(
              "commandId",
              UUID.randomUUID(),
              "businessName",
              "UI test business",
              "storeName",
              "UI test store",
              "code",
              "UITEST"));
    }
    var createdCategory =
        gateway.call(
            "/categories",
            "POST",
            new za.co.onlinepos.contracts.DevData.Save(
                UUID.randomUUID(), null, "Owner products", 0, true));
    var fields = new java.util.LinkedHashMap<String, Object>();
    fields.put("id", 0);
    fields.put("category_id", createdCategory.path("id").asInt());
    fields.put("name", "Owner-created item");
    fields.put("description", "");
    fields.put("active", true);
    fields.put("sort_order", 0);
    fields.put("is_300ml", false);
    fields.put("is_2l", false);
    var initial = gateway.catalogue("state", "GET", null, null);
    gateway.catalogue(
        "records",
        "PUT",
        new za.co.onlinepos.contracts.CatalogueManagement.Edit(
            UUID.randomUUID(),
            initial.path("revision").asLong(),
            List.of(
                new za.co.onlinepos.contracts.CatalogueManagement.Change(
                    "menu_item", null, fields, false)),
            false),
        null);
    initial = gateway.catalogue("state", "GET", null, null);
    long itemId = initial.path("tables").path("menu_item").get(0).path("id").asLong();
    gateway.catalogue(
        "records",
        "PUT",
        new za.co.onlinepos.contracts.CatalogueManagement.Edit(
            UUID.randomUUID(),
            initial.path("revision").asLong(),
            List.of(
                new za.co.onlinepos.contracts.CatalogueManagement.Change(
                    "branch_menu_item_price",
                    null,
                    Map.of(
                        "branch_id",
                        1,
                        "menu_item_id",
                        itemId,
                        "price",
                        new java.math.BigDecimal("20.00")),
                    false)),
            false),
        null);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var home =
          fx(
              () ->
                  new ModuleHome(
                      new Installation(
                          1,
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          UUID.randomUUID(),
                          1,
                          InstallationRole.MASTER,
                          "H2 grid integration",
                          "INSTALLED"),
                      gateway,
                      executor));
      var stage =
          fx(
              () -> {
                var s = new Stage();
                var scene = new Scene(home, 1280, 900);
                scene
                    .getStylesheets()
                    .add(MenuPreview.class.getResource("setup.css").toExternalForm());
                s.setScene(scene);
                s.show();
                return s;
              });
      try {
        fx(
            () -> {
              ((Button) home.lookup("#module-CUSTOMERS")).fire();
              return null;
            });
        await(
            () ->
                home.lookup("#customer-add") != null && !home.lookup("#customer-add").isDisabled());
        fx(
            () -> {
              Platform.runLater(() -> ((Button) home.lookup("#customer-add")).fire());
              return null;
            });
        await(
            () ->
                Window.getWindows().stream()
                    .anyMatch(w -> w.isShowing() && w.getScene().getRoot() instanceof DialogPane));
        fx(
            () -> {
              var popup = dialog();
              ((TextField) popup.lookup("#customer-field-first_name")).setText("Grid UI customer");
              ((TextField) popup.lookup("#customer-field-phone1")).setText("0841234567");
              ((Button) popup.lookupButton(ButtonType.OK)).fire();
              return null;
            });
        await(
            () ->
                home.lookup("#customers") instanceof TableView<?> list
                    && list.getItems().size() == 1);
        var customer = gateway.customers("?query=Grid+UI", "GET", null, null).path("items").get(0);
        assertEquals("+27841234567", customer.path("profile").path("phone1").asText());
        fx(
            () -> {
              ((Button) home.lookup("#home-lock")).fire();
              ((Button) home.lookup("#module-CATALOGUE")).fire();
              return null;
            });
        await(
            () ->
                home.lookup("#catalogue-list") instanceof TableView<?> list
                    && !list.getItems().isEmpty());
        fx(
            () -> {
              ((ButtonBase) home.lookup("#catalogue-categories")).fire();
              return null;
            });
        await(
            () ->
                home.lookup("#catalogue-list") instanceof TableView<?> list
                    && !list.getItems().isEmpty());
        fx(
            () -> {
              var table = (TableView<?>) home.lookup("#catalogue-list");
              for (int i = 0; i < table.getItems().size(); i++)
                if (((com.fasterxml.jackson.databind.JsonNode) table.getItems().get(i))
                    .path("name")
                    .asText()
                    .equals("Owner products")) table.getSelectionModel().select(i);
              return null;
            });
        await(() -> home.lookup("#catalogue-field-name") != null);
        fx(
            () -> {
              ((TextField) home.lookup("#catalogue-field-name")).setText("Grid UI edited category");
              ((Button) home.lookup("#catalogue-save")).fire();
              return null;
            });
        await(() -> ((Label) home.lookup("#catalogue-feedback")).getText().contains("Saved"));
        assertEquals(
            1,
            gateway
                .call("/categories?search=Grid+UI+edited&state=all", "GET", null)
                .path("total")
                .asInt());
        fx(
            () -> {
              ((Button) home.lookup("#catalogue-customization")).fire();
              return null;
            });
        await(
            () ->
                home.lookup("#item-product") instanceof ComboBox<?> products
                    && !products.getItems().isEmpty());
        var catalog =
            JsonFiles.JSON.treeToValue(
                gateway.catalogue("items", "GET", null, null), ItemCustomization.Catalog.class);
        var product =
            catalog.products().values().stream()
                .filter(p -> p.available() && p.combo() == null && p.groups().isEmpty())
                .findFirst()
                .orElseThrow();
        fx(
            () -> {
              var products = (ComboBox<?>) home.lookup("#item-product");
              for (int i = 0; i < products.getItems().size(); i++)
                if (products.getItems().get(i).toString().equals(product.name())) {
                  products.getSelectionModel().select(i);
                  break;
                }
              ((TabPane) home.lookup("#item-tabs")).getSelectionModel().select(4);
              return null;
            });
        await(() -> home.lookup("#draft-find-customer") != null);
        fx(
            () -> {
              ((TextField) home.lookup("#draft-customer-search")).setText("Grid UI");
              ((Button) home.lookup("#draft-find-customer")).fire();
              return null;
            });
        await(
            () ->
                ((ComboBox<?>) home.lookup("#draft-customer")).getItems().size() == 2
                    && !home.lookup("#draft-find-customer").isDisabled());
        fx(
            () -> {
              ((ComboBox<?>) home.lookup("#draft-customer")).getSelectionModel().select(1);
              ((Button) home.lookup("#item-quote")).fire();
              return null;
            });
        await(() -> !home.lookup("#item-add-draft").isDisabled());
        fx(
            () -> {
              ((Button) home.lookup("#item-add-draft")).fire();
              ((Button) home.lookup("#item-save-draft")).fire();
              return null;
            });
        await(
            () ->
                ((Label) home.lookup("#item-feedback"))
                    .getText()
                    .contains("Draft saved on Master"));
        var history =
            gateway.customers("/" + customer.path("id").asText() + "/history", "GET", null, null);
        assertEquals(1, history.path("items").size());
        assertEquals("DRAFT", history.path("items").get(0).path("state").asText());
        fx(
            () -> {
              ((Button) home.lookup("#home-lock")).fire();
              ((Button) home.lookup("#module-ADMINISTRATION")).fire();
              return null;
            });
        await(() -> home.lookup("#admin-tab-audit-history") != null);
        assertEquals(null, fx(() -> home.lookup("#admin-tab-staff")));
        fx(
            () -> {
              ((ButtonBase) home.lookup("#admin-tab-audit-history")).fire();
              return null;
            });
        await(
            () ->
                home.lookup("#admin-audit-table") instanceof TableView<?> list
                    && !list.getItems().isEmpty());
        fx(
            () -> {
              ((ButtonBase) home.lookup("#admin-tab-security-audit-tracking")).fire();
              return null;
            });
        await(
            () ->
                home.lookupAll(".label").stream()
                    .anyMatch(
                        node ->
                            node instanceof Label label
                                && label.getText().contains("Owner authentication is deferred")));
        assertFalse(fx(() -> !home.lookupAll(".password-field").isEmpty()));
        fx(
            () -> {
              ((Button) home.lookup("#home-lock")).fire();
              ((Button) home.lookup("#module-SALES_ORDERS")).fire();
              return null;
            });
        await(
            () ->
                home.lookup("#item-product") instanceof ComboBox<?> products
                    && !products.getItems().isEmpty());
        fx(
            () -> {
              home.applyCss();
              home.layout();
              assertEquals(1, ((TabPane) home.lookup("#item-tabs")).getTabs().size());
              var products = (ComboBox<?>) home.lookup("#item-product");
              for (int i = 0; i < products.getItems().size(); i++)
                if (products.getItems().get(i).toString().equals(product.name()))
                  products.getSelectionModel().select(i);
              home.applyCss();
              home.layout();
              ((Button) home.lookup("#item-quote")).fire();
              return null;
            });
        await(() -> !home.lookup("#item-add-draft").isDisabled());
        fx(
            () -> {
              ((Button) home.lookup("#item-add-draft")).fire();
              ((Button) home.lookup("#item-save-draft")).fire();
              return null;
            });
        await(
            () ->
                ((Label) home.lookup("#item-feedback"))
                    .getText()
                    .contains("Draft saved on Master"));
        fx(
            () -> {
              home.applyCss();
              home.layout();
              var image = home.snapshot(null, null);
              var bitmap =
                  new java.awt.image.BufferedImage(
                      (int) image.getWidth(),
                      (int) image.getHeight(),
                      java.awt.image.BufferedImage.TYPE_INT_ARGB);
              for (int y = 0; y < bitmap.getHeight(); y++)
                for (int x = 0; x < bitmap.getWidth(); x++)
                  bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
              javax.imageio.ImageIO.write(
                  bitmap, "png", Path.of("target", "sales-drafts.png").toFile());
              return null;
            });

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
