package za.co.onlinepos.desktop.customers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
    named = "POS_TEST_FX",
    matches = "true")
class AccountsPageTest {
  @Test
  void pageDialogsAndSearch() throws Exception {
    var startup = new CountDownLatch(1);
    try {
      Platform.startup(
          () -> {
            Platform.setImplicitExit(false);
            startup.countDown();
          });
    } catch (IllegalStateException alreadyStarted) {
      Platform.runLater(startup::countDown);
    }
    assertTrue(startup.await(15, TimeUnit.SECONDS));
    var result = new CompletableFuture<Void>();
    Platform.runLater(
        () -> {
          Stage stage = new Stage();
          try {
            Parent root = FXMLLoader.load(CustomersController.class.getResource("customers.fxml"));
            stage.setScene(new Scene(root, 1200, 760));
            stage.show();
            root.applyCss();
            root.layout();
            assertTrue(
                root.lookupAll(".preview").stream()
                    .anyMatch(n -> ((Label) n).getText().contains("reset")));
            var table = (TableView<?>) root.lookup("#customers");
            var search = (TextField) root.lookup("#search");
            assertEquals(3, table.getItems().size());
            assertTrue(root.lookup("#noSelection").isVisible());
            search.setText("EXAMPLE.TEST");
            assertEquals(2, table.getItems().size());
            search.setText("missing");
            assertTrue(table.getItems().isEmpty());
            search.clear();
            table.getSelectionModel().select(0);
            var failure = new AtomicReference<Throwable>();
            Platform.runLater(
                () -> {
                  try {
                    GridPane form = dialogForm(stage);
                    text(form, 0).setText("Preview Customer");
                    text(form, 2).setText("0800000000");
                    dialogSave(form).fire();
                  } catch (Exception | AssertionError e) {
                    failure.set(e);
                    closeDialog(stage);
                  }
                });
            button(root, "Add customer").fire();
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(4, table.getItems().size());
            assertEquals("Preview Customer", ((Label) root.lookup("#customerName")).getText());
            Platform.runLater(
                () -> {
                  try {
                    GridPane form = dialogForm(stage);
                    text(form, 0).setText("Edited Customer");
                    dialogSave(form).fire();
                  } catch (Exception | AssertionError e) {
                    failure.set(e);
                    closeDialog(stage);
                  }
                });
            button(root, "Edit profile").fire();
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals("Edited Customer", ((Label) root.lookup("#customerName")).getText());
            stage.setWidth(940);
            stage.setHeight(620);
            root.applyCss();
            root.layout();
            assertTrue(root.lookup("#details").isVisible());
            result.complete(null);
          } catch (Exception | AssertionError e) {
            result.completeExceptionally(e);
          } finally {
            stage.close();
          }
        });
    result.get(30, TimeUnit.SECONDS);
  }

  private static Button button(Parent root, String title) {
    return (Button)
        root.lookupAll(".button").stream()
            .filter(n -> ((Button) n).getText().equals(title))
            .findFirst()
            .orElseThrow();
  }

  private static GridPane dialogForm(Stage owner) {
    Window window =
        Window.getWindows().stream()
            .filter(w -> w != owner && w.isShowing())
            .findFirst()
            .orElseThrow();
    return (GridPane)
        ((ScrollPane) ((DialogPane) window.getScene().getRoot()).getContent()).getContent();
  }

  private static TextField text(GridPane form, int row) {
    return (TextField)
        ((VBox)
                form.getChildren().stream()
                    .filter(n -> n instanceof VBox && GridPane.getRowIndex(n) == row)
                    .findFirst()
                    .orElseThrow())
            .getChildren()
            .getFirst();
  }

  private static Button dialogSave(GridPane form) {
    return (Button) ((DialogPane) form.getScene().getRoot()).lookupButton(ButtonType.OK);
  }

  private static void closeDialog(Stage owner) {
    Window.getWindows().stream()
        .filter(w -> w != owner && w.isShowing())
        .toList()
        .forEach(Window::hide);
  }
}
