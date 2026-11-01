package za.co.onlinepos.desktop;

import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.screen.PhotoSalesLayout;
import za.co.onlinepos.contracts.screen.SalesScreens;
import za.co.onlinepos.contracts.screen.SalesScreens.Document;
import za.co.onlinepos.contracts.screen.SalesScreens.Page;
import za.co.onlinepos.contracts.screen.SalesScreens.Rect;
import za.co.onlinepos.contracts.screen.SalesScreens.Widget;
import za.co.onlinepos.contracts.screen.ScreenDocuments;
import za.co.onlinepos.contracts.screen.ScreenValidation;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.ScreenAssets;
import za.co.onlinepos.installation.ScreenCodec;

/** Protected authoring surface. Session keys remain in memory; backend validates every save. */
final class ScreenBuilderEditor extends BorderPane implements AutoCloseable {
  private final ModuleGateway gateway;
  private final ExecutorService executor;
  private String token;
  private final Label status = new Label("Checking authoring access…");
  private final ComboBox<String> pageChoice = new ComboBox<>();
  private final ComboBox<com.fasterxml.jackson.databind.JsonNode> definitions = new ComboBox<>();
  private final TextField name = new TextField("Store sales screen");
  private final VBox inspector = new VBox(8);
  private final Pane canvas = new Pane();
  private final ArrayDeque<Document> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private final PauseTransition autosave = new PauseTransition(Duration.seconds(3));
  private ItemCustomization.Catalog catalog;
  private Document document;
  private UUID pageId, selection;
  private long revision, publication;
  private boolean template, dirty, busy, closed, populating;
  private SalesScreens.Save uncertain;
  private SalesScreens.Publish uncertainPublication;
  private Document dragStart;
  private double dragX, dragY;

  ScreenBuilderEditor(ModuleGateway gateway, ExecutorService executor, String token) {
    this.gateway = gateway;
    this.executor = executor;
    this.token = token;
    setPadding(new Insets(8));
    status.setWrapText(true);
    setBottom(status);
    if (gateway instanceof DevGateway) login();
    else loadContext();
    autosave.setOnFinished(
        e -> {
          if (!(gateway instanceof PreviewGateway) && dirty && !busy && uncertain == null)
            save(false);
        });
  }

  private void reauthenticate() {
    var pin = new PasswordField();
    pin.setPromptText("Administration PIN");
    var dialog = new Dialog<ButtonType>();
    dialog.setTitle("Unlock builder");
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    dialog.getDialogPane().setContent(pin);
    dialog
        .showAndWait()
        .ifPresent(
            type -> {
              if (type == ButtonType.OK) {
                String value = pin.getText();
                pin.clear();
                work(
                    () -> gateway.screens("access", "POST", Map.of("pin", value), null),
                    response -> {
                      token = response.path("token").asText();
                      status.setText("Builder unlocked · current edits retained");
                    });
              } else pin.clear();
            });
  }

  private void login() {
    var pin = new PasswordField();
    pin.setPromptText("Administration staff PIN");
    pin.setTextFormatter(
        new javafx.scene.control.TextFormatter<String>(
            c -> c.getControlNewText().matches("[0-9]{0,6}") ? c : null));
    var enter = new Button("Unlock builder");
    enter.setOnAction(
        e -> {
          String value = pin.getText();
          pin.clear();
          work(
              () -> gateway.screens("access", "POST", Map.of("pin", value), null),
              response -> {
                token = response.path("token").asText();
                loadContext();
              });
        });
    setCenter(
        new VBox(
            12,
            new Label("Sales Screen Builder · protected Administration access"),
            new Label(
                "The development actor cannot author or publish. Use an enrolled local owner/manager."),
            pin,
            enter));
  }

  private void loadContext() {
    work(
        () -> gateway.screens("context", "GET", null, token),
        context -> {
          try {
            catalog =
                JsonFiles.JSON.treeToValue(
                    context.path("catalog"), ItemCustomization.Catalog.class);
          } catch (Exception e) {
            throw new IllegalArgumentException(e);
          }
          definitions.getItems().clear();
          context.path("definitions").forEach(definitions.getItems()::add);
          definitions.setConverter(
              new javafx.util.StringConverter<>() {
                public String toString(com.fasterxml.jackson.databind.JsonNode n) {
                  return n == null
                      ? ""
                      : n.path("name").asText()
                          + " · v"
                          + n.path("revision").asLong()
                          + (n.path("template").asBoolean() ? " · template" : "");
                }

                public com.fasterxml.jackson.databind.JsonNode fromString(String s) {
                  return null;
                }
              });
          document =
              ScreenDocuments.starter(UUID.fromString(context.path("storeId").asText()), catalog);
          pageId = document.pages().getFirst().id();
          build();
          render();
          status.setText(
              "Protected builder · no screen changes until saved; publication requires a separate action");
          work(
              () -> gateway.screens("publication", "GET", null, token),
              p -> publication = p.path("sequence").asLong());
        });
  }

  private Button button(String label, Runnable action) {
    var b = new Button(label);
    if (gateway instanceof PreviewGateway
        && Set.of("Save draft", "Publish", "Save as template", "Save a copy", "History / rollback")
            .contains(label)) b.setDisable(true);
    b.setOnAction(e -> action.run());
    return b;
  }

