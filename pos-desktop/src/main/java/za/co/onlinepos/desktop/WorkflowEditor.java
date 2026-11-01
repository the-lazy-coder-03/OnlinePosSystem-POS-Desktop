package za.co.onlinepos.desktop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import za.co.onlinepos.contracts.screen.SalesScreens;

/** Visual editor for a bounded declarative graph. The server approves actions independently. */
final class WorkflowEditor extends Dialog<List<SalesScreens.Workflow>> {
  private final ArrayList<SalesScreens.Workflow> flows;
  private final ComboBox<String> flowChoice = new ComboBox<>();
  private final Pane canvas = new Pane();
  private final VBox properties = new VBox(8);
  private final Label message = new Label();
  private final Map<UUID, double[]> positions = new HashMap<>();
  private int current;
  private UUID selected;

  WorkflowEditor(List<SalesScreens.Workflow> existing) {
    flows = new ArrayList<>(existing);
    setTitle("Workflow builder");
    setHeaderText(
        "Link approved actions, prompts and decisions. Each action still requires its backend permission.");
    getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    var addFlow = new Button("New workflow");
    addFlow.setOnAction(
        e -> {
          UUID end = UUID.randomUUID();
          flows.add(
              new SalesScreens.Workflow(
                  UUID.randomUUID(),
                  "Workflow " + (flows.size() + 1),
                  end,
                  List.of(
                      new SalesScreens.Step(
                          end, SalesScreens.NodeType.END, null, null, null, null, null))));
          current = flows.size() - 1;
          selected = end;
          refresh();
        });
    flowChoice.setOnAction(
        e -> {
          int at = flowChoice.getSelectionModel().getSelectedIndex();
          if (at >= 0 && at != current) {
            current = at;
            selected = null;
            render();
          }
        });
    var toolbar = new FlowPane(8, 8, flowChoice, addFlow);
    for (var type : SalesScreens.NodeType.values()) {
      var b = new Button("Add " + type.name().toLowerCase(java.util.Locale.ROOT));
      b.setOnAction(
          e -> {
            if (flows.isEmpty()) return;
            var flow = flow();
            var steps = new ArrayList<>(flow.steps());
            UUID id = UUID.randomUUID();
            steps.add(
                new SalesScreens.Step(
                    id,
                    type,
                    type == SalesScreens.NodeType.ACTION ? SalesScreens.Action.SAVE_DRAFT : null,
                    type == SalesScreens.NodeType.DECISION ? "CART_EMPTY" : null,
                    type == SalesScreens.NodeType.PROMPT ? "Continue?" : null,
                    null,
                    null));
            flows.set(
                current,
                new SalesScreens.Workflow(
                    flow.id(), flow.name(), flow.entry(), List.copyOf(steps)));
            selected = id;
            render();
          });
      toolbar.getChildren().add(b);
    }
    canvas.setPrefSize(760, 620);
    properties.setPrefWidth(270);
    properties.setStyle("-fx-padding:12;");
    var root = new BorderPane(new ScrollPane(canvas));
    root.setTop(toolbar);
    root.setRight(new ScrollPane(properties));
    message.setWrapText(true);
    root.setBottom(message);
    getDialogPane().setContent(root);
    getDialogPane().setPrefSize(1100, 750);
    setResultConverter(type -> type == ButtonType.OK ? List.copyOf(flows) : null);
    refresh();
  }

  private SalesScreens.Workflow flow() {
    return flows.get(current);
  }

  private void refresh() {
    flowChoice.getItems().setAll(flows.stream().map(SalesScreens.Workflow::name).toList());
    if (!flows.isEmpty()) flowChoice.getSelectionModel().select(current);
    render();
  }

  private void render() {
    canvas.getChildren().clear();
    properties.getChildren().clear();
    if (flows.isEmpty()) {
      message.setText("Create a workflow to begin.");
      return;
    }
    var f = flow();
    int i = 0;
    for (var step : f.steps()) {
      positions.computeIfAbsent(step.id(), id -> new double[2]);
      var p = positions.get(step.id());
      if (p[0] == 0 && p[1] == 0) {
        p[0] = 24 + (i % 3) * 235;
        p[1] = 24 + (i / 3) * 150;
      }
      i++;
    }
    for (var step : f.steps())
      for (var dest :
          java.util.stream.Stream.of(step.next(), step.otherwise())
              .filter(java.util.Objects::nonNull)
              .toList()) {
        var a = positions.get(step.id());
        var b = positions.get(dest);
        if (b != null) {
          var line = new javafx.scene.shape.Line(a[0] + 90, a[1] + 80, b[0] + 90, b[1]);
          line.setStroke(javafx.scene.paint.Color.TEAL);
          line.setMouseTransparent(true);
          canvas.getChildren().add(line);
        }
      }
    for (var step : f.steps()) {
      var node =
          new VBox(
              6,
              new Label((step.id().equals(f.entry()) ? "START · " : "") + step.type()),
              new Label(
                  step.action() != null
                      ? step.action().name()
                      : step.prompt() != null
                          ? step.prompt()
                          : step.condition() != null ? step.condition() : "Finish"));
      node.setPrefSize(180, 90);
      node.setStyle(
          "-fx-padding:10;-fx-background-color:"
              + (step.id().equals(selected) ? "#cceae5" : "#edf2f7")
              + ";-fx-border-color:#087f7a;");
      var p = positions.get(step.id());
      node.relocate(p[0], p[1]);
      final double[] origin = new double[2];
      node.setOnMousePressed(
          e -> {
            selected = step.id();
            origin[0] = e.getSceneX() - p[0];
            origin[1] = e.getSceneY() - p[1];
            inspect();
          });
      node.setOnMouseDragged(
          e -> {
            p[0] = Math.max(0, e.getSceneX() - origin[0]);
            p[1] = Math.max(0, e.getSceneY() - origin[1]);
            node.relocate(p[0], p[1]);
          });
      node.setOnMouseReleased(e -> render());
      canvas.getChildren().add(node);
    }
    inspect();
  }

