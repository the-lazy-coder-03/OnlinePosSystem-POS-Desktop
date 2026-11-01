package za.co.onlinepos.desktop;

import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import za.co.onlinepos.contracts.ItemCustomization.Quote;
import za.co.onlinepos.contracts.ItemCustomization.QuoteRequest;

/** Embedded topping page. Late quotations cannot apply a cancelled or superseded draft. */
final class ToppingPage extends BorderPane implements AutoCloseable {
  interface Quoter {
    void quote(QuoteRequest request, Consumer<Quote> success, Consumer<String> failure);
  }

  interface Commit {
    void apply(QuoteRequest request, Quote quote);
  }

  private final ToppingDraft draft;
  private final Quoter quoter;
  private final Commit commit;
  private final Runnable cancel;
  private final Label modeNotice = new Label("ADD mode · tap a topping");
  private final Label price = new Label("Checking price…"), message = new Label();
  private final ToggleButton remove = new ToggleButton("Remove");
  private final Button ok = new Button("OK"), retry = new Button("Retry price");
  private final PauseTransition debounce = new PauseTransition(Duration.millis(150));
  private final BorderPane content = new BorderPane();
  private ToppingDraft.Target active;
  private long revision;
  private boolean closed, committing, valid;

  ToppingPage(String name, ToppingDraft draft, Quoter quoter, Commit commit, Runnable cancel) {
    this.draft = draft;
    this.quoter = quoter;
    this.commit = commit;
    this.cancel = cancel;
    setId("topping-page");
    getStyleClass().add("topping-page");
    setPadding(new Insets(12));
    var title = new Label(name + " · Toppings");
    title.getStyleClass().add("topping-title");
    var hint = new Label("Portions per item · changes apply only after OK");
    message.setWrapText(true);
    message.setId("topping-message");
    price.setId("topping-total");
    remove.setId("topping-remove");
    remove.setOnAction(
        e -> {
          mode();
          renderTarget();
        });
    ok.setId("topping-ok");
    ok.setDefaultButton(true);
    ok.setOnAction(
        e -> {
          if (valid && !committing) {
            committing = true;
            request(true);
            renderTarget();
          }
        });
    var back = new Button("Cancel");
    back.setId("topping-cancel");
    back.setCancelButton(true);
    back.setOnAction(
        e -> {
          close();
          cancel.run();
        });
    retry.setId("topping-retry");
    retry.setOnAction(e -> changed());
    setTop(new VBox(8, title, hint, modeNotice, price, message));
    setCenter(content);
    setBottom(new HBox(12, remove, ok, back, retry));
    BorderPane.setMargin(getBottom(), new Insets(12, 0, 0, 0));
    debounce.setOnFinished(e -> request(false));
    if (!draft.combo()) active = draft.targets().getFirst();
    mode();
    renderTarget();
    changed();
  }

  private void mode() {
    modeNotice.setText(
        remove.isSelected()
            ? "REMOVE mode · next valid tap removes one portion"
            : "ADD mode · tap a topping");
    modeNotice.setStyle(
        remove.isSelected()
            ? "-fx-text-fill:#ff8282;-fx-font-weight:bold;"
            : "-fx-text-fill:#a6f582;");
    remove.setText(remove.isSelected() ? "REMOVE · tap one topping" : "Remove");
    remove.setStyle(remove.isSelected() ? "-fx-background-color:#bd262d;-fx-text-fill:white;" : "");
  }