  private void build() {
    var toolbar =
        new FlowPane(
            8,
            8,
            new Label("Screen"),
            name,
            definitions,
            button("Open", this::open),
            button("New screen", this::newScreen),
            button("New photo template", this::newPhotoTemplate),
            button("Apply photo layout to all Sales pages", this::convertPhotoLayout),
            button("Save a copy", this::saveCopy),
            button("Refresh versions", this::refreshVersions),
            button("Save draft", () -> save(false)),
            button("Publish", () -> publish(false)),
            button("History / rollback", this::history),
            button("Undo", this::undo),
            button("Redo", this::redo),
            button("Preview", this::preview),
            button("Export", this::exportDocument),
            button("Import", this::importDocument),
            button("Help", this::help));
    if (gateway instanceof DevGateway)
      toolbar.getChildren().add(button("Unlock again", this::reauthenticate));
    var pages =
        new FlowPane(
            8,
            8,
            new Label("Page"),
            pageChoice,
            button("Add page", this::addPage),
            button("Rename page", this::renamePage),
            button("Delete page", this::deletePage),
            button("Save as template", this::saveTemplate),
            button("Apply template", this::applyTemplate),
            button("Update template", this::updateTemplate),
            button("Workflows", this::workflows));
    var purpose = new ComboBox<SalesScreens.Purpose>();
    purpose.getItems().addAll(SalesScreens.Purpose.values());
    purpose.setPromptText("Page purpose");
    purpose.setOnAction(
        e -> {
          if (document != null && purpose.getValue() != null) {
            checkpoint();
            document =
                ScreenDocuments.page(
                    document,
                    new Page(pageId, page().name(), purpose.getValue(), page().widgets()));
            changed();
            render();
          }
        });
    pages.getChildren().add(purpose);
    pageChoice.setOnAction(
        e -> {
          if (!populating && pageChoice.getSelectionModel().getSelectedIndex() >= 0) {
            pageId = document.pages().get(pageChoice.getSelectionModel().getSelectedIndex()).id();
            selection = null;
            render();
          }
        });
    var palette = new VBox(8, new Label("Add a widget"));
    for (var type : SalesScreens.Type.values())
      palette.getChildren().add(button(type.name().replace('_', ' '), () -> add(type)));
    var resolution = new ComboBox<String>();
    resolution.getItems().addAll("1200 × 800", "1200 × 720", "1024 × 768", "1920 × 1080");
    resolution.setValue("1200 × 800");
    resolution.setOnAction(
        e -> {
          var parts = resolution.getValue().split(" × ");
          double width = Double.parseDouble(parts[0]), height = Double.parseDouble(parts[1]);
          checkpoint();
          double sx = width / document.canvas().width(), sy = height / document.canvas().height();
          document =
              new Document(
                  1,
                  document.screenId(),
                  document.storeId(),
                  new SalesScreens.Canvas(width, height, document.canvas().snapSize()),
                  document.pages().stream()
                      .map(
                          p ->
                              new Page(
                                  p.id(),
                                  p.name(),
                                  p.purpose(),
                                  p.widgets().stream()
                                      .map(
                                          w -> {
                                            var r = w.rect();
                                            return ScreenDocuments.geometry(
                                                w,
                                                new Rect(
                                                    r.x() * sx,
                                                    r.y() * sy,
                                                    r.w() * sx,
                                                    r.h() * sy,
                                                    r.z()));
                                          })
                                      .toList()))
                      .toList(),
                  document.workflows(),
                  document.templateOrigins());
          changed();
          render();
        });
    palette
        .getChildren()
        .addAll(
            new Label("Canvas resolution"),
            resolution,
            new Label(
                "Drag widgets to move.\nDrag the bottom-right square to resize.\nPositions snap to the canvas grid."));
    setTop(new VBox(8, toolbar, pages));
    setLeft(new ScrollPane(palette));
    var scroll = new ScrollPane(canvas);
    scroll.setPannable(true);
    setCenter(scroll);
    inspector.setPadding(new Insets(12));
    inspector.setPrefWidth(280);
    setRight(new ScrollPane(inspector));
    name.textProperty()
        .addListener(
            (o, a, b) -> {
              if (!populating) {
                dirty = true;
                autosave.playFromStart();
              }
            });
    canvas.setOnKeyPressed(
        e -> {
          if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.Z) {
            undo();
            e.consume();
          } else if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.Y) {
            redo();
            e.consume();
          } else if (selection != null) {
            var w = selected();
            if (w == null) return;
            double dx = 0, dy = 0;
            switch (e.getCode()) {
              case LEFT -> dx = -document.canvas().snapSize();
              case RIGHT -> dx = document.canvas().snapSize();
              case UP -> dy = -document.canvas().snapSize();
              case DOWN -> dy = document.canvas().snapSize();
              default -> {
                return;
              }
            }
            checkpoint();
            move(w, dx, dy, false);
            changed();
            render();
            e.consume();
          }
        });
  }

  private Page page() {
    return document.pages().stream()
        .filter(p -> p.id().equals(pageId))
        .findFirst()
        .orElse(document.pages().getFirst());
  }

  private Widget selected() {
    return page().widgets().stream().filter(w -> w.id().equals(selection)).findFirst().orElse(null);
  }

  private void replace(Widget w) {
    document =
        ScreenDocuments.page(
            document,
            new Page(
                page().id(),
                page().name(),
                page().purpose(),
                page().widgets().stream().map(x -> x.id().equals(w.id()) ? w : x).toList()));
  }

  private void checkpoint() {
    undo.push(document);
    if (undo.size() > 100) undo.removeLast();
    redo.clear();
  }

  private void changed() {
    dirty = true;
    status.setText(
        gateway instanceof PreviewGateway
            ? "Preview edits · use Export to keep a design · Save and Publish are disabled"
            : "Unsaved changes · autosaving protected draft…");
    autosave.playFromStart();
  }

  private void undo() {
    if (undo.isEmpty()) return;
    redo.push(document);
    document = undo.pop();
    changed();
    render();
  }

  private void redo() {
    if (redo.isEmpty()) return;
    undo.push(document);
    document = redo.pop();
    changed();
    render();
  }

  private void render() {
    if (document == null) return;
    populating = true;
    pageChoice.getItems().setAll(document.pages().stream().map(Page::name).toList());
    pageChoice.getSelectionModel().select(document.pages().indexOf(page()));
    populating = false;
    canvas.getChildren().clear();
    canvas.setMinSize(document.canvas().width(), document.canvas().height());
    canvas.setPrefSize(document.canvas().width(), document.canvas().height());
    canvas.setStyle("-fx-background-color:#181d39;");
    if (!canvas.getStyleClass().contains("sales-photo")) canvas.getStyleClass().add("sales-photo");
    String css = SalesRuntime.class.getResource("/sales-photo.css").toExternalForm();
    if (!canvas.getStylesheets().contains(css)) canvas.getStylesheets().add(css);
    canvas.setFocusTraversable(true);
    for (var w :
        page().widgets().stream().sorted(Comparator.comparingInt(x -> x.rect().z())).toList()) {
      var node = new BorderPane();
      var label = new Label(w.label() + "\n" + w.type().name());
      label.setWrapText(true);
      label.setStyle(
          "-fx-text-fill:"
              + w.style().foreground()
              + ";-fx-font-size:"
              + w.style().fontSize()
              + "px;");
      label.setMouseTransparent(true);
      if (w.type() == SalesScreens.Type.KEYPAD) {
        var keypad = SalesRuntime.keypadView(null, null);
        keypad.setMouseTransparent(true);
        node.setCenter(keypad);
      } else node.setCenter(label);
      SalesRuntime.place(node, w);
      if (w.id().equals(selection))
        node.setStyle(node.getStyle() + "-fx-border-color:#ff9d00;-fx-border-width:3;");
      var handle = new Label("◢");
      handle.setMinSize(44, 44);
      handle.setStyle("-fx-text-fill:#fd9b12;-fx-font-size:24;");
      final boolean[] resizing = {false};
      node.setBottom(handle);
      BorderPane.setAlignment(handle, javafx.geometry.Pos.BOTTOM_RIGHT);
      node.setOnMousePressed(
          e -> {
            selection = w.id();
            resizing[0] = inside((javafx.scene.Node) e.getTarget(), handle);
            dragStart = document;
            dragX = e.getSceneX();
            dragY = e.getSceneY();
            showInspector();
            canvas.requestFocus();
            e.consume();
          });
      node.setOnMouseDragged(
          e -> {
            boolean resize = resizing[0];
            document = dragStart;
            var original = selected();
            move(original, e.getSceneX() - dragX, e.getSceneY() - dragY, resize);
            var r = selected().rect();
            node.relocate(r.x(), r.y());
            node.setPrefSize(r.w(), r.h());
            node.setMinSize(r.w(), r.h());
            node.setMaxSize(r.w(), r.h());
            for (var child : page().widgets()) {
              var visible = canvas.lookup("#sales-widget-" + child.id());
              if (visible != null) visible.relocate(child.rect().x(), child.rect().y());
            }
            canvas.getChildren().removeIf(n -> "snap-guide".equals(n.getId()));
            var vertical = new javafx.scene.shape.Line(r.x(), 0, r.x(), document.canvas().height());
            var horizontal =
                new javafx.scene.shape.Line(0, r.y(), document.canvas().width(), r.y());
            for (var guide : List.of(vertical, horizontal)) {
              guide.setId("snap-guide");
              guide.setMouseTransparent(true);
              guide.setStroke(javafx.scene.paint.Color.DARKORANGE);
              guide.getStrokeDashArray().addAll(5.0, 5.0);
              canvas.getChildren().add(guide);
            }
            e.consume();
          });
      node.setOnMouseReleased(
          e -> {
            if (dragStart != null && !dragStart.equals(document)) {
              undo.push(dragStart);
              redo.clear();
              changed();
            }
            dragStart = null;
            render();
            e.consume();
          });
      canvas.getChildren().add(node);
    }
    showInspector();
  }

  private static boolean inside(javafx.scene.Node target, javafx.scene.Node parent) {
    for (var n = target; n != null; n = n.getParent()) if (n == parent) return true;
    return false;
  }

  private double snap(double n) {
    return Math.round(n / document.canvas().snapSize()) * (double) document.canvas().snapSize();
  }

  private void move(Widget w, double dx, double dy, boolean resize) {
    var r = w.rect();
    double x = r.x(), y = r.y(), width = r.w(), height = r.h();
    if (resize) {
      width = Math.max(8, Math.min(document.canvas().width() - x, snap(width + dx)));
      height = Math.max(8, Math.min(document.canvas().height() - y, snap(height + dy)));
    } else {
      x = Math.max(0, Math.min(document.canvas().width() - width, snap(x + dx)));
      y = Math.max(0, Math.min(document.canvas().height() - height, snap(y + dy)));
    }
    replace(ScreenDocuments.geometry(w, new Rect(x, y, width, height, r.z())));
    if (!resize && w.type() == SalesScreens.Type.GROUP)
      for (var child : List.copyOf(page().widgets()))
        if (w.id().equals(child.groupId())) move(child, x - r.x(), y - r.y(), false);
  }

  private TextField field(String label, String value) {
    inspector.getChildren().add(new Label(label));
    var f = new TextField(value);
    inspector.getChildren().add(f);
    return f;
  }

  private void showInspector() {
    inspector.getChildren().clear();
    var w = selected();
    if (w == null) {
      inspector.getChildren().add(new Label("Select a widget to edit its properties."));
      return;
    }
    inspector.getChildren().add(button("Upload image asset", this::image));
    var label = field("Label", w.label());
    var background = field("Background (#RRGGBB)", w.style().background());
    var foreground = field("Text color", w.style().foreground());
    var font = field("Font size", "" + w.style().fontSize());
    var z = field("Layer", "" + w.rect().z());
    var access = new ComboBox<SalesScreens.Access>();
    access.getItems().addAll(SalesScreens.Access.values());
    access.setValue(w.access());
    inspector.getChildren().addAll(new Label("Visibility (does not grant permission)"), access);
    var binding = new TextField(ScreenCodec.encode(w.binding()));
    var options = new TextField(ScreenCodec.encode(w.options()));
    var leftField = field("Left", String.valueOf(w.rect().x()));
    var topField = field("Top", String.valueOf(w.rect().y()));
    var width = field("Width", String.valueOf(w.rect().w()));
    var height = field("Height", String.valueOf(w.rect().h()));
    var border = field("Border color", w.style().border());
    var borderWidth = field("Border width", String.valueOf(w.style().borderWidth()));
    var fonts = new ComboBox<String>();
    fonts.getItems().addAll("Segoe UI", "Arial", "Verdana", "System");
    fonts.setValue(w.style().fontFamily());
    inspector.getChildren().addAll(new Label("Font"), fonts);
    if (w.type() == SalesScreens.Type.NAV_BUTTON) {
      var destination = new ComboBox<Page>();
      destination.getItems().setAll(document.pages());
      destination.setConverter(
          new javafx.util.StringConverter<>() {
            public String toString(Page p) {
              return p == null ? "" : p.name();
            }

            public Page fromString(String s) {
              return null;
            }
          });
      destination.setValue(
          document.pages().stream()
              .filter(p -> p.id().toString().equals(w.binding().get("targetPageId")))
              .findFirst()
              .orElse(null));
      destination.setOnAction(
          e -> {
            if (destination.getValue() != null)
              binding.setText(
                  ScreenCodec.encode(
                      Map.of("targetPageId", destination.getValue().id().toString())));
          });
      inspector.getChildren().addAll(new Label("Navigate to page"), destination);
    }
    if (w.type() == SalesScreens.Type.FUNCTION_BUTTON) {
      var actions = new ComboBox<SalesScreens.Action>();
      actions.getItems().addAll(SalesScreens.Action.values());
      actions.setConverter(
          new javafx.util.StringConverter<>() {
            public String toString(SalesScreens.Action a) {
              return a == null
                  ? ""
                  : a.name().replace('_', ' ')
                      + (SalesScreens.draftCapabilities().available().contains(a)
                          ? ""
                          : " · unavailable");
            }

            public SalesScreens.Action fromString(String s) {
              return null;
            }
          });
      if (w.binding().containsKey("action"))
        actions.setValue(SalesScreens.Action.valueOf(w.binding().get("action")));
      actions.setOnAction(
          e -> {
            if (actions.getValue() != null)
              binding.setText(ScreenCodec.encode(Map.of("action", actions.getValue().name())));
          });
      inspector.getChildren().addAll(new Label("Action"), actions);
      var workflows = new ComboBox<SalesScreens.Workflow>();
      workflows.getItems().setAll(document.workflows());
      workflows.setConverter(
          new javafx.util.StringConverter<>() {
            public String toString(SalesScreens.Workflow f) {
              return f == null ? "" : f.name();
            }

            public SalesScreens.Workflow fromString(String s) {
              return null;
            }
          });
      workflows.setOnAction(
          e -> {
            if (workflows.getValue() != null)
              binding.setText(
                  ScreenCodec.encode(Map.of("workflowId", workflows.getValue().id().toString())));
          });
      inspector.getChildren().addAll(new Label("Or use a workflow"), workflows);
    }
    if (w.type() == SalesScreens.Type.ORDER_LIST) {
      var columns = new VBox(4);
      for (String key : List.of("quantity", "description", "unitPrice", "lineTotal", "notes")) {
        var check =
            new javafx.scene.control.CheckBox(
                switch (key) {
                  case "quantity" -> "Quantity";
                  case "description" -> "Description";
                  case "unitPrice" -> "Unit price";
                  case "lineTotal" -> "Line total";
                  default -> "Notes";
                });
        check.setUserData(key);
        check.setSelected(
            List.of(
                    w.options()
                        .getOrDefault("columns", "quantity,description,unitPrice,lineTotal")
                        .split(","))
                .contains(key));
        columns.getChildren().add(check);
        check.setOnAction(
            e ->
                options.setText(
                    ScreenCodec.encode(
                        Map.of(
                            "columns",
                            columns.getChildren().stream()
                                .map(n -> (javafx.scene.control.CheckBox) n)
                                .filter(javafx.scene.control.CheckBox::isSelected)
                                .map(n -> n.getUserData().toString())
                                .collect(java.util.stream.Collectors.joining(","))))));
      }
      inspector.getChildren().addAll(new Label("Visible order columns"), columns);
    }
    if (w.type() == SalesScreens.Type.TEXT) {
      var text = field("Text", w.options().getOrDefault("text", w.label()));
      text.textProperty()
          .addListener((o, a, b) -> options.setText(ScreenCodec.encode(Map.of("text", b))));
    }

    var group = new ComboBox<String>();
    group.getItems().add("None");
    var groups = new HashMap<String, UUID>();
    for (var g : page().widgets())
      if (g.type() == SalesScreens.Type.GROUP && !g.id().equals(w.id())) {
        String key = g.label() + " (" + (groups.size() + 1) + ")";
        groups.put(key, g.id());
        group.getItems().add(key);
        if (g.id().equals(w.groupId())) group.setValue(key);
      }
    if (group.getValue() == null) group.setValue("None");
    inspector.getChildren().addAll(new Label("Group"), group);
    if (w.type() == SalesScreens.Type.PRODUCT_BUTTON) {
      var products = new ComboBox<ItemCustomization.Product>();
      products.getItems().setAll(catalog.products().values());
      products.setConverter(
          new javafx.util.StringConverter<>() {
            public String toString(ItemCustomization.Product p) {
              return p == null ? "" : p.name() + " · R " + p.price();
            }

            public ItemCustomization.Product fromString(String s) {
              return null;
            }
          });
      products.setOnAction(
          e -> {
            if (products.getValue() != null)
              binding.setText(
                  ScreenCodec.encode(Map.of("priceId", products.getValue().priceId().toString())));
          });
      inspector.getChildren().addAll(new Label("Product variant"), products);
    }
    if (w.type() == SalesScreens.Type.FUNCTION_BUTTON)
      inspector
          .getChildren()
          .add(
              new Label(
                  "Supported draft actions: "
                      + SalesScreens.draftCapabilities().available()
                      + "\nFinancial, hold/resume and printing remain gated. Draft-only workflows require server approval."));
    inspector
        .getChildren()
        .add(
            button(
                "Apply properties",
                () -> {
                  try {
                    var b =
                        ScreenCodec.JSON.readValue(
                            binding.getText(),
                            new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, String>>() {});
                    var o =
                        ScreenCodec.JSON.readValue(
                            options.getText(),
                            new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, String>>() {});
                    var s = w.style();
                    var style =
                        new SalesScreens.Style(
                            background.getText(),
                            foreground.getText(),
                            Double.parseDouble(font.getText()),
                            border.getText(),
                            Double.parseDouble(borderWidth.getText()),
                            fonts.getValue(),
                            s.assetHash());
                    var r = w.rect();
                    var updated =
                        new Widget(
                            w.id(),
                            w.type(),
                            new Rect(
                                Double.parseDouble(leftField.getText()),
                                Double.parseDouble(topField.getText()),
                                Double.parseDouble(width.getText()),
                                Double.parseDouble(height.getText()),
                                Integer.parseInt(z.getText())),
                            style,
                            label.getText(),
                            b,
                            o,
                            access.getValue(),
                            groups.get(group.getValue()));
                    var old = document;
                    replace(updated);
                    try {
                      validate();
                    } catch (Exception invalid) {
                      document = old;
                      throw invalid;
                    }
                    undo.push(old);
                    redo.clear();
                    changed();
                    render();
                  } catch (Exception invalid) {
                    status.setText(invalid.getMessage());
                  }
                }));
    inspector
        .getChildren()
        .add(
            button(
                "Delete widget",
                () -> {
                  checkpoint();
                  document =
                      ScreenDocuments.page(
                          document,
                          new Page(
                              page().id(),
                              page().name(),
                              page().purpose(),
                              page().widgets().stream()
                                  .filter(x -> !x.id().equals(w.id()))
                                  .map(
                                      x ->
                                          w.id().equals(x.groupId())
                                              ? new Widget(
                                                  x.id(),
                                                  x.type(),
                                                  x.rect(),
                                                  x.style(),
                                                  x.label(),
                                                  x.binding(),
                                                  x.options(),
                                                  x.access(),
                                                  null)
                                              : x)
                                  .toList()));
                  selection = null;
                  changed();
                  render();
                }));
  }

  private void add(SalesScreens.Type type) {
    var binding = new HashMap<String, String>();
    if (type == SalesScreens.Type.PRODUCT_BUTTON) {
      var product =
          catalog.products().values().stream()
              .filter(ItemCustomization.Product::available)
              .findFirst();
      if (product.isEmpty()) {
        status.setText("Add an available catalogue variant first.");
        return;
      }
      binding.put("priceId", product.get().priceId().toString());
    }
    if (type == SalesScreens.Type.NAV_BUTTON) binding.put("targetPageId", pageId.toString());
    if (type == SalesScreens.Type.FUNCTION_BUTTON) binding.put("action", "SAVE_DRAFT");
    checkpoint();
    var widgets = new ArrayList<>(page().widgets());
    var w =
        ScreenDocuments.widget(
            type,
            type.name().replace('_', ' '),
            new Rect(16, 96, 160, 64, widgets.size() + 1),
            binding);
    widgets.add(w);
    document =
        ScreenDocuments.page(
            document, new Page(page().id(), page().name(), page().purpose(), List.copyOf(widgets)));
    selection = w.id();
    changed();
    render();
  }

  private void addPage() {
    var d = new javafx.scene.control.TextInputDialog("New page");
    d.setHeaderText("Page name");
    d.showAndWait()
        .filter(s -> !s.isBlank())
        .ifPresent(
            n -> {
              checkpoint();
              var p = new Page(UUID.randomUUID(), n, SalesScreens.Purpose.SALES, List.of());
              var pages = new ArrayList<>(document.pages());
              pages.add(p);
              document =
                  new Document(
                      1,
                      document.screenId(),
                      document.storeId(),
                      document.canvas(),
                      List.copyOf(pages),
                      document.workflows(),
                      document.templateOrigins());
              pageId = p.id();
              changed();
              render();
            });
  }

  private void renamePage() {
    var d = new javafx.scene.control.TextInputDialog(page().name());
    d.setHeaderText("Rename page");
    d.showAndWait()
        .filter(s -> !s.isBlank())
        .ifPresent(
            n -> {
              checkpoint();
              document =
                  ScreenDocuments.page(
                      document, new Page(pageId, n, page().purpose(), page().widgets()));
              changed();
              render();
            });
  }

  private void deletePage() {
    if (document.pages().size() == 1) {
      status.setText("A screen needs at least one page.");
      return;
    }
    boolean linked =
        document.pages().stream()
            .flatMap(p -> p.widgets().stream())
            .anyMatch(w -> pageId.toString().equals(w.binding().get("targetPageId")));
    if (linked) {
      status.setText("Move navigation buttons targeting this page before deleting it.");
      return;
    }
    checkpoint();
    document =
        new Document(
            1,
            document.screenId(),
            document.storeId(),
            document.canvas(),
            document.pages().stream().filter(p -> !p.id().equals(pageId)).toList(),
            document.workflows(),
            document.templateOrigins().stream().filter(o -> !o.pageId().equals(pageId)).toList());
    pageId = document.pages().getFirst().id();
    changed();
    render();
  }

  private List<String> validate() {
    return ScreenValidation.validate(
        document,
        document.storeId(),
        catalog.products().keySet(),
        ScreenValidation.Limits.standard());
  }

  private void save(boolean afterPublish) {
    if (busy) return;
    try {
      validate();
    } catch (Exception invalid) {
      status.setText(invalid.getMessage());
      return;
    }
    if (uncertain == null)
      uncertain =
          new SalesScreens.Save(UUID.randomUUID(), revision, name.getText(), template, document);
    var submitted = uncertain;
    work(
        () -> gateway.screens("save", "POST", submitted, token),
        result -> {
          revision = result.path("revision").asLong();
          uncertain = null;
          var definition =
              JsonFiles.JSON.valueToTree(
                  Map.of(
                      "screen_id",
                      submitted.document().screenId(),
                      "name",
                      submitted.name(),
                      "template",
                      submitted.template(),
                      "revision",
                      revision));
          definitions
              .getItems()
              .removeIf(
                  n ->
                      n.path("screen_id")
                          .asText()
                          .equals(submitted.document().screenId().toString()));
          definitions.getItems().add(definition);
          dirty =
              !document.equals(submitted.document()) || !name.getText().equals(submitted.name());
          status.setText(
              "Draft saved · revision " + revision + (dirty ? " · newer edits pending" : ""));
          if (dirty) autosave.playFromStart();
          else if (afterPublish) publish(false);
        });
  }

  private void publish(boolean rollback) {
    if (dirty || revision == 0) {
      save(true);
      return;
    }
    try {
      validate();
    } catch (Exception invalid) {
      status.setText(invalid.getMessage());
      return;
    }
    if (!confirm("Publish this screen for all tills at their next safe order boundary?")) return;
    if (uncertainPublication == null)
      uncertainPublication =
          new SalesScreens.Publish(UUID.randomUUID(), document.screenId(), revision, publication);
    var command = uncertainPublication;
    work(
        () -> gateway.screens(rollback ? "rollback" : "publish", "POST", command, token),
        result -> {
          publication = result.path("sequence").asLong();
          uncertainPublication = null;
          status.setText(
              "Published sequence " + publication + " · tills activate at a safe draft boundary");
        });
  }

  private void open() {
    var d = definitions.getValue();
    if (d == null || !confirmClose()) return;
    work(
        () ->
            gateway.screens(
                "revisions/" + d.path("screen_id").asText() + "/" + d.path("revision").asLong(),
                "GET",
                null,
                token),
        response -> {
          try {
            var r = JsonFiles.JSON.treeToValue(response, SalesScreens.Revision.class);
            document = r.document();
            revision = r.revision();
            template = r.template();
            populating = true;
            name.setText(r.name());
            populating = false;
            dirty = false;
            uncertain = null;
            undo.clear();
            redo.clear();
            pageId = document.pages().getFirst().id();
            render();
          } catch (Exception e) {
            status.setText(e.getMessage());
          }
        });
  }

  private void refreshVersions() {
    work(
        () -> gateway.screens("context", "GET", null, token),
        context -> {
          definitions.getItems().clear();
          context.path("definitions").forEach(definitions.getItems()::add);
          work(
              () -> gateway.screens("publication", "GET", null, token),
              r -> {
                publication = r.path("sequence").asLong();
                status.setText("Versions refreshed · current edits retained");
              });
        });
  }

  private void saveCopy() {
    if (busy || uncertain != null || uncertainPublication != null) {
      status.setText("Resolve the pending command before copying this draft.");
      return;
    }
    document = ScreenDocuments.copy(document, document.storeId());
    pageId = document.pages().getFirst().id();
    revision = 0;
    name.setText(name.getText() + " copy");
    dirty = true;
    save(false);
    render();
  }

  private void newScreen() {
    if (!confirmClose()) return;
    document = ScreenDocuments.starter(document.storeId(), catalog);
    revision = 0;
    template = false;
    uncertain = null;
    pageId = document.pages().getFirst().id();
    undo.clear();
    redo.clear();
    dirty = false;
    render();
  }

  private void newPhotoTemplate() {
    if (busy || uncertain != null || uncertainPublication != null) {
      status.setText("Resolve the pending command before creating a photo template.");
      return;
    }
    if (!confirmClose()) return;
    document = ScreenDocuments.starter(document.storeId(), catalog);
    revision = 0;
    uncertain = null;
    pageId = document.pages().getFirst().id();
    undo.clear();
    redo.clear();
    template = true;
    name.setText("Photo-style Sales template");
    autosave.stop();
    dirty = true;
    render();
    status.setText("Independent photo template · Save draft to keep it");
  }

  private void convertPhotoLayout() {
    if (busy || uncertain != null || uncertainPublication != null) {
      status.setText("Resolve the pending command before converting this layout.");
      return;
    }
    autosave.stop();
    try {
      var candidate = PhotoSalesLayout.apply(document);
      ScreenValidation.validate(
          candidate,
          candidate.storeId(),
          catalog.products().keySet(),
          ScreenValidation.Limits.standard());
      work(
          () -> gateway.screens("preview", "POST", candidate, token),
          response -> {
            var runtime = new SalesRuntime(gateway, executor, token, candidate, catalog);
            var dialog = new Dialog<ButtonType>();
            var apply =
                new ButtonType("Apply layout", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            dialog.setTitle("Review photo layout · all pages · no saved changes");
            dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);
            dialog.getDialogPane().setContent(runtime);
            dialog.getDialogPane().setPrefSize(1240, 880);
            dialog.setResizable(true);
            var result = dialog.showAndWait();
            runtime.close();
            if (result.orElse(ButtonType.CANCEL) == apply) {
              checkpoint();
              document = candidate;
              selection = null;
              dirty = true;
              render();
              status.setText(
                  "Photo layout staged · Save draft to persist; Publish activates it for tills");
            } else {
              status.setText("Conversion cancelled · previous layout retained");
            }
          });
    } catch (Exception failure) {
      status.setText(failure.getMessage());
    }
  }

  private void saveTemplate() {
    if (dirty || uncertain != null) {
      status.setText("Save the current draft before creating an independent template.");
      return;
    }
    document = ScreenDocuments.copy(document, document.storeId());
    pageId = document.pages().getFirst().id();
    revision = 0;
    template = true;
    name.setText(name.getText() + " template");
    dirty = true;
    save(false);
    render();
  }

  private void applyTemplate() {
    var d = definitions.getValue();
    if (d == null || !d.path("template").asBoolean()) {
      status.setText("Select a template from the screen list first.");
      return;
    }
    work(
        () ->
            gateway.screens(
                "revisions/" + d.path("screen_id").asText() + "/" + d.path("revision").asLong(),
                "GET",
                null,
                token),
        response -> {
          try {
            var r = JsonFiles.JSON.treeToValue(response, SalesScreens.Revision.class);
            checkpoint();
            document = ScreenDocuments.applyTemplate(document, r);
            changed();
            render();
          } catch (Exception invalid) {
            status.setText(invalid.getMessage());
          }
        });
  }

  private void updateTemplate() {
    var origin =
        document.templateOrigins().stream().filter(o -> o.pageId().equals(pageId)).findFirst();
    if (origin.isEmpty()) {
      status.setText("This page has no template origin.");
      return;
    }
    var d =
        definitions.getItems().stream()
            .filter(n -> n.path("screen_id").asText().equals(origin.get().templateId().toString()))
            .findFirst();
    if (d.isEmpty()) {
      status.setText("Refresh the builder to load the latest template list.");
      return;
    }
    work(
        () ->
            gateway.screens(
                "revisions/" + origin.get().templateId() + "/" + d.get().path("revision").asLong(),
                "GET",
                null,
                token),
        response -> {
          try {
            var r = JsonFiles.JSON.treeToValue(response, SalesScreens.Revision.class);
            var review = ScreenDocuments.updateTemplate(document, origin.get(), r, Set.of());
            var dialog = new Dialog<ButtonType>();
            dialog.setTitle("Review template changes");
            dialog.setHeaderText(
                "Only checked changes apply to this page. Other pages and local widgets stay as they are.");
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            var options = new VBox(12);
            var selected = new java.util.HashSet<String>();
            for (var change : review.conflicts()) {
              String title =
                  document.pages().stream()
                      .flatMap(p -> p.widgets().stream())
                      .filter(w -> w.id().equals(change.widgetId()))
                      .map(Widget::label)
                      .findFirst()
                      .orElseGet(
                          () ->
                              document.workflows().stream()
                                  .filter(f -> f.id().equals(change.widgetId()))
                                  .map(SalesScreens.Workflow::name)
                                  .findFirst()
                                  .orElse("New template element"));
              var check =
                  new javafx.scene.control.CheckBox(
                      title
                          + " · "
                          + change.field()
                          + (change.base().equals(change.local())
                              ? ""
                              : " · local change conflicts"));
              check.setWrapText(true);
              check.setOnAction(
                  e -> {
                    String key = change.widgetId() + "." + change.field();
                    if (check.isSelected()) selected.add(key);
                    else selected.remove(key);
                  });
              var values =
                  new Label("Current: " + change.local() + "\nTemplate: " + change.incoming());
              values.setWrapText(true);
              options.getChildren().addAll(check, values);
            }
            if (review.conflicts().isEmpty())
              options.getChildren().add(new Label("No pending template changes for this page."));
            var scroll = new ScrollPane(options);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportWidth(650);
            scroll.setPrefViewportHeight(450);
            dialog.getDialogPane().setContent(scroll);
            dialog
                .showAndWait()
                .filter(ButtonType.OK::equals)
                .ifPresent(
                    answer -> {
                      try {
                        var proposed =
                            ScreenDocuments.updateTemplate(
                                    document, origin.get(), r, Set.copyOf(selected))
                                .document();
                        ScreenValidation.validate(
                            proposed,
                            document.storeId(),
                            catalog.products().keySet(),
                            ScreenValidation.Limits.standard());
                        checkpoint();
                        document = proposed;
                        changed();
                        render();
                      } catch (Exception invalid) {
                        status.setText("Template update rejected: " + invalid.getMessage());
                      }
                    });
          } catch (Exception invalid) {
            status.setText(invalid.getMessage());
          }
        });
  }

  private void workflows() {
    new WorkflowEditor(document.workflows())
        .showAndWait()
        .ifPresent(
            flows -> {
              try {
                var proposed =
                    new Document(
                        1,
                        document.screenId(),
                        document.storeId(),
                        document.canvas(),
                        document.pages(),
                        flows,
                        document.templateOrigins());
                ScreenValidation.validate(
                    proposed,
                    document.storeId(),
                    catalog.products().keySet(),
                    ScreenValidation.Limits.standard());
                checkpoint();
                document = proposed;
                changed();
              } catch (Exception invalid) {
                status.setText("Workflow rejected: " + invalid.getMessage());
              }
            });
  }

  private void history() {
    work(
        () -> gateway.screens("history/" + document.screenId(), "GET", null, token),
        rows -> {
          var d = new javafx.scene.control.ChoiceDialog<Long>();
          for (var row : rows) d.getItems().add(row.path("revision").asLong());
          d.setHeaderText("Select an immutable revision to roll back to");
          d.showAndWait()
              .ifPresent(
                  rev -> {
                    if (dirty) {
                      status.setText("Save or discard pending changes first.");
                      return;
                    }
                    var command =
                        new SalesScreens.Publish(
                            UUID.randomUUID(), document.screenId(), rev, publication);
                    if (confirm(
                        "Publish historical revision " + rev + " as a new store-wide publication?"))
                      work(
                          () -> gateway.screens("rollback", "POST", command, token),
                          r -> {
                            publication = r.path("sequence").asLong();
                            status.setText("Rollback published as sequence " + publication);
                          });
                  });
        });
  }

  private void preview() {
    try {
      var warnings = validate();
      work(
          () -> gateway.screens("preview", "POST", document, token),
          r -> {
            var runtime = new SalesRuntime(gateway, executor, token, document, catalog);
            var stage = new javafx.stage.Stage();
            stage.setTitle("Sales design preview · no transactions");
            stage.setScene(new javafx.scene.Scene(runtime, 1200, 760));
            stage.setOnHidden(e -> runtime.close());
            stage.show();
            status.setText(warnings.isEmpty() ? "Preview validated" : String.join(" · ", warnings));
          });
    } catch (Exception invalid) {
      status.setText(invalid.getMessage());
    }
  }

  private FileChooser chooser() {
    var f = new FileChooser();
    f.getExtensionFilters().add(new FileChooser.ExtensionFilter("Sales screen JSON", "*.json"));
    return f;
  }

  private void exportDocument() {
    try {
      validate();
      var file = chooser().showSaveDialog(getScene().getWindow());
      if (file == null) return;
      var doc = document;
      work(
          () -> {
            var assets = new ArrayList<SalesScreens.Asset>();
            for (String hash : ScreenAssets.required(doc))
              assets.add(
                  JsonFiles.JSON.treeToValue(
                      gateway.screens("assets/" + hash, "GET", null, token),
                      SalesScreens.Asset.class));
            Files.writeString(
                file.toPath(),
                ScreenCodec.encode(
                    new SalesScreens.Bundle(doc, List.copyOf(assets), name.getText(), template)));
            return "Exported screen and validated assets";
          },
          status::setText);
    } catch (Exception bad) {
      status.setText("Export rejected: " + bad.getMessage());
    }
  }

  private void importDocument() {
    var file = chooser().showOpenDialog(getScene().getWindow());
    if (file == null) return;
    try {
      if (Files.size(file.toPath()) > 12 * 1024 * 1024)
        throw new IllegalArgumentException("Import exceeds size limit");
      var tree = ScreenCodec.JSON.readTree(Files.readAllBytes(file.toPath()));
      var bundle =
          tree.has("document")
              ? ScreenCodec.JSON.treeToValue(tree, SalesScreens.Bundle.class)
              : new SalesScreens.Bundle(
                  ScreenCodec.JSON.treeToValue(tree, Document.class), List.of());
      var source = bundle.document();
      boolean foreign = !document.storeId().equals(source.storeId());
      var binding = new HashMap<UUID, UUID>();
      for (var page : source.pages())
        for (var widget : page.widgets())
          if (widget.type() == SalesScreens.Type.PRODUCT_BUTTON) {
            UUID old = ScreenValidation.uuid(widget.binding().get("priceId"));
            if ((foreign || !catalog.products().containsKey(old)) && !binding.containsKey(old)) {
              var choices = new javafx.scene.control.ChoiceDialog<String>();
              var ids = new HashMap<String, UUID>();
              for (var product : catalog.products().values())
                if (product.available()) {
                  String key = product.name() + " · " + product.priceId();
                  ids.put(key, product.priceId());
                  choices.getItems().add(key);
                }
              choices.setHeaderText(
                  "Rebind imported product: " + widget.label() + " (" + old + ")");
              var selected = choices.showAndWait();
              if (selected.isEmpty()) return;
              binding.put(old, ids.get(selected.get()));
            }
          }
      var rebound =
          new Document(
              1,
              source.screenId(),
              document.storeId(),
              source.canvas(),
              source.pages().stream()
                  .map(
                      page ->
                          new Page(
                              page.id(),
                              page.name(),
                              page.purpose(),
                              page.widgets().stream()
                                  .map(
                                      w -> {
                                        if (w.type() != SalesScreens.Type.PRODUCT_BUTTON) return w;
                                        var b = new HashMap<>(w.binding());
                                        var id = UUID.fromString(b.get("priceId"));
                                        b.put("priceId", binding.getOrDefault(id, id).toString());
                                        return new Widget(
                                            w.id(),
                                            w.type(),
                                            w.rect(),
                                            w.style(),
                                            w.label(),
                                            Map.copyOf(b),
                                            w.options(),
                                            w.access(),
                                            w.groupId());
                                      })
                                  .toList()))
                  .toList(),
              source.workflows(),
              List.of());
      ScreenValidation.validate(
          rebound,
          document.storeId(),
          catalog.products().keySet(),
          ScreenValidation.Limits.standard());
      var required = ScreenAssets.required(rebound);
      var supplied = new HashMap<String, SalesScreens.Asset>();
      long size = 0;
      for (var asset : bundle.assets()) {
        size += asset.content() == null ? 0 : asset.content().length;
        if (size > 4 * 1024 * 1024
            || supplied.put(asset.hash(), asset) != null
            || !required.contains(asset.hash()))
          throw new IllegalArgumentException("Unexpected, duplicate or oversized import assets");
        if (!asset.hash().equals(ScreenAssets.hash(asset.content())))
          throw new IllegalArgumentException("Asset hash mismatch");
      }
      if (!confirmClose()) return;
      work(
          () -> {
            for (String hash : required) {
              var asset = supplied.get(hash);
              if (asset == null)
                asset =
                    JsonFiles.JSON.treeToValue(
                        gateway.screens("assets/" + hash, "GET", null, token),
                        SalesScreens.Asset.class);
              else
                gateway.screens(
                    "assets",
                    "POST",
                    new SalesScreens.Asset(
                        UUID.randomUUID(), asset.hash(), asset.mediaType(), asset.content()),
                    token);
              ScreenAssets.write(gateway.screenCache(), asset);
            }
            return ScreenDocuments.copy(rebound, rebound.storeId());
          },
          copy -> {
            document = copy;
            revision = 0;
            template = bundle.template();
            populating = true;
            name.setText(bundle.name());
            populating = false;
            uncertain = null;
            pageId = document.pages().getFirst().id();
            changed();
            render();
          });
    } catch (Exception invalid) {
      status.setText("Import rejected: " + invalid.getMessage());
    }
  }

  private void image() {
    var w = selected();
    if (w == null) return;
    var chooser = new FileChooser();
    chooser
        .getExtensionFilters()
        .add(new FileChooser.ExtensionFilter("PNG or JPEG", "*.png", "*.jpg", "*.jpeg"));
    var file = chooser.showOpenDialog(getScene().getWindow());
    if (file == null) return;
    try {
      if (Files.size(file.toPath()) > 524288)
        throw new IllegalArgumentException("Image must be 512 KB or smaller");
      var bytes = Files.readAllBytes(file.toPath());
      var hash = ScreenAssets.hash(bytes);
      String media =
          file.getName().toLowerCase(java.util.Locale.ROOT).endsWith("png")
              ? "image/png"
              : "image/jpeg";
      var asset = new SalesScreens.Asset(UUID.randomUUID(), hash, media, bytes);
      work(
          () -> {
            var response = gateway.screens("assets", "POST", asset, token);
            ScreenAssets.write(gateway.screenCache(), asset);
            return response;
          },
          r -> {
            checkpoint();
            var s = w.style();
            replace(
                new Widget(
                    w.id(),
                    w.type(),
                    w.rect(),
                    new SalesScreens.Style(
                        s.background(),
                        s.foreground(),
                        s.fontSize(),
                        s.border(),
                        s.borderWidth(),
                        s.fontFamily(),
                        hash),
                    w.label(),
                    w.binding(),
                    w.options(),
                    w.access(),
                    w.groupId()));
            changed();
            render();
          });
    } catch (Exception bad) {
      status.setText("Image rejected: " + bad.getMessage());
    }
  }

  private void help() {
    new Alert(
            Alert.AlertType.INFORMATION,
            "New screens use the photo-style layout: categories above five product columns, an order area, subtotal and quantity keypad. Apply photo layout to all Sales pages previews a conversion before staging it; Cancel keeps your layout. Save draft keeps the new revision and History can restore an older one. New photo template starts an independent template. Pages contain movable widgets. Product buttons bind to a catalogue variant ID; navigation buttons target page IDs. The order list selects a line with one tap and customises it with two. Groups move their members together. Layers control overlap. Save draft stores an immutable revision; Publish changes the store-wide layout at a safe order boundary. Templates are independent copies. Import is validated and cannot execute code. Unsupported payment, refund, hold/resume and printing actions remain hidden from cashiers. Autosave never publishes. PINs and session tokens are never exported.",
            ButtonType.OK)
        .showAndWait();
  }

  private boolean confirm(String message) {
    return new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL)
            .showAndWait()
            .orElse(ButtonType.CANCEL)
        == ButtonType.OK;
  }

  boolean confirmClose() {
    if (busy) return false;
    if (!dirty && uncertain == null && uncertainPublication == null) return true;
    if (confirm(
        "Leave the builder and discard unsaved edits? Saved revisions remain in history.")) {
      autosave.stop();
      return true;
    }
    return false;
  }

  private <T> void work(java.util.concurrent.Callable<T> action, Consumer<T> done) {
    if (busy) return;
    busy = true;
    setDisable(true);
    executor.execute(
        () -> {
          try {
            var response = action.call();
            Platform.runLater(
                () -> {
                  if (closed) return;
                  busy = false;
                  setDisable(false);
                  done.accept(response);
                });
          } catch (Exception e) {
            Platform.runLater(
                () -> {
                  if (closed) return;
                  busy = false;
                  setDisable(false);
                  int statusCode =
                      e instanceof DevGateway.Failure f
                          ? f.status
                          : e
                                  instanceof
                                  za.co.onlinepos.installation.TrustedStoreClient.ModuleFailure f
                              ? f.status()
                              : 0;
                  if (statusCode == 400 || statusCode == 409) {
                    uncertain = null;
                    uncertainPublication = null;
                  }
                  status.setText(
                      "Not confirmed · "
                          + e.getMessage()
                          + " · Edits retained. Save retries the same pending command.");
                });
          }
        });
  }

  @Override
  public void close() {
    closed = true;
    autosave.stop();
    token = null;
  }
}
