package za.co.onlinepos.desktop;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.ItemCustomization.Catalog;
import za.co.onlinepos.contracts.ItemCustomization.Choice;
import za.co.onlinepos.contracts.ItemCustomization.Combo;
import za.co.onlinepos.contracts.ItemCustomization.Component;
import za.co.onlinepos.contracts.ItemCustomization.Extra;
import za.co.onlinepos.contracts.ItemCustomization.ExtraRule;
import za.co.onlinepos.contracts.ItemCustomization.Group;
import za.co.onlinepos.contracts.ItemCustomization.ItemRule;
import za.co.onlinepos.contracts.ItemCustomization.Product;
import za.co.onlinepos.contracts.ItemCustomization.Quote;
import za.co.onlinepos.contracts.ItemCustomization.QuoteRequest;
import za.co.onlinepos.contracts.ItemCustomization.Rules;
import za.co.onlinepos.contracts.ItemCustomization.Selection;
import za.co.onlinepos.contracts.ItemCustomization.Slot;
import za.co.onlinepos.contracts.ItemCustomization.Swap;
import za.co.onlinepos.contracts.LocalCommands;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.TrustedStoreClient;

/** Item-oriented configuration with staged rules and a separately confirmed draft cart. */
final class ItemWorkbench extends BorderPane implements AutoCloseable {
  private record Named<T>(T value, String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  private final ModuleGateway gateway;
  private final ExecutorService executor;
  private final String token;
  private final Label feedback = new Label();
  private final ComboBox<Named<UUID>> products = new ComboBox<>();
  private final TabPane tabs = new TabPane();
  private final Button save = new Button("Save settings"),
      reload = new Button("Reload / discard edits"),
      add = new Button("Add quoted item to draft"),
      persist = new Button("Save draft"),
      clear = new Button("Clear cart");
  private final List<LocalCommands.DraftLine> cart = new ArrayList<>();
  private final ListView<String> cartView = new ListView<>();
  private Catalog catalog;
  private Rules rules;
  private boolean busy, closed, dirty;
  private final boolean orderingOnly;
  private final ComboBox<String> itemType = new ComboBox<>();
  private boolean fillingType;
  private Runnable addCategory;
  private final ComboBox<Named<String>> categoryFilter = new ComboBox<>();
  private ItemCustomization.Edit pending;
  private LocalCommands.CreateDraft pendingDraft;
  private Dialog<?> dialog;
  private Quote quote;
  private QuoteRequest confirmedRequest;
  private SelectionForm selectionForm;
  private Spinner<Integer> quantity;
  private final TextArea breakdown = new TextArea();

  ItemWorkbench(ModuleGateway gateway, ExecutorService executor, String token) {
    this(gateway, executor, token, false);
  }

  ItemWorkbench(
      ModuleGateway gateway, ExecutorService executor, String token, boolean orderingOnly) {
    this.orderingOnly = orderingOnly;
    this.gateway = gateway;
    this.executor = executor;
    this.token = token;
    setId("item-workbench");
    setPadding(new Insets(12));
    products.setId("item-product");
    products.setPrefWidth(430);
    products.setOnAction(
        e -> {
          quote = null;
          confirmedRequest = null;
          render();
        });
    save.setId("item-save");
    save.setOnAction(e -> save());
    reload.setId("item-reload");
    reload.setOnAction(
        e -> {
          if (confirmClose()) {
            cart.clear();
            cartView.getItems().clear();
            pendingDraft = null;
            load();
          }
        });
    itemType.setId("item-type");
    itemType.getItems().setAll("Standard", "Combo");
    itemType.setOnAction(
        e -> {
          if (fillingType || catalog == null || id() == null) return;
          if ("Combo".equals(itemType.getValue())) {
            if (product().combo() == null) combo(new Combo(product().price(), List.of()));
          } else {
            var map = new LinkedHashMap<>(rules.combos());
            map.remove(id());
            rules = new Rules(rules.categoryExtras(), rules.swapGroups(), rules.items(), map);
            changed();
          }
          render();
        });
    categoryFilter.setId("sales-category");
    categoryFilter.setOnAction(e -> filterProducts());
    setTop(
        new VBox(
            8,
            new Label(
                orderingOnly
                    ? "Sales & Orders · draft ordering"
                    : "Item setup · included toppings, extras, options and combos"),
            orderingOnly
                ? new Label("Drafts only · checkout, payments and receipts are not yet available.")
                : new Label("Catalogue configuration"),
            orderingOnly
                ? new FlowPane(10, 10, categoryFilter, products, reload)
                : new FlowPane(10, 10, products, itemType, save, reload)));
    tabs.setId("item-tabs");
    tabs.setTabMinWidth(135);
    tabs.setTabMaxWidth(220);
    tabs.setTabMinHeight(38);
    setCenter(tabs);
    feedback.setWrapText(true);
    feedback.setId("item-feedback");
    setBottom(feedback);
    load();
  }

  private <T> void work(Callable<T> task, Consumer<T> success) {
    if (busy || closed) return;
    busy = true;
    tabs.setDisable(true);
    save.setDisable(true);
    products.setDisable(true);
    reload.setDisable(true);
    itemType.setDisable(true);
    feedback.setText("Working…");
    executor.execute(
        () -> {
          try {
            var value = task.call();
            Platform.runLater(
                () -> {
                  if (closed) return;
                  unlock();
                  feedback.setText("");
                  success.accept(value);
                });
          } catch (Exception failure) {
            Platform.runLater(
                () -> {
                  if (closed) return;
                  unlock();
                  int status =
                      failure instanceof TrustedStoreClient.ModuleFailure f ? f.status() : 503;
                  if (status == 400 || status == 409) {
                    pendingDraft = null;
                    quote = null;
                    confirmedRequest = null;
                  }
                  feedback.setText(
                      status == 409
                          ? "Catalogue or price changed. Reload and review before retrying."
                          : status == 401 || status == 403
                              ? "An authenticated staff session with the required permission is needed."
                              : status == 400
                                  ? "The Master rejected these selections. Check required choices, prices and combo settings."
                                  : "Connection error. Save was not confirmed. Your staged changes and draft are retained for retry.");
                });
          }
        });
  }

  private void unlock() {
    busy = false;
    tabs.setDisable(false);
    products.setDisable(false);
    reload.setDisable(false);
    itemType.setDisable(catalog == null || !catalog.editable());
    save.setDisable(catalog == null || !catalog.editable());
  }

  private void load() {
    work(
        () ->
            JsonFiles.JSON.treeToValue(
                gateway.catalogue("items", "GET", null, token), Catalog.class),
        c -> {
          catalog = c;
          rules = c.rules();
          dirty = false;
          pending = null;
          quote = null;
          confirmedRequest = null;
          categoryFilter.getItems().clear();
          categoryFilter.getItems().add(new Named<>(null, "All categories"));
          c.categories()
              .forEach(
                  (key, name) -> {
                    if (!orderingOnly
                        || c.products().values().stream()
                            .anyMatch(
                                p ->
                                    key.equals(p.category())
                                        && ItemCustomization.effective(c, p).available()))
                      categoryFilter.getItems().add(new Named<>(key, name));
                  });
          categoryFilter.getSelectionModel().selectFirst();
          filterProducts();
          render();
          feedback.setText(
              gateway instanceof PreviewGateway
                  ? "Preview only — fictional catalogue; configuration changes stay in memory."
                  : "Settings loaded from the Master. Existing draft snapshots are unchanged.");
        });
  }

  private Catalog staged() {
    return new Catalog(
        catalog.revision(),
        catalog.products(),
        catalog.extras(),
        catalog.categories(),
        rules,
        catalog.editable(),
        catalog.parents());
  }

  private UUID id() {
    return products.getValue() == null ? null : products.getValue().value();
  }

  private Product product() {
    return ItemCustomization.effective(staged(), catalog.products().get(id()));
  }

  private void changed() {
    dirty = true;
    pending = null;
    quote = null;
    confirmedRequest = null;
    feedback.setText("Unsaved item settings. Save to obtain an authoritative quote.");
    render();
  }

  private void item(ItemRule rule) {
    var map = new LinkedHashMap<>(rules.items());
    map.put(id(), rule);
    rules = new Rules(rules.categoryExtras(), rules.swapGroups(), map, rules.combos());
    changed();
  }

  private ItemRule item() {
    return rules.items().getOrDefault(id(), new ItemRule(List.of(), Map.of(), List.of(), false));
  }

  private void save() {
    try {
      ItemCustomization.validate(staged());
      if (pending == null)
        pending = new ItemCustomization.Edit(UUID.randomUUID(), catalog.revision(), rules);
      var request = pending;
      work(
          () -> gateway.catalogue("items/rules", "PUT", request, token),
          r -> {
            dirty = false;
            pending = null;
            load();
          });
    } catch (IllegalArgumentException e) {
      feedback.setText(e.getMessage());
    }
  }

  private Tab tab(String title, javafx.scene.Node content) {
    var tab = new Tab(title, content);
    tab.setClosable(false);
    return tab;
  }

  private ScrollPane scroll(javafx.scene.Node n) {
    var s = new ScrollPane(n);
    s.setFitToWidth(true);
    return s;
  }

  private Button action(String label, Runnable action) {
    var b = new Button(label);
    b.setDisable(!catalog.editable());
    b.setOnAction(e -> action.run());
    return b;
  }

  private void filterProducts() {
    if (catalog == null) return;
    products.getItems().clear();
    String category = categoryFilter.getValue() == null ? null : categoryFilter.getValue().value();
    catalog.products().values().stream()
        .filter(
            p ->
                !orderingOnly
                    || p.available()
                        && ItemCustomization.withinCategory(catalog, category, p.category()))
        .sorted(Comparator.comparing(Product::name))
        .forEach(
            p ->
                products
                    .getItems()
                    .add(
                        new Named<>(
                            p.priceId(), p.name() + (p.available() ? "" : " [unavailable]"))));
    if (!products.getItems().isEmpty()) products.getSelectionModel().selectFirst();
    else {
      tabs.getTabs().clear();
      render();
      feedback.setText("No available products in this category.");
    }
  }

  void onAddCategory(Runnable action) {
    this.addCategory = action;
  }

  private void render() {
    if (catalog == null) return;
    if (id() == null) {
      var empty =
          new VBox(
              12,
              new Label("Your menu is empty. Create a category, then add products and prices."));
      if (addCategory != null) {
        var add = new Button("Add Category");
        add.setId("empty-add-category");
        add.setOnAction(e -> addCategory.run());
        empty.getChildren().add(add);
      }
      setCenter(empty);
      return;
    }
    setCenter(tabs);
    fillingType = true;
    itemType.setValue(product().combo() == null ? "Standard" : "Combo");
    itemType.setDisable(!catalog.editable());
    fillingType = false;
    int selected = tabs.getSelectionModel().getSelectedIndex();
    tabs.getTabs().clear();
    try {
      if (orderingOnly) tabs.getTabs().add(tab("Order builder", builder()));
      else
        tabs.getTabs()
            .addAll(
                tab("Recipe", recipe()),
                tab("Extras & Swaps", extras()),
                tab("Options", options()),
                tab("Combos", combos()),
                tab("Order builder", builder()));
      if (selected >= 0 && selected < tabs.getTabs().size())
        tabs.getSelectionModel().select(selected);
      save.setDisable(!catalog.editable());
    } catch (IllegalArgumentException e) {
      feedback.setText(e.getMessage());
    }
  }

  private VBox box() {
    var box = new VBox(10);
    box.setPadding(new Insets(12));
    return box;
  }

  private javafx.scene.Node recipe() {
    var b = box();
    b.getChildren()
        .add(
            new Label(
                "Recipe quantities are included. Removing ingredients does not reduce the item price."));
    for (var extra : product().extras())
      if (extra.included() > 0)
        b.getChildren()
            .add(
                new HBox(
                    12,
                    new Label(
                        extra.name()
                            + " × "
                            + extra.included()
                            + " · "
                            + (extra.removable() ? "removable" : "required")
                            + " · "
                            + extra.origin()),
                    action("Edit", () -> editExtra(extra))));
    b.getChildren().add(action("Add / edit recipe ingredient", () -> chooseExtra()));
    return scroll(b);
  }

  private void chooseExtra() {
    var popup = popup("Choose topping");
    var choices = new ComboBox<Named<String>>();
    catalog.extras().values().forEach(e -> choices.getItems().add(new Named<>(e.key(), e.name())));
    popup.getDialogPane().setContent(choices);
    finish(
        popup,
        () -> {
          if (choices.getValue() == null) throw new IllegalArgumentException("Select a topping");
          String key = choices.getValue().value();
          Extra extra =
              product().extras().stream()
                  .filter(e -> e.key().equals(key))
                  .findFirst()
                  .orElse(
                      catalog.products().get(id()).extras().stream()
                          .filter(e -> e.key().equals(key))
                          .findFirst()
                          .orElse(catalog.extras().get(key)));
          Platform.runLater(() -> editExtra(extra));
        });
  }

  private void editExtra(Extra e) {
    var popup = popup("Topping: " + e.name());
    var price = new TextField(e.price() == null ? "" : e.price().toPlainString());
    price.setId("item-extra-price");
    price.setPromptText("Extra price (required for paid additions / swaps)");
    var included = new Spinner<Integer>(0, 99, e.included());
    included.setId("item-included-quantity");
    var removable = new CheckBox("May be removed / swapped");
    removable.setSelected(e.removable());
    var swap = new TextField(e.swapGroup());
    swap.setPromptText("Compatible swap group, e.g. Burger toppings");
    var reset =
        action(
            "Use inherited rule",
            () -> {
              var r = item();
              var overrides = new LinkedHashMap<>(r.extras());
              overrides.remove(e.key());
              var excluded = new ArrayList<>(r.exclusions());
              excluded.remove(e.key());
              popup.close();
              item(new ItemRule(excluded, overrides, r.groups(), r.overrideGroups()));
            });
    popup
        .getDialogPane()
        .setContent(
            new VBox(
                10,
                new Label("Included quantity"),
                included,
                removable,
                new Label("Price per extra portion (R)"),
                price,
                new Label("Swap group"),
                swap,
                reset));
    finish(
        popup,
        () -> {
          var r = item();
          var overrides = new LinkedHashMap<>(r.extras());
          overrides.put(
              e.key(),
              new ExtraRule(
                  price.getText().isBlank() ? null : money(price.getText()),
                  included.getValue(),
                  removable.isSelected(),
                  swap.getText().strip()));
          var excluded = new ArrayList<>(r.exclusions());
          excluded.remove(e.key());
          item(new ItemRule(excluded, overrides, r.groups(), r.overrideGroups()));
        });
  }

  private javafx.scene.Node extras() {
    var b = box();
    String category = product().category();
    b.getChildren()
        .add(
            new Label(
                "Category: "
                    + catalog.categories().getOrDefault(category, category)
                    + " — changes below apply live to every item in this category."));
    var inherited = rules.categoryExtras().getOrDefault(category, List.of());
    for (var e : catalog.extras().values()) {
      var use = new CheckBox(e.name());
      use.setSelected(inherited.contains(e.key()));
      use.setDisable(!catalog.editable());
      use.setOnAction(
          event -> {
            var map = new LinkedHashMap<>(rules.categoryExtras());
            var keys = new ArrayList<>(map.getOrDefault(category, List.of()));
            if (use.isSelected()) keys.add(e.key());
            else keys.remove(e.key());
            map.put(category, keys);
            rules = new Rules(map, rules.swapGroups(), rules.items(), rules.combos());
            changed();
          });
      var swapGroup =
          action(
              "Set shared swap group",
              () -> {
                var popup = popup("Compatible swap group: " + e.name());
                var input = new TextField(rules.swapGroups().getOrDefault(e.key(), ""));
                popup
                    .getDialogPane()
                    .setContent(
                        new VBox(
                            10,
                            new Label(
                                "Equal or cheaper replacements are free within this group. Blank disables shared swapping."),
                            input));
                finish(
                    popup,
                    () -> {
                      var groups = new LinkedHashMap<>(rules.swapGroups());
                      if (input.getText().isBlank()) groups.remove(e.key());
                      else groups.put(e.key(), input.getText().strip());
                      rules =
                          new Rules(rules.categoryExtras(), groups, rules.items(), rules.combos());
                      changed();
                    });
              });
      b.getChildren()
          .add(
              new FlowPane(
                  10,
                  8,
                  use,
                  new Label("Swap group: " + rules.swapGroups().getOrDefault(e.key(), "None")),
                  swapGroup));
    }
    b.getChildren().add(new Label("This item: inherited rules and exceptions"));
    for (var e : product().extras()) {
      var exclude =
          action(
              "Exclude on this item",
              () -> {
                if (e.included() > 0) {
                  feedback.setText(
                      "Set included recipe quantity to zero before excluding this topping.");
                  return;
                }
                var r = item();
                var keys = new ArrayList<>(r.exclusions());
                keys.add(e.key());
                var overrides = new LinkedHashMap<>(r.extras());
                item(new ItemRule(keys, overrides, r.groups(), r.overrideGroups()));
              });
      b.getChildren()
          .add(
              new FlowPane(
                  10,
                  8,
                  new Label(
                      e.name()
                          + " · "
                          + e.origin()
                          + " · "
                          + (e.price() == null ? "extra price missing" : "R " + e.price())
                          + " · swap group: "
                          + e.swapGroup()),
                  action("Override", () -> editExtra(e)),
                  exclude));
    }
    for (String key : item().exclusions())
      b.getChildren()
          .add(
              new HBox(
                  10,
                  new Label(catalog.extras().get(key).name() + " · excluded"),
                  action(
                      "Restore inheritance",
                      () -> {
                        var r = item();
                        var keys = new ArrayList<>(r.exclusions());
                        keys.remove(key);
                        item(new ItemRule(keys, r.extras(), r.groups(), r.overrideGroups()));
                      })));
    b.getChildren().add(action("Add item-specific topping", () -> chooseExtra()));
    return scroll(b);
  }

  private javafx.scene.Node options() {
    var b = box();
    b.getChildren()
        .add(
            new Label(
                "Reusable modifier groups are inherited from the catalogue. Item overrides can set defaults, limits and replacement components."));
    for (var group : product().groups())
      b.getChildren()
          .add(
              new FlowPane(
                  10,
                  10,
                  new Label(
                      group.name()
                          + " · choose "
                          + group.min()
                          + "–"
                          + group.max()
                          + " · "
                          + group.choices().stream().map(Choice::name).toList()),
                  action("Edit group", () -> editGroup(group)),
                  action(
                      "Remove group",
                      () -> {
                        var groups = new ArrayList<>(product().groups());
                        groups.removeIf(g -> g.key().equals(group.key()));
                        var r = item();
                        item(new ItemRule(r.exclusions(), r.extras(), groups, true));
                      })));
    b.getChildren()
        .addAll(
            action("Add choice group", () -> editGroup(null)),
            action(
                "Restore catalogue groups",
                () -> {
                  var r = item();
                  item(new ItemRule(r.exclusions(), r.extras(), List.of(), false));
                }));
    return scroll(b);
  }

  private void editGroup(Group original) {
    var popup = popup("Choice group");
    var name = new TextField(original == null ? "" : original.name());
    var min = new Spinner<Integer>(0, 99, original == null ? 1 : original.min());
    var max = new Spinner<Integer>(0, 99, original == null ? 1 : original.max());
    var replaces = extraChoices(true);
    if (original != null) select(replaces, original.replacesKey());
    var choices = new ArrayList<Choice>(original == null ? List.of() : original.choices());
    var list = new ListView<String>();
    Runnable refresh =
        () ->
            list.getItems()
                .setAll(
                    choices.stream()
                        .map(
                            c ->
                                c.name()
                                    + " +R "
                                    + c.surcharge()
                                    + (c.defaultSelected() ? " [default]" : ""))
                        .toList());
    refresh.run();
    var add = new Button("Add choice");
    add.setOnAction(event -> choiceDialog(choices, refresh));
    var remove = new Button("Remove selected choice");
    remove.setOnAction(
        event -> {
          int i = list.getSelectionModel().getSelectedIndex();
          if (i >= 0) {
            choices.remove(i);
            refresh.run();
          }
        });
    popup
        .getDialogPane()
        .setContent(
            new VBox(
                10,
                new Label("Group name"),
                name,
                new HBox(10, new Label("Minimum"), min, new Label("Maximum"), max),
                new Label("Replaces the included quantity of a recipe component (optional)"),
                replaces,
                list,
                new HBox(10, add, remove)));
    finish(
        popup,
        () -> {
          var groups = new ArrayList<>(product().groups());
          String key = original == null ? UUID.randomUUID().toString() : original.key();
          groups.removeIf(g -> g.key().equals(key));
          groups.add(
              new Group(
                  key,
                  name.getText().strip(),
                  min.getValue(),
                  max.getValue(),
                  value(replaces),
                  List.copyOf(choices)));
          var r = item();
          var candidate = new ItemRule(r.exclusions(), r.extras(), groups, true);
          var old = rules;
          var map = new LinkedHashMap<>(rules.items());
          map.put(id(), candidate);
          var test =
              new Catalog(
                  catalog.revision(),
                  catalog.products(),
                  catalog.extras(),
                  catalog.categories(),
                  new Rules(rules.categoryExtras(), rules.swapGroups(), map, rules.combos()),
                  catalog.editable());
          ItemCustomization.validate(test);
          rules = old;
          item(candidate);
        });
  }

  private void choiceDialog(List<Choice> choices, Runnable refresh) {
    var parent = dialog;
    var popup = popup("Add option");
    var name = new TextField();
    var surcharge = new TextField("0.00");
    var defaultChoice = new CheckBox("Selected by default");
    var component = extraChoices(true);
    var product = productChoices(true);
    popup
        .getDialogPane()
        .setContent(
            new VBox(
                10,
                new Label("Name (e.g. Chicken or Beef)"),
                name,
                new Label("Configured surcharge (R)"),
                surcharge,
                defaultChoice,
                new Label("Replacement recipe ingredient (optional)"),
                component,
                new Label("Associated product (optional)"),
                product));
    finish(
        popup,
        () -> {
          if (name.getText().isBlank()) throw new IllegalArgumentException("Name required");
          choices.add(
              new Choice(
                  UUID.randomUUID().toString(),
                  name.getText().strip(),
                  money(surcharge.getText()),
                  value(product),
                  value(component),
                  defaultChoice.isSelected(),
                  true));
          refresh.run();
        });
    dialog = parent;
  }

  private javafx.scene.Node combos() {
    var b = box();
    var combo = product().combo();
    b.getChildren()
        .add(
            new Label(
                combo != null && combo.mode() == ItemCustomization.PricingMode.CALCULATED
                    ? "Calculated combo: selected branch prices × quantities + component customization; upgrade fees are not added."
                    : "Fixed combo price + configured replacement surcharges + component extras. Standalone component prices are not added."));
    if (combo == null) {
      b.getChildren()
          .add(
              action(
                  "Make this item a combo", () -> combo(new Combo(product().price(), List.of()))));
      return b;
    }
    var available = new CheckBox("Available at this branch");
    available.setSelected(combo.available());
    available.setOnAction(
        e -> combo(new Combo(combo.price(), combo.slots(), combo.mode(), available.isSelected())));
    b.getChildren().add(available);
    var mode = new ComboBox<ItemCustomization.PricingMode>();
    mode.getItems().setAll(ItemCustomization.PricingMode.values());
    mode.setValue(combo.mode());
    mode.setOnAction(
        e -> combo(new Combo(combo.price(), combo.slots(), mode.getValue(), combo.enabled())));
    b.getChildren()
        .addAll(
            new Label(
                "Pricing mode · calculated sums selected branch prices, without upgrade fees"),
            mode);
    var price = new TextField(combo.price().toPlainString());
    b.getChildren()
        .add(
            new HBox(
                10,
                new Label("Combo price (R)"),
                price,
                action(
                    "Apply price",
                    () -> {
                      try {
                        combo(
                            new Combo(
                                money(price.getText()),
                                combo.slots(),
                                combo.mode(),
                                combo.enabled()));
                      } catch (IllegalArgumentException ex) {
                        feedback.setText(ex.getMessage());
                      }
                    })));
    for (var slot : combo.slots())
      b.getChildren()
          .add(
              new FlowPane(
                  10,
                  10,
                  new Label(
                      slot.name()
                          + " × "
                          + slot.quantity()
                          + " · "
                          + slot.choices().size()
                          + " choices"),
                  action("Edit slot", () -> slotDialog(slot)),
                  action(
                      "Remove slot",
                      () -> {
                        var slots = new ArrayList<>(combo.slots());
                        slots.removeIf(s -> s.key().equals(slot.key()));
                        combo(new Combo(combo.price(), slots, combo.mode(), combo.enabled()));
                      })));
    b.getChildren()
        .addAll(
            action("Add component slot", () -> slotDialog(null)),
            action(
                "Use standalone product / imported combo",
                () -> {
                  var map = new LinkedHashMap<>(rules.combos());
                  map.remove(id());
                  rules = new Rules(rules.categoryExtras(), rules.swapGroups(), rules.items(), map);
                  changed();
                }));
    return scroll(b);
  }

  private void combo(Combo combo) {
    if (product().combo() == null
        && (product().extras().stream().anyMatch(e -> e.included() > 0)
            || !product().groups().isEmpty())) {
      feedback.setText(
          "Remove this item's included recipe and parent options before selecting Combo. Component recipes and options remain available.");
      return;
    }
    var map = new LinkedHashMap<>(rules.combos());
    map.put(id(), combo);
    rules = new Rules(rules.categoryExtras(), rules.swapGroups(), rules.items(), map);
    changed();
  }

  private void slotDialog(Slot original) {
    var popup = popup("Combo component slot");
    var name = new TextField(original == null ? "" : original.name());
    var quantity = new Spinner<Integer>(1, 99, original == null ? 1 : original.quantity());
    var minimum = new Spinner<Integer>(0, 99, original == null ? 1 : original.min());
    var maximum = new Spinner<Integer>(1, 99, original == null ? 1 : original.max());
    var quantities = new LinkedHashMap<UUID, Spinner<Integer>>();
    var rows = box();
    var toggles = new ToggleGroup();
    var uses = new LinkedHashMap<UUID, CheckBox>();
    var prices = new LinkedHashMap<UUID, TextField>();
    for (var p : catalog.products().values())
      if (!p.priceId().equals(id())
          && p.combo() == null
          && !rules.combos().containsKey(p.priceId())) {
        var use = new CheckBox(p.name());
        use.setSelected(original != null && original.choices().containsKey(p.priceId()));
        var price =
            new TextField(
                original == null
                    ? "0.00"
                    : original
                        .choices()
                        .getOrDefault(p.priceId(), BigDecimal.ZERO)
                        .toPlainString());
        price.setPrefWidth(85);
        var def = new RadioButton("Default selection");
        def.setUserData(p.priceId());
        def.setToggleGroup(toggles);
        def.setSelected(original != null && p.priceId().equals(original.defaultPriceId()));
        var entryQuantity =
            new Spinner<Integer>(1, 99, original == null ? 1 : original.quantityFor(p.priceId()));
        quantities.put(p.priceId(), entryQuantity);
        rows.getChildren()
            .add(
                new HBox(
                    10,
                    use,
                    new Label("Quantity"),
                    entryQuantity,
                    new Label("Upgrade R"),
                    price,
                    def));
        uses.put(p.priceId(), use);
        prices.put(p.priceId(), price);
      }
    var none = new Button("Require a choice (no default)");
    none.setOnAction(e -> toggles.selectToggle(null));
    var pane = scroll(rows);
    pane.setPrefViewportHeight(320);
    popup
        .getDialogPane()
        .setContent(
            new VBox(
                10,
                new Label("Slot name (e.g. Burger, Chips, Drink)"),
                name,
                new Label("Set the quantity alongside each permitted product."),
                new Label("Minimum / maximum selected entries (0 minimum makes this optional)"),
                new HBox(10, minimum, maximum),
                none,
                pane));
    finish(
        popup,
        () -> {
          var allowed = new LinkedHashMap<UUID, BigDecimal>();
          uses.forEach(
              (id, use) -> {
                if (use.isSelected()) allowed.put(id, money(prices.get(id).getText()));
              });
          UUID def =
              toggles.getSelectedToggle() == null
                  ? null
                  : (UUID) toggles.getSelectedToggle().getUserData();
          if (name.getText().isBlank()
              || allowed.isEmpty()
              || def != null && !allowed.containsKey(def))
            throw new IllegalArgumentException(
                "Name, allowed products and a valid default are required");
          if (product().combo().mode() == ItemCustomization.PricingMode.FIXED
              && def != null
              && allowed.get(def).signum() != 0)
            throw new IllegalArgumentException("The included default must have zero surcharge");
          var slots = new ArrayList<>(product().combo().slots());
          String key = original == null ? UUID.randomUUID().toString() : original.key();
          slots.removeIf(s -> s.key().equals(key));
          if (minimum.getValue() > maximum.getValue() || maximum.getValue() > allowed.size())
            throw new IllegalArgumentException("Selection limits must fit the allowed choices");
          var counts = new LinkedHashMap<UUID, Integer>();
          allowed.keySet().forEach(id -> counts.put(id, quantities.get(id).getValue()));
          slots.add(
              new Slot(
                  key,
                  name.getText().strip(),
                  quantity.getValue(),
                  def,
                  allowed,
                  minimum.getValue(),
                  maximum.getValue(),
                  counts));
          combo(
              new Combo(
                  product().combo().price(),
                  slots,
                  product().combo().mode(),
                  product().combo().enabled()));
        });
  }

  private javafx.scene.Node builder() {
    var b = box();
    quantity = new Spinner<Integer>(1, 999, 1);
    quantity.setId("item-quantity");
    selectionForm = new SelectionForm(product());
    b.getChildren()
        .addAll(
            new Label("Order preview — select required options, removals, swaps and extras"),
            quantity,
            selectionForm.node);
    var preview = new Button("Calculate staged preview");
    preview.setId("item-preview");
    preview.setOnAction(
        e -> {
          try {
            var q = ItemCustomization.quote(staged(), request());
            showQuote(q);
            feedback.setText(
                dirty
                    ? "Unsaved preview only. Save settings and obtain a Master quote before adding to a draft."
                    : "Local preview only. Obtain a Master quote before adding to a draft.");
          } catch (IllegalArgumentException failure) {
            feedback.setText(failure.getMessage());
          }
        });
    var confirm = new Button("Quote from Master");
    confirm.setId("item-quote");
    confirm.setOnAction(
        e -> {
          if (dirty) {
            feedback.setText("Save settings before requesting a confirmed quote.");
            return;
          }
          try {
            var request = request();
            work(
                () ->
                    JsonFiles.JSON.treeToValue(
                        gateway.catalogue("items/quote", "POST", request, token), Quote.class),
                q -> {
                  quote = q;
                  confirmedRequest = request;
                  showQuote(q);
                  feedback.setText(
                      "Quote confirmed by the Master. Prices are checked again when the draft is saved.");
                });
          } catch (IllegalArgumentException failure) {
            feedback.setText(failure.getMessage());
          }
        });
    add.setId("item-add-draft");
    add.setOnAction(
        e -> {
          if (quote == null || confirmedRequest == null || !confirmedRequest.equals(request())) {
            feedback.setText("Obtain a fresh Master quote for these exact selections first.");
            return;
          }
          if (pendingDraft != null) {
            feedback.setText("Retry the unconfirmed draft before changing the cart.");
            return;
          }
          cart.add(
              new LocalCommands.DraftLine(
                  UUID.randomUUID(),
                  quote.priceId(),
                  quote.priceRevision(),
                  quote.quantity(),
                  quote.selection()));
          cartView
              .getItems()
              .add(quote.name() + " × " + quote.quantity() + " — R " + displayMoney(quote.total()));
        });
    breakdown.setId("item-breakdown");
    breakdown.setEditable(false);
    breakdown.setPrefRowCount(7);
    var customer = new javafx.scene.control.ComboBox<com.fasterxml.jackson.databind.JsonNode>();
    customer.setId("draft-customer");
    var walkIn =
        za.co.onlinepos.installation.JsonFiles.JSON
            .createObjectNode()
            .put("id", "")
            .put("name", "Anonymous walk-in");
    customer.getItems().add(walkIn);
    customer.setValue(walkIn);
    customer.setConverter(
        new javafx.util.StringConverter<>() {
          @Override
          public String toString(com.fasterxml.jackson.databind.JsonNode value) {
            return value == null ? "Anonymous walk-in" : value.path("name").asText();
          }

          @Override
          public com.fasterxml.jackson.databind.JsonNode fromString(String value) {
            return null;
          }
        });
    var customerSearch = new javafx.scene.control.TextField();
    customerSearch.setPromptText("Find customer by name or phone");
    customerSearch.setId("draft-customer-search");
    var findCustomer = new Button("Find customer");
    findCustomer.setId("draft-find-customer");
    findCustomer.setOnAction(
        event ->
            work(
                () ->
                    gateway.customers(
                        "?query="
                            + java.net.URLEncoder.encode(
                                customerSearch.getText(), java.nio.charset.StandardCharsets.UTF_8),
                        "GET",
                        null,
                        token),
                result -> {
                  customer.getItems().setAll(walkIn);
                  result
                      .path("items")
                      .forEach(
                          row -> {
                            if (!row.path("archived").asBoolean()
                                && !row.path("banned").asBoolean()) customer.getItems().add(row);
                          });
                  customer.setValue(walkIn);
                }));
    persist.setId("item-save-draft");
    persist.setDisable(gateway instanceof PreviewGateway);
    persist.setOnAction(
        e -> {
          if (cart.isEmpty()) return;
          if (dirty && pendingDraft == null) {
            feedback.setText("Save or discard staged item settings before saving this draft.");
            return;
          }
          if (pendingDraft == null)
            pendingDraft =
                new LocalCommands.CreateDraft(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    List.copyOf(cart),
                    customer.getValue() == null || customer.getValue().path("id").asText().isBlank()
                        ? null
                        : UUID.fromString(customer.getValue().path("id").asText()));
          var request = pendingDraft;
          work(
              () -> gateway.catalogue("items/drafts", "POST", request, token),
              r -> {
                pendingDraft = null;
                cart.clear();
                cartView.getItems().clear();
                feedback.setText(
                    "Draft saved on Master: "
                        + r.path("reference").asText()
                        + ". This is not a completed sale.");
              });
        });
    clear.setOnAction(
        e -> {
          if (pendingDraft != null) {
            feedback.setText(
                "An unconfirmed save is pending. Retry it before starting another draft.");
            return;
          }
          cart.clear();
          cartView.getItems().clear();
        });
    cartView.setPrefHeight(130);
    b.getChildren()
        .addAll(
            new FlowPane(10, 10, preview, confirm, add),
            breakdown,
            new FlowPane(10, 10, customerSearch, findCustomer, customer),
            new Label("Draft cart — no checkout or payments"),
            cartView,
            new HBox(10, persist, clear));
    return scroll(b);
  }

  private QuoteRequest request() {
    return new QuoteRequest(
        id(), product().priceRevision(), quantity.getValue(), selectionForm.selection());
  }

  private static String displayMoney(BigDecimal amount) {
    return amount.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
  }

  private void showQuote(Quote q) {
    var lines = new StringBuilder(q.name() + "\nBase R " + displayMoney(q.basePrice()) + "\n");
    for (var charge : q.charges())
      lines
          .append(charge.label())
          .append(": R ")
          .append(displayMoney(charge.amount()))
          .append('\n');
    lines
        .append("Unit R ")
        .append(displayMoney(q.unitPrice()))
        .append(" · Quantity ")
        .append(q.quantity())
        .append(" · Total R ")
        .append(displayMoney(q.total()));
    breakdown.setText(lines.toString());
  }

  private final class SelectionForm {
    final VBox node = box();
    final Map<String, Spinner<Integer>> removals = new LinkedHashMap<>(),
        additions = new LinkedHashMap<>();
    final List<Swap> swaps = new ArrayList<>();
    final Map<String, List<CheckBox>> choices = new LinkedHashMap<>();
    final Map<String, CheckBox> componentChecks = new LinkedHashMap<>();
    final List<String> componentSlotKeys = new ArrayList<>();
    final Map<String, SelectionForm> componentForms = new LinkedHashMap<>();

    SelectionForm(Product p) {
      if (p.combo() != null) {
        for (var slot : p.combo().slots()) {
          componentSlotKeys.add(slot.key());
          node.getChildren()
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
            var form = new SelectionForm(ItemCustomization.effective(staged(), product));
            componentForms.put(key, form);
            var customize =
                new javafx.scene.control.TitledPane("Customize " + product.name(), form.node);
            customize.setExpanded(false);
            customize.disableProperty().bind(check.selectedProperty().not());
            node.getChildren().addAll(check, customize);
          }
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
        if (extra.included() > 0 && !replaced.contains(extra.key())) {
          var remove = new Spinner<Integer>(0, extra.removable() ? extra.included() : 0, 0);
          removals.put(extra.key(), remove);
          node.getChildren()
              .add(
                  new HBox(
                      10,
                      new Label(extra.name() + " · included " + extra.included() + " · remove"),
                      remove));
        }
        if (extra.available() && extra.price() != null) {
          var amount = new Spinner<Integer>(0, 99, 0);
          additions.put(extra.key(), amount);
          node.getChildren()
              .add(
                  new HBox(
                      10,
                      new Label("Extra " + extra.name() + " +R " + extra.price() + " each"),
                      amount));
        }
      }
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

    Selection selection() {
      var remove = new LinkedHashMap<String, Integer>();
      removals.forEach(
          (k, v) -> {
            if (v.getValue() > 0) remove.put(k, v.getValue());
          });
      var extra = new LinkedHashMap<String, Integer>();
      additions.forEach(
          (k, v) -> {
            if (v.getValue() > 0) extra.put(k, v.getValue());
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

  private ComboBox<Named<String>> extraChoices(boolean none) {
    var box = new ComboBox<Named<String>>();
    if (none) box.getItems().add(new Named<>(null, "None"));
    product().extras().forEach(e -> box.getItems().add(new Named<>(e.key(), e.name())));
    return box;
  }

  private ComboBox<Named<UUID>> productChoices(boolean none) {
    var box = new ComboBox<Named<UUID>>();
    if (none) box.getItems().add(new Named<>(null, "None"));
    catalog.products().values().stream()
        .filter(p -> p.combo() == null && !rules.combos().containsKey(p.priceId()))
        .forEach(p -> box.getItems().add(new Named<>(p.priceId(), p.name())));
    return box;
  }

  private static <T> T value(ComboBox<Named<T>> box) {
    return box.getValue() == null ? null : box.getValue().value();
  }

  private static <T> void select(ComboBox<Named<T>> box, T value) {
    for (var choice : box.getItems())
      if (java.util.Objects.equals(choice.value(), value)) {
        box.getSelectionModel().select(choice);
        return;
      }
  }

  private static BigDecimal money(String text) {
    try {
      return ItemCustomization.money(new BigDecimal(text.strip()));
    } catch (ArithmeticException | NumberFormatException e) {
      throw new IllegalArgumentException(
          "Enter a nonnegative amount with at most two decimal places");
    }
  }

  private Dialog<ButtonType> popup(String name) {
    var popup = new Dialog<ButtonType>();
    dialog = popup;
    popup.setTitle(name);
    popup.setResizable(true);
    popup.initOwner(getScene().getWindow());
    popup.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    return popup;
  }

  private void finish(Dialog<ButtonType> popup, Runnable accept) {
    var error = new Label();
    var content = popup.getDialogPane().getContent();
    popup.getDialogPane().setContent(new VBox(10, content, error));
    popup
        .getDialogPane()
        .lookupButton(ButtonType.OK)
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            event -> {
              try {
                accept.run();
              } catch (IllegalArgumentException failure) {
                event.consume();
                error.setText(failure.getMessage());
              }
            });
    popup.showAndWait();
    dialog = null;
  }

  boolean confirmClose() {
    if (busy) {
      feedback.setText("Wait for the current request to finish.");
      return false;
    }
    return !dirty()
        || new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.CONFIRMATION,
                    "Discard staged settings and the unsaved draft cart?",
                    ButtonType.OK,
                    ButtonType.CANCEL)
                .showAndWait()
                .orElse(ButtonType.CANCEL)
            == ButtonType.OK;
  }

  boolean dirty() {
    return dirty || pendingDraft != null || !cart.isEmpty();
  }

  @Override
  public void close() {
    closed = true;
    if (dialog != null) dialog.close();
    tabs.getTabs().clear();
    cart.clear();
    catalog = null;
    rules = null;
  }
}