  private void renderTarget() {
    remove.setDisable(committing || active == null);
    if (active == null) {
      var choices = new VBox(12, new Label("Choose the combo component to customise"));
      choices.setId("topping-components");
      for (var target : draft.targets()) {
        var button = new Button(target.label());
        button.setUserData(target.index());
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(
            e -> {
              active = target;
              remove.setSelected(false);
              mode();
              renderTarget();
            });
        button.setDisable(committing);
        choices.getChildren().add(button);
      }
      var scroll = new ScrollPane(choices);
      scroll.setFitToWidth(true);
      showContent(scroll);
      return;
    }
    var tiles = new TilePane(6, 6);
    tiles.setPrefColumns(5);
    tiles.setPrefTileWidth(100);
    tiles.setPrefTileHeight(76);
    tiles.setId("topping-buttons");
    for (var amount : draft.amounts(active)) {
      var extra = amount.extra();
      var button =
          new Button(
              extra.name() + "\n" + (extra.price() == null ? "Included" : "+R " + extra.price()));
      button.setUserData(extra.key());
      button.setId("topping-ingredient-" + extra.key());
      button.setAccessibleText(extra.name() + ", " + amount.total() + " portions");
      button.setTooltip(
          new javafx.scene.control.Tooltip(
              extra.name()
                  + (extra.price() == null ? "" : " · Extra R " + extra.price() + " per portion")
                  + (!extra.available() ? " · Unavailable to add" : "")));
      button.setWrapText(true);
      button.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
      button.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
      button.setMinSize(0, 64);
      button.getStyleClass().add("topping-tile");
      String bg =
          switch (Math.floorMod(extra.key().hashCode(), 5)) {
            case 0 -> "#174bda";
            case 1 -> "#56cf26";
            case 2 -> "#f0d634";
            case 3 -> "#e56320";
            default -> "#b92cbd";
          };
      String fg =
          bg.equals("#56cf26") || bg.equals("#f0d634") || bg.equals("#e56320")
              ? "#102018"
              : "white";
      button.setStyle("-fx-background-color:" + bg + ";-fx-text-fill:" + fg + ";");
      button.setDisable(committing || !draft.canTap(active, extra.key(), remove.isSelected()));
      button.setOnAction(
          e -> {
            if (draft.tap(active, extra.key(), remove.isSelected())) {
              remove.setSelected(false);
              mode();
              renderTarget();
              changed();
              var replacement = content.lookup("#topping-buttons");
              if (replacement != null)
                replacement.lookupAll(".button").stream()
                    .filter(n -> extra.key().equals(n.getUserData()))
                    .findFirst()
                    .ifPresent(javafx.scene.Node::requestFocus);
            }
          });
      tiles.getChildren().add(button);
    }
    var picker = new ScrollPane(tiles);
    picker.setFitToWidth(true);
    tiles.prefTileWidthProperty().bind(picker.widthProperty().subtract(44).divide(5));
    var table =
        new TableView<ToppingDraft.Amount>(
            FXCollections.observableArrayList(
                draft.amounts(active).stream()
                    .filter(
                        a ->
                            a.total() > 0
                                || a.included() > 0
                                || a.added() > 0
                                || a.removed() > 0
                                || a.swappedIn() > 0)
                    .toList()));
    table.setId("topping-ingredients");
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    table.setFixedCellSize(48);
    column(table, "Qty", a -> "" + a.total(), 45);
    column(table, "Ingredient", a -> a.extra().name(), 160);
    column(table, "Included", a -> "" + a.included(), 65);
    column(table, "Added", a -> "" + a.added(), 55);
    column(table, "Removed", a -> "" + a.removed(), 70);
    column(
        table,
        "Swap",
        a -> {
          int net = a.total() - a.included() - a.added() + a.removed();
          return net == 0 ? "" : (net > 0 ? "+" : "") + net;
        },
        55);
    var summary = new VBox(10, new Label(active.label()), table);
    VBox.setVgrow(table, Priority.ALWAYS);
    if (draft.combo()) {
      var choose = new Button("Choose another component");
      choose.setDisable(committing);
      choose.setOnAction(
          e -> {
            active = null;
            remove.setSelected(false);
            mode();
            renderTarget();
          });
      summary.getChildren().add(choose);
    }
    var row = new HBox(12, picker, summary);
    HBox.setHgrow(summary, Priority.ALWAYS);
    picker.prefWidthProperty().bind(widthProperty().multiply(0.45));
    summary.setMinWidth(260);
    showContent(row);
  }

  private void showContent(javafx.scene.Node node) {
    content.setCenter(node);
    content.applyCss();
    content.layout();
  }

  private void column(
      TableView<ToppingDraft.Amount> table,
      String label,
      java.util.function.Function<ToppingDraft.Amount, String> value,
      double width) {
    var col = new TableColumn<ToppingDraft.Amount, String>(label);
    col.setCellValueFactory(
        c -> new javafx.beans.property.ReadOnlyStringWrapper(value.apply(c.getValue())));
    col.setPrefWidth(width);
    table.getColumns().add(col);
  }

  private void changed() {
    revision++;
    valid = false;
    ok.setDisable(true);
    message.setText("Checking the item price…");
    debounce.playFromStart();
  }

  private void request(boolean apply) {
    debounce.stop();
    long ticket = ++revision;
    var request = draft.request();
    valid = false;
    ok.setDisable(true);
    retry.setDisable(true);
    quoter.quote(
        request,
        quote -> {
          if (closed || ticket != revision) return;
          price.setText("Item total  R " + quote.total().setScale(2));
          message.setText("Price checked · OK applies these changes to this line only.");
          if (apply) {
            close();
            commit.apply(request, quote);
          } else {
            valid = true;
            ok.setDisable(false);
            retry.setDisable(false);
          }
        },
        error -> {
          if (closed || ticket != revision) return;
          committing = false;
          valid = false;
          ok.setDisable(true);
          retry.setDisable(false);
          message.setText(error + " · Changes retained. Retry price or Cancel.");
          renderTarget();
        });
  }

  @Override
  public void close() {
    closed = true;
    revision++;
    debounce.stop();
  }
}
