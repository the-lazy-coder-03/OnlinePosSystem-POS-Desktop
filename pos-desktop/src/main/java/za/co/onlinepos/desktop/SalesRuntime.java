package za.co.onlinepos.desktop;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.LocalCommands;
import za.co.onlinepos.contracts.screen.SalesScreens;
import za.co.onlinepos.contracts.screen.SalesScreens.Action;
import za.co.onlinepos.contracts.screen.SalesScreens.Document;
import za.co.onlinepos.contracts.screen.SalesScreens.Page;
import za.co.onlinepos.contracts.screen.SalesScreens.Widget;
import za.co.onlinepos.contracts.screen.ScreenDocuments;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.ScreenAssets;
import za.co.onlinepos.installation.ScreenCache;

/** Published declarative layout; authoritative quotation precedes every cart mutation. */
final class SalesRuntime extends BorderPane implements AutoCloseable {
  record Line(
      UUID id,
      ItemCustomization.QuoteRequest request,
      ItemCustomization.Quote quote,
      String notes) {
    Line(UUID id, ItemCustomization.QuoteRequest request, ItemCustomization.Quote quote) {
      this(id, request, quote, "");
    }
  }

  private final ModuleGateway gateway;
  private final ExecutorService executor;
  private final String token;
  private final Label status = new Label("Loading sales screen…");
  private final Label scaleNotice = new Label();
  private final javafx.collections.ObservableList<Line> lines = FXCollections.observableArrayList();
  private final Timeline polling = new Timeline();
  private final List<TableView<Line>> orderLists = new ArrayList<>();
  private final List<Button> customerButtons = new ArrayList<>();
  private String customerName;
  private final List<Label> totals = new ArrayList<>();
  private ItemCustomization.Catalog catalog;
  private Document document;
  private ScreenCache cache;
  private UUID pageId, selected, customer, saveCommand, orderId;
  private final Map<UUID, String> pendingNotes = new java.util.HashMap<>();
  private int pending;
  private long draftRevision;
  private boolean existingDraft;
  private volatile boolean offline;

  record Boot(ItemCustomization.Catalog catalog, UUID storeId, boolean offline) {}

  private boolean closed, saving, preview;
  private final javafx.beans.property.BooleanProperty submitting =
      new javafx.beans.property.SimpleBooleanProperty();
  private Dialog<?> dialog;
  private ToppingPage toppingPage;

  private static String money(BigDecimal amount) {
    return amount.setScale(2).toPlainString();
  }

