package za.co.onlinepos.desktop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.ItemCustomization.Component;
import za.co.onlinepos.contracts.ItemCustomization.Product;
import za.co.onlinepos.contracts.ItemCustomization.Selection;
import za.co.onlinepos.contracts.ItemCustomization.Swap;

final class SalesSelection {
  private final ItemCustomization.Catalog catalog;

  SalesSelection(ItemCustomization.Catalog catalog) {
    this.catalog = catalog;
  }

  private static VBox box() {
    return new VBox(10);
  }

  private record Named<T>(T id, String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static <T> T value(ComboBox<Named<T>> box) {
    return box.getValue() == null ? null : box.getValue().id();
  }

  final class SelectionForm {
    final VBox node = box();
    final List<VBox> stages = new ArrayList<>();
    final List<Runnable> stageChecks = new ArrayList<>();
    final Map<String, Integer> removals = new LinkedHashMap<>(), additions = new LinkedHashMap<>();
    final List<Swap> swaps = new ArrayList<>();
    final Map<String, List<CheckBox>> choices = new LinkedHashMap<>();
    final Map<String, CheckBox> componentChecks = new LinkedHashMap<>();
    final List<String> componentSlotKeys = new ArrayList<>();
    final Map<String, SelectionForm> componentForms = new LinkedHashMap<>();

    SelectionForm(Product p) {
      if (p.combo() != null) {
        for (var slot : p.combo().slots()) {
          var stage = box();
          stages.add(stage);
          node.getChildren().add(stage);
          componentSlotKeys.add(slot.key());
          stage
              .getChildren()
              .add(new Label(slot.name() + " · choose " + slot.min() + "–" + slot.max()));
          for (var id : slot.choices().keySet()) {
            var product = catalog.products().get(id);
            if (!product.available()) continue;
            String key = slot.key() + "/" + id;
            var check =
                new CheckBox(
                    product.name()
                        + " × "
                        + slot.quantityFor(id)
                        + (p.combo().mode() == ItemCustomization.PricingMode.FIXED
                            ? " +R " + slot.choices().get(id)
                            : " · R " + product.price() + " each"));
            check.setUserData(new Component(slot.key(), id, product.priceRevision(), null));
            check.setSelected(id.equals(slot.defaultPriceId()));
            componentChecks.put(key, check);
            var form = new SelectionForm(ItemCustomization.effective(catalog, product));
            componentForms.put(key, form);
            var customize =
                new javafx.scene.control.TitledPane("Customize " + product.name(), form.node);
            customize.setExpanded(false);
            customize.disableProperty().bind(check.selectedProperty().not());
            stage.getChildren().addAll(check, customize);
          }
          stageChecks.add(
              () -> {
                var chosen =
                    componentChecks.entrySet().stream()
                        .filter(
                            e ->
                                e.getKey().startsWith(slot.key() + "/")
                                    && e.getValue().isSelected())
                        .toList();
                if (chosen.size() < slot.min() || chosen.size() > slot.max())
                  throw new IllegalArgumentException(
                      "Choose " + slot.min() + "–" + slot.max() + " for " + slot.name());
                for (var choice : chosen) {
                  var component = (Component) choice.getValue().getUserData();
                  ItemCustomization.quote(
                      catalog,
                      new ItemCustomization.QuoteRequest(
                          component.priceId(),
                          component.expectedPriceRevision(),
                          slot.quantityFor(component.priceId()),
                          componentForms.get(choice.getKey()).selection()));
                }
              });
        }
        return;
      }
      for (var group : p.groups()) {
        node.getChildren()
            .add(new Label(group.name() + " · choose " + group.min() + "–" + group.max()));
        var values = new ArrayList<CheckBox>();
        for (var choice : group.choices()) {
          var check = new CheckBox(choice.name() + " +R " + choice.surcharge());
          check.setUserData(choice.key());
          check.setSelected(choice.defaultSelected());
          check.setDisable(!choice.available());
          values.add(check);
          node.getChildren().add(check);
        }
        choices.put(group.key(), values);
      }
      var replaced = new java.util.HashSet<String>();
      for (var group : p.groups())
        if (group.replacesKey() != null && !group.replacesKey().isBlank()) {
          replaced.add(group.replacesKey());
          group
              .choices()
              .forEach(
                  c -> {
                    if (c.componentKey() != null) replaced.add(c.componentKey());
                  });
        }
      for (var extra : p.extras()) {
        if (extra.included() > 0 && !replaced.contains(extra.key())) removals.put(extra.key(), 0);
        if (extra.available() && extra.price() != null) additions.put(extra.key(), 0);
      }
      if (!p.extras().isEmpty())
        node.getChildren()
            .add(new Label("To change toppings, add the item, then double-tap its order line."));
      var from = new ComboBox<Named<String>>();
      var to = new ComboBox<Named<String>>();
      for (var e : p.extras()) {
        if (e.included() > 0 && e.removable()) from.getItems().add(new Named<>(e.key(), e.name()));
      }
      from.setOnAction(
          event -> {
            to.getItems().clear();
            if (from.getValue() == null) return;
            var original =
                p.extras().stream()
                    .filter(e -> e.key().equals(value(from)))
                    .findFirst()
                    .orElseThrow();
            for (var replacement : p.extras())
              if (replacement.available()
                  && replacement.price() != null
                  && original.swapGroup() != null
                  && !original.swapGroup().isBlank()
                  && original.swapGroup().equals(replacement.swapGroup())
                  && !original.key().equals(replacement.key()))
                to.getItems().add(new Named<>(replacement.key(), replacement.name()));
          });
      var count = new Spinner<Integer>(1, 99, 1);
      var swap = new Button("Add swap");
      var summary = new Label();
      var clear = new Button("Clear swaps");
      swap.setOnAction(
          e -> {
            if (from.getValue() != null && to.getValue() != null) {
              swaps.add(new Swap(value(from), value(to), count.getValue()));
              summary.setText(
                  swaps.stream()
                      .map(
                          s ->
                              p.extras().stream()
                                      .filter(x -> x.key().equals(s.from()))
                                      .findFirst()
                                      .orElseThrow()
                                      .name()
                                  + " → "
                                  + p.extras().stream()
                                      .filter(x -> x.key().equals(s.to()))
                                      .findFirst()
                                      .orElseThrow()
                                      .name()
                                  + " × "
                                  + s.quantity())
                      .toList()
                      .toString());
            }
          });
      clear.setOnAction(
          e -> {
            swaps.clear();
            summary.setText("");
          });
      node.getChildren()
          .addAll(
              new Label("Compatible topping swaps (charged only for upgrades)"),
              new FlowPane(10, 8, from, to, count, swap, clear),
              summary);
    }

    void restore(Selection selection) {
      if (selection == null) return;
      selection
          .removals()
          .forEach(
              (k, v) -> {
                removals.put(k, v);
              });
      selection
          .additions()
          .forEach(
              (k, v) -> {
                additions.put(k, v);
              });
      swaps.addAll(selection.swaps());
      selection
          .choices()
          .forEach(
              (k, v) -> {
                if (choices.containsKey(k))
                  choices
                      .get(k)
                      .forEach(c -> c.setSelected(v.contains(c.getUserData().toString())));
              });
      if (!selection.components().isEmpty()) {
        componentChecks.values().forEach(c -> c.setSelected(false));
        selection
            .components()
            .forEach(
                c -> {
                  String key = c.slot() + "/" + c.priceId();
                  if (componentChecks.containsKey(key)) {
                    componentChecks.get(key).setSelected(true);
                    componentForms.get(key).restore(c.selection());
                  }
                });
      }
    }

    Selection selection() {
      var remove = new LinkedHashMap<String, Integer>();
      removals.forEach(
          (k, v) -> {
            if (v > 0) remove.put(k, v);
          });
      var extra = new LinkedHashMap<String, Integer>();
      additions.forEach(
          (k, v) -> {
            if (v > 0) extra.put(k, v);
          });
      var selected = new LinkedHashMap<String, List<String>>();
      choices.forEach(
          (k, v) ->
              selected.put(
                  k,
                  v.stream()
                      .filter(CheckBox::isSelected)
                      .map(c -> c.getUserData().toString())
                      .toList()));
      var slots = new ArrayList<Component>();
      componentChecks.forEach(
          (key, check) -> {
            if (check.isSelected()) {
              var component = (Component) check.getUserData();
              slots.add(
                  new Component(
                      component.slot(),
                      component.priceId(),
                      component.expectedPriceRevision(),
                      componentForms.get(key).selection()));
            }
          });
      for (String slotKey : componentSlotKeys)
        if (slots.stream().noneMatch(c -> c.slot().equals(slotKey)))
          slots.add(new Component(slotKey, null, 0, null));
      return new Selection(catalog.revision(), remove, extra, List.copyOf(swaps), selected, slots);
    }
  }
}