  private ComboBox<String> destinations(UUID id) {
    var combo = new ComboBox<String>();
    combo.getItems().add("None");
    for (int i = 0; i < flow().steps().size(); i++)
      combo.getItems().add((i + 1) + " · " + flow().steps().get(i).type());
    int at = 0;
    for (int i = 0; i < flow().steps().size(); i++)
      if (flow().steps().get(i).id().equals(id)) at = i + 1;
    combo.getSelectionModel().select(at);
    return combo;
  }

  private UUID destination(ComboBox<String> combo) {
    int at = combo.getSelectionModel().getSelectedIndex() - 1;
    return at < 0 ? null : flow().steps().get(at).id();
  }

  private void inspect() {
    properties.getChildren().clear();
    var f = flow();
    var name = new TextField(f.name());
    var rename = new Button("Rename workflow");
    rename.setOnAction(
        e -> {
          flows.set(
              current, new SalesScreens.Workflow(f.id(), name.getText(), f.entry(), f.steps()));
          refresh();
        });
    properties.getChildren().addAll(new Label("Workflow name"), name, rename);
    var step = f.steps().stream().filter(n -> n.id().equals(selected)).findFirst();
    if (step.isEmpty()) return;
    var s = step.get();
    var action = new ComboBox<SalesScreens.Action>();
    action.getItems().addAll(SalesScreens.Action.values());
    action.setValue(s.action());
    var condition = new ComboBox<String>();
    condition.getItems().addAll("HAS_SELECTION", "CART_EMPTY", "CUSTOMER_SELECTED");
    condition.setValue(s.condition());
    var prompt = new TextField(s.prompt());
    var next = destinations(s.next());
    var otherwise = destinations(s.otherwise());
    properties.getChildren().add(new Label("Selected: " + s.type()));
    switch (s.type()) {
      case ACTION -> properties.getChildren().addAll(new Label("Action"), action);
      case PROMPT -> properties.getChildren().addAll(new Label("Confirmation message"), prompt);
      case DECISION ->
          properties
              .getChildren()
              .addAll(new Label("Condition"), condition, new Label("False destination"), otherwise);
      default -> {}
    }
    if (s.type() != SalesScreens.NodeType.END)
      properties
          .getChildren()
          .addAll(
              new Label(
                  s.type() == SalesScreens.NodeType.DECISION ? "True destination" : "Next step"),
              next);
    var apply = new Button("Apply node");
    apply.setOnAction(
        e -> {
          var updated =
              new SalesScreens.Step(
                  s.id(),
                  s.type(),
                  s.type() == SalesScreens.NodeType.ACTION ? action.getValue() : null,
                  s.type() == SalesScreens.NodeType.DECISION ? condition.getValue() : null,
                  s.type() == SalesScreens.NodeType.PROMPT ? prompt.getText() : null,
                  s.type() == SalesScreens.NodeType.END ? null : destination(next),
                  s.type() == SalesScreens.NodeType.DECISION ? destination(otherwise) : null);
          flows.set(
              current,
              new SalesScreens.Workflow(
                  f.id(),
                  f.name(),
                  f.entry(),
                  f.steps().stream().map(n -> n.id().equals(s.id()) ? updated : n).toList()));
          if (updated.action() != null
              && !SalesScreens.draftCapabilities().available().contains(updated.action()))
            message.setText(
                "This action has no authoritative backend. Its runtime button will be hidden.");
          render();
        });
    var start = new Button("Use as start");
    start.setOnAction(
        e -> {
          flows.set(current, new SalesScreens.Workflow(f.id(), f.name(), s.id(), f.steps()));
          render();
        });
    var delete = new Button("Delete node");
    delete.setOnAction(
        e -> {
          if (s.id().equals(f.entry())) {
            message.setText("Choose another start node first.");
            return;
          }
          flows.set(
              current,
              new SalesScreens.Workflow(
                  f.id(),
                  f.name(),
                  f.entry(),
                  f.steps().stream().filter(n -> !n.id().equals(s.id())).toList()));
          selected = null;
          render();
        });
    properties.getChildren().addAll(apply, start, delete);
  }
}
