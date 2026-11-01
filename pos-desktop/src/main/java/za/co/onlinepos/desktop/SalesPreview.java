package za.co.onlinepos.desktop;

import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.screen.ScreenDocuments;
import za.co.onlinepos.installation.JsonFiles;

/** Isolated visual review; never reads installation secrets or opens the store database. */
public final class SalesPreview {
  private SalesPreview() {}

  public static void main(String[] args) {
    if (System.getProperty("os.name").startsWith("Windows")
        && System.getProperty("prism.order") == null) System.setProperty("prism.order", "sw");
    Platform.startup(
        () -> {
          try {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var gateway = new PreviewGateway();
            var catalog =
                JsonFiles.JSON.treeToValue(
                    gateway.catalogue("items", "GET", null, "preview"),
                    ItemCustomization.Catalog.class);
            var runtime =
                new SalesRuntime(
                    gateway,
                    executor,
                    "preview",
                    ScreenDocuments.starter(PreviewGateway.SCREEN_STORE, catalog),
                    catalog);
            var editor = new ScreenBuilderEditor(gateway, executor, "isolated-preview");
            var sales = new javafx.scene.control.Tab("Sales", runtime);
            sales.setClosable(false);
            var builder = new javafx.scene.control.Tab("Builder preview", editor);
            builder.setClosable(false);
            var root = new BorderPane(new javafx.scene.control.TabPane(sales, builder));
            root.setTop(
                new Label(
                    "Sales preview · sample catalogue · no saved orders or payments · installed store unchanged"));
            var stage = new Stage();
            stage.setTitle("OnlinePOS — Sales Preview");
            stage.setScene(new Scene(root, 1280, 820));
            stage.setMinWidth(800);
            stage.setMinHeight(600);
            stage.setOnHidden(
                e -> {
                  runtime.close();
                  editor.close();
                  executor.shutdownNow();
                  Platform.exit();
                });
            stage.show();
            System.out.println("SALES_PHOTO_PREVIEW_OPEN");
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