  private BigDecimal total() {
    return lines.stream().map(l -> l.quote().total()).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  SalesRuntime(ModuleGateway gateway, ExecutorService executor, String token) {
    this.gateway = gateway;
    this.executor = executor;
    this.token = token;
    status.setWrapText(true);
    status.setPadding(new Insets(8));
    setBottom(new VBox(status, scaleNotice));
    task(
        () -> {
          var file = gateway.screenCache().resolveSibling("sales-catalogue-cache.json");
          try {
            var c =
                JsonFiles.JSON.treeToValue(
                    gateway.catalogue("items", "GET", null, token),
                    ItemCustomization.Catalog.class);
            var publication = gateway.screens("publication", "GET", null, token);
            var boot = new Boot(c, UUID.fromString(publication.path("storeId").asText()), false);
            JsonFiles.write(file, boot);
            return boot;
          } catch (Exception unavailable) {
            if (!java.nio.file.Files.exists(file)
                || java.nio.file.Files.size(file) > 16 * 1024 * 1024) throw unavailable;
            var saved =
                JsonFiles.JSON.readValue(java.nio.file.Files.readAllBytes(file), Boot.class);
            return new Boot(saved.catalog(), saved.storeId(), true);
          }
        },
        loaded -> {
          catalog = loaded.catalog();
          offline = loaded.offline();
          UUID store = loaded.storeId();
          cache = new ScreenCache(gateway.screenCache(), store);
          try {
            cache.activate(true, false);
          } catch (java.io.IOException unavailable) {
            status.setText("Previous layout retained; unable to activate downloaded update.");
          }
          document =
              cache.active() == null
                  ? ScreenDocuments.starter(store, catalog)
                  : cache.active().document();
          pageId = document.pages().getFirst().id();
          render();
          if (offline)
            status.setText(
                "Offline · last-known-good layout · quotes and saves unavailable until reconnected");
          poll();
          polling.getKeyFrames().add(new KeyFrame(Duration.seconds(10), e -> poll()));
          polling.setCycleCount(Timeline.INDEFINITE);
          polling.play();
        });
  }

  /** In-memory design preview cannot submit orders or publish configuration. */
  SalesRuntime(
      ModuleGateway gateway,
      ExecutorService executor,
      String token,
      Document document,
      ItemCustomization.Catalog catalog) {
    this.gateway = gateway;
    this.executor = executor;
    this.token = token;
    this.document = document;
    this.catalog = catalog;
    preview = true;
    pageId = document.pages().getFirst().id();
    setBottom(new VBox(status, scaleNotice));
    render();
    status.setText("Design preview · quotes only · drafts cannot be saved here");
  }

  private void poll() {
    if (closed || cache == null) return;
    executor.execute(
        () -> {
          try {
            if (offline) {
              var refreshed =
                  JsonFiles.JSON.treeToValue(
                      gateway.catalogue("items", "GET", null, token),
                      ItemCustomization.Catalog.class);
              Platform.runLater(
                  () -> {
                    if (!closed) {
                      catalog = refreshed;
                      offline = false;
                      render();
                    }
                  });
            }
            var events = gateway.screens("events?after=" + cache.received(), "GET", null, token);
            for (var event : events) {
              var publication = JsonFiles.JSON.treeToValue(event, SalesScreens.Publication.class);
              for (String hash : ScreenAssets.required(publication.document())) {
                try {
                  ScreenAssets.read(gateway.screenCache(), hash);
                } catch (Exception missing) {
                  ScreenAssets.write(
                      gateway.screenCache(),
                      JsonFiles.JSON.treeToValue(
                          gateway.screens("assets/" + hash, "GET", null, token),
                          SalesScreens.Asset.class));
                }
              }
              cache.receive(publication);
            }
            Platform.runLater(
                () -> {
                  if (closed) return;
                  try {
                    if (cache.activate(
                        lines.isEmpty(),
                        pending > 0 || dialog != null || toppingPage != null || saving)) {
                      document = cache.active().document();
                      pageId = document.pages().getFirst().id();
                      render();
                    }
                    if (cache.pending())
                      status.setText(
                          "Updated layout ready · activates after this draft is cleared or saved");
                  } catch (Exception e) {
                    status.setText("Layout retained; unable to activate update.");
                  }
                });
          } catch (Exception unavailable) {
            /* Keep last-known-good configuration; never replace on failed delivery. */
          }
        });
  }

  private void render() {
    if (toppingPage != null) return;
    if (!getStyleClass().contains("sales-photo")) getStyleClass().add("sales-photo");
    String css = SalesRuntime.class.getResource("/sales-photo.css").toExternalForm();
    if (!getStylesheets().contains(css)) getStylesheets().add(css);
    customerButtons.clear();
    status.setStyle("-fx-text-fill:#d6e8ff;");
    scaleNotice.setStyle("-fx-text-fill:#f0d634;");
    orderLists.clear();
    totals.clear();
    Page page =
        document.pages().stream()
            .filter(p -> p.id().equals(pageId))
            .findFirst()
            .orElse(document.pages().getFirst());
    var canvas = new Pane();
    canvas.setStyle("-fx-background-color:#181d39;");
    canvas.setMinSize(document.canvas().width(), document.canvas().height());
    canvas.setPrefSize(document.canvas().width(), document.canvas().height());
    canvas.setMaxSize(document.canvas().width(), document.canvas().height());
    for (var w :
        page.widgets().stream().sorted(Comparator.comparingInt(x -> x.rect().z())).toList()) {
      // Elevated controls never appear in a cashier layout. Layout is never a permission grant.
      if (w.access() != SalesScreens.Access.CASHIER) continue;
      Node node = widget(w);
      if (node == null) continue;
      if (node instanceof javafx.scene.control.Labeled label && w.style().assetHash() != null)
        try {
          var icon =
              new javafx.scene.image.ImageView(
                  new javafx.scene.image.Image(
                      new java.io.ByteArrayInputStream(
                          ScreenAssets.read(gateway.screenCache(), w.style().assetHash()))));
          icon.setFitWidth(Math.min(64, w.rect().w() / 2));
          icon.setFitHeight(Math.min(48, w.rect().h() / 2));
          icon.setPreserveRatio(true);
          label.setGraphic(icon);
          label.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
        } catch (Exception missing) {
          status.setText("A layout image is unavailable.");
        }
      place(node, w);
      canvas.getChildren().add(node);
    }
    var scaled = new javafx.scene.Group(canvas);
    var holder = new StackPane(scaled);
    holder.setMinSize(320, 240);
    Runnable fit =
        () -> {
          double scale =
              Math.min(
                  holder.getWidth() / document.canvas().width(),
                  holder.getHeight() / document.canvas().height());
          if (scale > 0) {
            canvas.setScaleX(scale);
            canvas.setScaleY(scale);
            boolean small =
                page.widgets().stream()
                    .filter(
                        w ->
                            w.type() == SalesScreens.Type.PRODUCT_BUTTON
                                || w.type() == SalesScreens.Type.NAV_BUTTON
                                || w.type() == SalesScreens.Type.FUNCTION_BUTTON)
                    .anyMatch(w -> w.rect().w() * scale < 44 || w.rect().h() * scale < 44);
            scaleNotice.setText(
                small
                    ? "Some controls are smaller than the recommended touch size. Enlarge the window or review this layout."
                    : "");
            scaleNotice.setWrapText(true);
            scaleNotice.setVisible(small);
            scaleNotice.setManaged(small);
          }
        };
    holder.widthProperty().addListener((o, a, b) -> fit.run());
    holder.heightProperty().addListener((o, a, b) -> fit.run());
    setCenter(holder);
    refresh();
    if (!preview)
      status.setText(
          (cache != null && cache.active() != null
                  ? "Published screen " + cache.active().sequence()
                  : "Starter screen · no store layout published")
              + " · Draft ordering · Checkout unavailable");
  }

  static void place(Node node, Widget w) {
    var r = w.rect();
    node.relocate(r.x(), r.y());
    if (node instanceof javafx.scene.layout.Region region) {
      region.setPrefSize(r.w(), r.h());
      region.setMinSize(r.w(), r.h());
      region.setMaxSize(r.w(), r.h());
    }
    var s = w.style();
    node.setStyle(
        "-fx-background-color:"
            + s.background()
            + ";-fx-text-fill:"
            + s.foreground()
            + ";-fx-font-size:"
            + s.fontSize()
            + "px;-fx-font-family:'"
            + s.fontFamily()
            + "';-fx-border-color:"
            + s.border()
            + ";-fx-border-width:"
            + s.borderWidth()
            + ";");
    node.getStyleClass().add("sales-" + w.type().name().toLowerCase(java.util.Locale.ROOT));
    if (node instanceof javafx.scene.control.Labeled label) {
      label.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
      label.setAlignment(
          w.type() == SalesScreens.Type.TOTALS
              ? javafx.geometry.Pos.CENTER_LEFT
              : javafx.geometry.Pos.CENTER);
    }
    node.setStyle(
        node.getStyle()
            + "-fx-control-inner-background:"
            + s.background()
            + ";-fx-alignment:"
            + (w.type() == SalesScreens.Type.TOTALS ? "center-left" : "center")
            + ";");
    node.setId("sales-widget-" + w.id());
    node.setAccessibleText(w.label());
  }

  private Node widget(Widget w) {
    switch (w.type()) {
      case NAV_BUTTON:
        {
          var b = new Button(w.label());
          b.setWrapText(true);
          if (pageId.toString().equals(w.binding().get("targetPageId")))
            b.getStyleClass().add("selected-category");
          b.setOnAction(
              e -> {
                pageId = UUID.fromString(w.binding().get("targetPageId"));
                render();
              });
          return b;
        }
      case PRODUCT_BUTTON:
        {
          var product = catalog.products().get(UUID.fromString(w.binding().get("priceId")));
          if (product == null || !product.available()) return null;
          var b = new Button(w.label());
          b.setTooltip(
              new javafx.scene.control.Tooltip(w.label() + " · R " + money(product.price())));
          b.setWrapText(true);
          b.setOnAction(e -> add(product));
          return b;
        }
      case FUNCTION_BUTTON:
        {
          if (w.binding().containsKey("workflowId")) {
            var id = UUID.fromString(w.binding().get("workflowId"));
            var flow =
                document.workflows().stream()
                    .filter(f -> f.id().equals(id))
                    .findFirst()
                    .orElseThrow();
            if (flow.steps().stream()
                .anyMatch(
                    n ->
                        n.type() == SalesScreens.NodeType.ACTION
                            && !SalesScreens.draftCapabilities().available().contains(n.action())))
              return null;
            var button = new Button(w.label());
            button.setOnAction(e -> workflow(flow));
            return button;
          }
          var action = Action.valueOf(w.binding().get("action"));
          if (!SalesScreens.draftCapabilities().available().contains(action)) return null;
          var b = new Button(w.label());
          b.setWrapText(true);
          if (action == Action.SAVE_DRAFT)
            b.disableProperty()
                .bind(submitting.or(new javafx.beans.property.SimpleBooleanProperty(preview)));
          b.setOnAction(e -> dispatch(action));
          return b;
        }
      case ORDER_LIST:
        return orderList(w);
      case TOTALS:
        {
          var label = new Label();
          totals.add(label);
          return label;
        }
      case IMAGE:
        {
          if (w.style().assetHash() == null) return null;
          try {
            var view =
                new javafx.scene.image.ImageView(
                    new javafx.scene.image.Image(
                        new java.io.ByteArrayInputStream(
                            ScreenAssets.read(gateway.screenCache(), w.style().assetHash()))));
            view.setFitWidth(w.rect().w());
            view.setFitHeight(w.rect().h());
            view.setPreserveRatio(true);
            return view;
          } catch (Exception missing) {
            return null;
          }
        }
      case TEXT:
        return new Label(w.options().getOrDefault("text", w.label()));
      case CUSTOMER:
        {
          var button = new Button("Choose customer");
          customerButtons.add(button);
          button.setOnAction(e -> chooseCustomer());
          return button;
        }
      case KEYPAD:
        return keypad();
      case GROUP:
        return new Pane();
      default:
        return null; // Payment/operational widgets are unavailable until genuine backends exist.
    }
  }

  private TableView<Line> orderList(Widget w) {
    var table = new TableView<Line>(lines);
    table.setPlaceholder(new Label("Tap a product to begin"));
    table.setFixedCellSize(48);
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    for (String field :
        w.options()
            .getOrDefault("columns", "quantity,description,unitPrice,lineTotal")
            .split(",")) {
      var col =
          new TableColumn<Line, String>(
              switch (field) {
                case "quantity" -> "Qty";
                case "description" -> "Item";
                case "unitPrice" -> "Each";
                case "lineTotal" -> "Total";
                default -> "Options";
              });
      col.setCellValueFactory(
          cell -> {
            var l = cell.getValue();
            return new ReadOnlyStringWrapper(
                switch (field) {
                  case "quantity" -> "" + l.quote().quantity();
                  case "description" -> l.quote().name();
                  case "unitPrice" -> "R " + money(l.quote().unitPrice());
                  case "lineTotal" -> "R " + money(l.quote().total());
                  default -> l.notes();
                });
          });
      col.setPrefWidth(field.equals("description") ? 210 : 64);
      table.getColumns().add(col);
    }
    table
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (b != null) selected = b.id();
            });
    table.setRowFactory(
        t -> {
          var row = new javafx.scene.control.TableRow<Line>();
          row.setOnMouseClicked(
              e -> {
                if (!row.isEmpty()) {
                  selected = row.getItem().id();
                  if (e.getClickCount() == 2) toppings(row.getItem());
                }
              });
          return row;
        });
    table.setOnKeyPressed(
        e -> {
          if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
            var l = selected();
            if (l != null) toppings(l);
            e.consume();
          }
        });
    orderLists.add(table);
    return table;
  }

  private Node keypad() {
    return keypadView(
        value -> quantity(value),
        () -> status.setText("Select a line and enter a quantity from 1 to 99."));
  }

  static Node keypadView(java.util.function.IntConsumer apply, Runnable invalid) {
    var grid = new GridPane();
    grid.getStyleClass().add("quantity-keypad");
    grid.setHgap(6);
    grid.setVgap(6);
    grid.setPadding(new Insets(4));
    for (int i = 0; i < 3; i++) {
      var col = new javafx.scene.layout.ColumnConstraints();
      col.setPercentWidth(100.0 / 3);
      grid.getColumnConstraints().add(col);
    }
    for (int i = 0; i < 6; i++) {
      var row = new javafx.scene.layout.RowConstraints();
      row.setPercentHeight(100.0 / 6);
      grid.getRowConstraints().add(row);
    }
    var input = new TextField();
    input.setAccessibleText("Selected line quantity");
    input.setPromptText("Selected line quantity");
    input.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    input.setTextFormatter(
        new javafx.scene.control.TextFormatter<String>(
            c -> c.getControlNewText().matches("[0-9]{0,2}") ? c : null));
    grid.add(input, 0, 0, 3, 1);
    Runnable submit =
        () -> {
          try {
            int value = Integer.parseInt(input.getText());
            if (value < 1 || value > 99) throw new IllegalArgumentException();
            if (apply != null) apply.accept(value);
          } catch (Exception bad) {
            if (invalid != null) invalid.run();
          }
        };
    input.setOnAction(e -> submit.run());
    String[] keys = {"7", "8", "9", "4", "5", "6", "1", "2", "3", "0", "00", "Clr"};
    for (int i = 0; i < keys.length; i++) {
      String key = keys[i];
      var button = new Button(key);
      button.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
      button.setMinSize(0, 0);
      button.setOnAction(
          e -> {
            if (key.equals("Clr")) input.clear();
            else input.appendText(key);
          });
      grid.add(button, i % 3, 1 + i / 3);
    }
    var enter = new Button("Apply quantity");
    enter.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    enter.setMinSize(0, 0);
    enter.setOnAction(e -> submit.run());
    grid.add(enter, 0, 5, 3, 1);
    return grid;
  }

  private Line selected() {
    return lines.stream().filter(l -> l.id().equals(selected)).findFirst().orElse(null);
  }

  private void add(ItemCustomization.Product product) {
    if (offline) {
      status.setText("Reconnect to the store for an authoritative quote.");
      return;
    }
    if (saving || saveCommand != null || dialog != null || toppingPage != null) return;
    var effective = ItemCustomization.effective(catalog, product);
    if (effective.combo() != null || effective.groups().stream().anyMatch(g -> g.min() > 0))
      selection(product, null);
    else
      quote(
          UUID.randomUUID(),
          new ItemCustomization.QuoteRequest(
              product.priceId(),
              product.priceRevision(),
              1,
              ItemCustomization.Selection.empty(catalog.revision())),
          null);
  }

  private void toppings(Line line) {
    if (saving || saveCommand != null || pending > 0 || dialog != null || toppingPage != null)
      return;
    var draft = new ToppingDraft(catalog, line.request(), line.quote().selection());
    if (draft.targets().isEmpty()) return;
    toppingPage =
        new ToppingPage(
            line.quote().name(),
            draft,
            (request, success, failure) ->
                task(
                    () ->
                        JsonFiles.JSON.treeToValue(
                            gateway.catalogue("items/quote", "POST", request, token),
                            ItemCustomization.Quote.class),
                    success,
                    failure),
            (request, quote) -> {
              int at = -1;
              for (int i = 0; i < lines.size(); i++)
                if (lines.get(i).id().equals(line.id())) at = i;
              if (at >= 0) {
                lines.set(at, new Line(line.id(), request, quote, line.notes()));
                selected = line.id();
                saveCommand = null;
              }
              closeToppings();
            },
            this::closeToppings);
    setCenter(toppingPage);
    applyCss();
    layout();
  }

  private void closeToppings() {
    if (toppingPage != null) toppingPage.close();
    toppingPage = null;
    render();
  }

  private void customise(Line line) {
    if (saving || saveCommand != null || pending > 0 || dialog != null) return;
    selection(catalog.products().get(line.request().priceId()), line);
  }

  private void selection(ItemCustomization.Product product, Line original) {
    if (dialog != null) return;
    var form =
        new SalesSelection(catalog)
        .new SelectionForm(ItemCustomization.effective(catalog, product));
    if (original != null && original.request().priceId().equals(product.priceId()))
      form.restore(original.request().selection());
    var popup = new Dialog<ButtonType>();
    dialog = popup;
    popup.setTitle(product.name() + " · choices");
    if (getScene() != null) popup.initOwner(getScene().getWindow());
    var confirm =
        new ButtonType(
            original == null ? "Add to draft" : "Update this line",
            javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
    popup.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
    var message = new Label();
    message.setWrapText(true);
    var quantity =
        new Spinner<Integer>(1, 99, original == null ? 1 : original.request().quantity());
    quantity.setId("line-quantity");
    var notes = new javafx.scene.control.TextArea(original == null ? "" : original.notes());
    notes.setId("line-notes");
    notes.setPromptText("Optional notes for this line");
    notes.setPrefRowCount(2);
    notes.setTextFormatter(
        new javafx.scene.control.TextFormatter<String>(
            c -> c.getControlNewText().length() <= 500 ? c : null));
    var variant = new javafx.scene.control.ComboBox<ItemCustomization.Product>();
    String family = catalog.variantGroups().get(product.priceId());
    if (family != null)
      variant
          .getItems()
          .setAll(
              catalog.products().values().stream()
                  .filter(
                      p -> p.available() && family.equals(catalog.variantGroups().get(p.priceId())))
                  .toList());
    variant.setConverter(
        new javafx.util.StringConverter<>() {
          public String toString(ItemCustomization.Product p) {
            return p == null ? "" : p.name() + " · R " + p.price();
          }

          public ItemCustomization.Product fromString(String s) {
            return null;
          }
        });
    variant.setValue(product);
    variant.setOnAction(
        e -> {
          if (variant.getValue() != null
              && !variant.getValue().priceId().equals(product.priceId())) {
            var target = variant.getValue();
            popup.close();
            selection(target, original);
          }
        });
    var details = new VBox(8, new Label("Quantity"), quantity, new Label("Notes"), notes);
    if (variant.getItems().size() > 1) details.getChildren().addAll(new Label("Variant"), variant);
    var content = new VBox(10);
    final int[] stage = {0};
    var steps = new Label();
    var back = new Button("Back");
    var next = new Button("Next");
    back.setId("combo-back");
    next.setId("combo-next");
    Runnable show =
        () -> {
          content.getChildren().clear();
          if (form.stages.isEmpty()) {
            content.getChildren().addAll(form.node, details, message);
            return;
          }
          steps.setText("Step " + (stage[0] + 1) + " of " + form.stages.size());
          back.setDisable(stage[0] == 0);
          next.setVisible(stage[0] < form.stages.size() - 1);
          next.setManaged(next.isVisible());
          popup
              .getDialogPane()
              .lookupButton(confirm)
              .setDisable(stage[0] != form.stages.size() - 1);
          content
              .getChildren()
              .addAll(steps, form.stages.get(stage[0]), new HBox(10, back, next), details, message);
        };
    back.setOnAction(
        e -> {
          stage[0]--;
          message.setText("");
          show.run();
        });
    next.setOnAction(
        e -> {
          try {
            form.stageChecks.get(stage[0]).run();
            stage[0]++;
            message.setText("");
            show.run();
          } catch (Exception invalid) {
            message.setText(invalid.getMessage());
          }
        });
    show.run();
    var scroll = new ScrollPane(content);
    scroll.setFitToWidth(true);
    scroll.setPrefViewportWidth(600);
    scroll.setPrefViewportHeight(500);
    popup.getDialogPane().setContent(scroll);
    popup
        .getDialogPane()
        .lookupButton(confirm)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            e -> {
              e.consume();
              try {
                if (!form.stages.isEmpty()) form.stageChecks.get(stage[0]).run();
                var request =
                    new ItemCustomization.QuoteRequest(
                        product.priceId(),
                        product.priceRevision(),
                        quantity.getValue(),
                        form.selection());
                ItemCustomization.quote(catalog, request);
                popup.getDialogPane().lookupButton(confirm).setDisable(true);
                UUID lineId = original == null ? UUID.randomUUID() : original.id();
                pendingNotes.put(lineId, notes.getText());
                quote(
                    lineId,
                    request,
                    () -> {
                      popup.setResult(confirm);
                      popup.close();
                    },
                    popup::isShowing);
              } catch (Exception invalid) {
                message.setText(invalid.getMessage());
              }
            });
    popup.setOnHidden(e -> dialog = null);
    popup.show();
  }

  private void quote(UUID id, ItemCustomization.QuoteRequest request, Runnable done) {
    quote(id, request, done, () -> true);
  }

  private void quote(
      UUID id,
      ItemCustomization.QuoteRequest request,
      Runnable done,
      java.util.function.BooleanSupplier accepted) {
    pending++;
    task(
        () ->
            JsonFiles.JSON.treeToValue(
                gateway.catalogue("items/quote", "POST", request, token),
                ItemCustomization.Quote.class),
        quote -> {
          pending--;
          if (!accepted.getAsBoolean()) {
            pendingNotes.remove(id);
            status.setText("Selection cancelled · no draft line added");
            return;
          }
          int at = -1;
          for (int i = 0; i < lines.size(); i++) if (lines.get(i).id().equals(id)) at = i;
          String notes = pendingNotes.remove(id);
          if (notes == null) notes = at < 0 ? "" : lines.get(at).notes();
          var line = new Line(id, request, quote, notes);
          if (at < 0) lines.add(line);
          else lines.set(at, line);
          selected = id;
          saveCommand = null;
          refresh();
          if (done != null) done.run();
        },
        failure -> {
          pending--;
          pendingNotes.remove(id);
          status.setText(failure);
          if (dialog != null)
            for (var type : dialog.getDialogPane().getButtonTypes())
              dialog.getDialogPane().lookupButton(type).setDisable(false);
        });
  }

  private void quantity(int quantity) {
    var line = selected();
    if (line == null || quantity < 1 || quantity > 99 || saving || pending > 0) return;
    var r = line.request();
    quote(
        line.id(),
        new ItemCustomization.QuoteRequest(
            r.priceId(), r.expectedPriceRevision(), quantity, r.selection()),
        null);
  }

  private <T> java.util.Optional<T> interact(Dialog<T> modal) {
    var previous = dialog;
    dialog = modal;
    try {
      return modal.showAndWait();
    } finally {
      dialog = previous;
    }
  }

  private boolean confirm(String message) {
    var alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
    if (getScene() != null) alert.initOwner(getScene().getWindow());
    return interact(alert).orElse(ButtonType.CANCEL) == ButtonType.OK;
  }

  private boolean dispatch(Action action) {
    int before = lines.size();
    if (toppingPage != null
        || dialog != null
        || saving
        || pending > 0
        || saveCommand != null && action != Action.SAVE_DRAFT) return false;
    switch (action) {
      case EDIT_LINE -> {
        if (selected() != null) customise(selected());
      }
      case SET_QUANTITY -> {
        var l = selected();
        if (l != null) {
          var d = new javafx.scene.control.TextInputDialog("" + l.request().quantity());
          d.setHeaderText("Quantity for " + l.quote().name());
          interact(d)
              .ifPresent(
                  s -> {
                    try {
                      quantity(Integer.parseInt(s));
                    } catch (Exception bad) {
                      status.setText("Enter a quantity from 1 to 99.");
                    }
                  });
        }
      }
      case REMOVE_LINE -> {
        var l = selected();
        if (l != null && confirm("Remove this unsaved draft line?")) {
          lines.remove(l);
          selected = null;
          saveCommand = null;
          refresh();
        }
      }
      case CLEAR_DRAFT, NEW_DRAFT -> {
        if (lines.isEmpty() || confirm("Discard the unsaved draft?")) {
          lines.clear();
          customer = null;
          selected = null;
          saveCommand = null;
          existingDraft = false;
          orderId = null;
          refresh();
          poll();
        }
      }
      case OPEN_DRAFT -> openDraft();
      case SAVE_DRAFT -> save();
      case SELECT_CUSTOMER -> chooseCustomer();
      default -> status.setText("This operation has no authoritative backend.");
    }
    return !(action == Action.REMOVE_LINE && lines.size() == before
        || (action == Action.CLEAR_DRAFT || action == Action.NEW_DRAFT)
            && before > 0
            && lines.size() == before
        || action == Action.SET_QUANTITY && pending == 0);
  }

  private void chooseCustomer() {
    if (saving || pending > 0) return;
    var search = new javafx.scene.control.TextInputDialog();
    search.setHeaderText("Find customer by name or phone");
    var query = interact(search);
    if (query.isEmpty()) return;
    pending++;
    task(
        () ->
            gateway.customers(
                "?search="
                    + java.net.URLEncoder.encode(
                        query.get(), java.nio.charset.StandardCharsets.UTF_8),
                "GET",
                null,
                token),
        data -> {
          pending--;
          var choices = new javafx.scene.control.ChoiceDialog<String>();
          var ids = new java.util.HashMap<String, UUID>();
          ids.put("Walk-in", null);
          choices.getItems().add("Walk-in");
          var rows = data.isArray() ? data : data.path("customers");
          for (var row : rows) {
            if (row.path("archived").asBoolean() || row.path("banned").asBoolean()) continue;
            String name = row.path("name").asText() + " · " + row.path("id").asText();
            ids.put(name, UUID.fromString(row.path("id").asText()));
            choices.getItems().add(name);
          }
          choices.setHeaderText("Customer for this draft");
          interact(choices)
              .ifPresent(
                  name -> {
                    customer = ids.get(name);
                    customerName = name;
                    refresh();
                    saveCommand = null;
                    status.setText("Customer: " + name);
                  });
        },
        failure -> {
          pending--;
          status.setText(failure);
        });
  }

  private void workflow(SalesScreens.Workflow flow) {
    if (preview) {
      status.setText("Workflow execution needs a published server-approved revision.");
      return;
    }
    if (saving || pending > 0 || dialog != null || cache == null || cache.active() == null) return;
    var active = cache.active();
    pending++;
    task(
        () ->
            JsonFiles.JSON.treeToValue(
                gateway.screens(
                    "workflows/approve",
                    "POST",
                    new SalesScreens.WorkflowRequest(
                        active.screenId(), active.revision(), flow.id()),
                    token),
                SalesScreens.Workflow.class),
        approved -> {
          pending--;
          var steps = new java.util.HashMap<UUID, SalesScreens.Step>();
          approved.steps().forEach(n -> steps.put(n.id(), n));
          UUID next = approved.entry();
          int count = 0;
          while (next != null && count++ < 256) {
            var step = steps.get(next);
            switch (step.type()) {
              case END -> {
                return;
              }
              case PROMPT -> {
                if (!confirm(step.prompt())) return;
                next = step.next();
              }
              case DECISION -> {
                boolean condition =
                    switch (step.condition()) {
                      case "HAS_SELECTION" -> selected() != null;
                      case "CART_EMPTY" -> lines.isEmpty();
                      case "CUSTOMER_SELECTED" -> customer != null;
                      default -> false;
                    };
                next = condition ? step.next() : step.otherwise();
              }
              case ACTION -> {
                if (!dispatch(step.action())) return;
                next = step.next();
              }
            }
          }
        },
        failure -> {
          pending--;
          status.setText(failure);
        });
  }

  private void openDraft() {
    if (preview) {
      status.setText("Preview cannot open store drafts.");
      return;
    }
    if (!lines.isEmpty()) {
      status.setText("Save or clear the current draft first.");
      return;
    }
    pending++;
    task(
        () -> gateway.catalogue("items/drafts", "GET", null, token),
        rows -> {
          pending--;
          var choices = new javafx.scene.control.ChoiceDialog<String>();
          var ids = new java.util.HashMap<String, UUID>();
          for (var row : rows) {
            String caption =
                row.path("reference").asText()
                    + " · R "
                    + row.path("total").asText()
                    + " · revision "
                    + row.path("revision").asLong();
            ids.put(caption, UUID.fromString(row.path("orderId").asText()));
            choices.getItems().add(caption);
          }
          if (ids.isEmpty()) {
            status.setText("No saved drafts to open.");
            return;
          }
          choices.setHeaderText("Choose a saved draft");
          interact(choices).ifPresent(selected -> loadDraft(ids.get(selected)));
        },
        message -> {
          pending--;
          status.setText(message);
        });
  }

  private void loadDraft(UUID id) {
    pending++;
    task(
        () -> gateway.catalogue("items/drafts/" + id, "GET", null, token),
        draft -> {
          try {
            if (!"DRAFT".equals(draft.path("state").asText()))
              throw new IllegalArgumentException("This record is not an editable draft");
            var loaded = new ArrayList<Line>();
            int index = 0;
            for (var row : draft.path("lines")) {
              var quoteNode = row.has("customization") ? row.path("customization") : row;
              var quote = JsonFiles.JSON.treeToValue(quoteNode, ItemCustomization.Quote.class);
              UUID lineId =
                  row.has("lineId")
                      ? UUID.fromString(row.path("lineId").asText())
                      : UUID.fromString(draft.path("lineIds").get(index).asText());
              loaded.add(
                  new Line(
                      lineId,
                      new ItemCustomization.QuoteRequest(
                          quote.priceId(),
                          quote.priceRevision(),
                          quote.quantity(),
                          quote.selection()),
                      quote,
                      row.has("notes")
                          ? row.path("notes").asText()
                          : draft.path("lineNotes").path(index).asText("")));
              index++;
            }
            lines.setAll(loaded);
            orderId = id;
            existingDraft = true;
            draftRevision = draft.path("revision").asLong();
            var customerNode =
                draft.has("customer_id")
                    ? draft.path("customer_id")
                    : draft.path("customer").path("id");
            customer = customerNode.isTextual() ? UUID.fromString(customerNode.asText()) : null;
            status.setText("Saved draft loaded · revision " + draftRevision);
            refresh();
          } catch (Exception bad) {
            status.setText("Cannot open draft: " + bad.getMessage());
          } finally {
            pending--;
          }
        },
        message -> {
          pending--;
          status.setText(message);
        });
  }

  private void save() {
    if (preview) {
      status.setText("Design preview cannot save orders.");
      return;
    }
    if (lines.isEmpty() || saving || pending > 0) return;
    if (saveCommand == null) {
      saveCommand = UUID.randomUUID();
      if (!existingDraft) orderId = UUID.randomUUID();
    }
    var command =
        new LocalCommands.CreateDraft(
            saveCommand,
            orderId,
            lines.stream()
                .map(
                    l ->
                        new LocalCommands.DraftLine(
                            l.id(),
                            l.request().priceId(),
                            l.request().expectedPriceRevision(),
                            l.request().quantity(),
                            l.request().selection(),
                            l.notes()))
                .toList(),
            customer);
    saving = true;
    submitting.set(true);
    task(
        () ->
            existingDraft
                ? gateway.catalogue(
                    "items/drafts/" + orderId,
                    "PUT",
                    new LocalCommands.EditDraft(
                        command.commandId(), draftRevision, command.lines(), command.customerId()),
                    token)
                : gateway.catalogue("items/drafts", "POST", command, token),
        result -> {
          saving = false;
          submitting.set(false);
          existingDraft = false;
          lines.clear();
          selected = null;
          customer = null;
          saveCommand = null;
          refresh();
          status.setText(
              "Draft saved · "
                  + result.path("reference").asText(orderId.toString())
                  + " · No sale or payment recorded");
          poll();
        },
        message -> {
          saving = false;
          submitting.set(false);
          status.setText(message + " · Draft retained. Retry preserves command identity.");
        });
  }

  private void refresh() {
    customerButtons.forEach(
        b ->
            b.setText(
                customer == null
                    ? "Walk-in · Choose customer"
                    : "Customer: "
                        + (customerName == null
                            ? customer.toString().substring(0, 8)
                            : customerName)));
    totals.forEach(l -> l.setText("Subtotal   R " + money(total())));
    orderLists.forEach(
        t -> {
          t.refresh();
          t.getSelectionModel().select(selected());
        });
  }

  private <T> void task(java.util.concurrent.Callable<T> work, Consumer<T> success) {
    task(work, success, message -> status.setText(message));
  }

  private <T> void task(
      java.util.concurrent.Callable<T> work, Consumer<T> success, Consumer<String> failure) {
    executor.execute(
        () -> {
          try {
            var result = work.call();
            Platform.runLater(
                () -> {
                  if (!closed) success.accept(result);
                });
          } catch (Exception e) {
            Platform.runLater(
                () -> {
                  if (!closed)
                    failure.accept(
                        "Operation not confirmed: "
                            + (e.getMessage() == null ? "check store connection" : e.getMessage()));
                });
          }
        });
  }

  boolean confirmClose() {
    return !saving
        && pending == 0
        && (lines.isEmpty() || confirm("Discard this unsaved draft and leave Sales?"));
  }

  @Override
  public void close() {
    closed = true;
    polling.stop();
    if (toppingPage != null) toppingPage.close();
    if (dialog != null) dialog.close();
    lines.clear();
  }
}
