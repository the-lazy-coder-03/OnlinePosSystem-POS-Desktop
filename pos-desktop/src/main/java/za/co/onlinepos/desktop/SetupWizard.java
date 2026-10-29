package za.co.onlinepos.desktop;

import java.util.List;
import java.util.UUID;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import za.co.onlinepos.contracts.InstallationRole;
import za.co.onlinepos.desktop.setup.SetupPresenter;
import za.co.onlinepos.installation.Installation;
import za.co.onlinepos.installation.LanPolicy;
import za.co.onlinepos.installation.MasterSelection;
import za.co.onlinepos.installation.SetupRequest;
import za.co.onlinepos.installation.StoreProfile;

/**
 * Guided local installation; store identities are created or obtained from the selected main PC.
 */
final class SetupWizard extends BorderPane {
  private final SetupPresenter presenter;
  private Runnable installedCallback = () -> {};
  private final RadioButton main = new RadioButton("Main computer"),
      till = new RadioButton("Additional till");
  private final TextField store = new TextField(),
      business = new TextField(),
      terminal = new TextField(),
      address = new TextField();
  private final PasswordField password = new PasswordField();
  private final ComboBox<LanPolicy> networks = new ComboBox<>();
  private final ComboBox<MasterSelection> masters = new ComboBox<>();
  private final Label progress = new Label(), stepLabel = new Label(), summary = new Label();
  private final Button next = new Button("Continue"),
      back = new Button("Back"),
      find = new Button("Find main computers");
  private final VBox content = new VBox(14);
  private final String generatedCode =
      "STORE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
  private Installation existing;
  private MasterSelection selected;
  private int step = 1;
  private long lookupGeneration;
  private boolean loading = true, busy, networksLoaded, scanning, checking;

  SetupWizard(SetupPresenter presenter) {
    this.presenter = presenter;
    setPadding(new Insets(32));
    getStyleClass().add("wizard");
    var brand = new Label("ONLINEPOS");
    brand.getStyleClass().add("brand");
    var title = new Label("Set up this computer");
    title.getStyleClass().add("title");
    var help = new Button("Help");
    help.setId("setup-help");
    help.setOnAction(e -> help());
    var spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    setTop(new VBox(12, new HBox(12, brand, spacer, help), title, stepLabel));
    content.getStyleClass().add("card");
    var scroll = new ScrollPane(content);
    scroll.setFitToWidth(true);
    BorderPane.setMargin(scroll, new Insets(24, 0, 20, 0));
    setCenter(scroll);
    progress.setWrapText(true);
    progress.setId("setup-feedback");
    summary.setWrapText(true);
    next.setId("setup-next");
    next.getStyleClass().add("primary");
    next.setDefaultButton(true);
    back.setId("setup-back");
    back.setOnAction(
        e -> {
          if (!busy) {
            password.clear();
            step--;
            render();
          }
        });
    next.setOnAction(e -> advance());
    setBottom(new VBox(12, progress, new HBox(12, back, next)));
    var roles = new ToggleGroup();
    main.setToggleGroup(roles);
    till.setToggleGroup(roles);
    main.setId("setup-main");
    till.setId("setup-till");
    roles
        .selectedToggleProperty()
        .addListener(
            (o, a, b) -> {
              selected = null;
              lookupGeneration++;
            });
    terminal.setText(computerName());
    terminal.setId("terminal-name");
    store.setId("setup-store-name");
    business.setId("setup-business-name");
    address.setId("master-lan-ip");
    password.setId("setup-password");
    networks.setId("store-network");
    masters.setId("setup-masters");
    store.setPromptText("For example: Oak Street Pizza");
    business.setPromptText("Defaults to the store name");
    address.setPromptText("For example: 192.168.1.20");
    networks.setMaxWidth(Double.MAX_VALUE);
    masters.setMaxWidth(Double.MAX_VALUE);
    networks.setConverter(
        new StringConverter<>() {
          public String toString(LanPolicy n) {
            return n == null ? "" : n.adapterName() + " · " + n.address();
          }

          public LanPolicy fromString(String s) {
            throw new UnsupportedOperationException();
          }
        });
    networks
        .valueProperty()
        .addListener(
            (o, a, b) -> {
              selected = null;
              masters.getItems().clear();
              lookupGeneration++;
              if (step == 2 && till.isSelected() && b != null) discover();
              updateButtons();
            });
    masters.setOnAction(
        e -> {
          if (masters.getValue() != null) {
            selected = masters.getValue();
            progress.setText("Selected " + selected);
            updateButtons();
          }
        });
    address
        .textProperty()
        .addListener(
            (o, a, b) -> {
              selected = null;
              lookupGeneration++;
              updateButtons();
            });
    find.setOnAction(e -> discover());
    render();
    progress.setText("Checking this computer…");
    presenter
        .currentInstallation()
        .whenComplete(
            (saved, error) ->
                Platform.runLater(
                    () -> {
                      if (error != null) {
                        progress.setText(
                            "Cannot read setup. Reopen the app or use installation diagnostics.");
                        return;
                      }
                      loading = false;
                      saved.ifPresent(
                          value -> {
                            existing = value;
                            main.setSelected(value.role() == InstallationRole.MASTER);
                            till.setSelected(value.role() == InstallationRole.SLAVE);
                            main.setDisable(true);
                            till.setDisable(true);
                            terminal.setText(value.terminalName());
                            terminal.setDisable(true);
                            store.setText(
                                value.storeProfile() == null
                                    ? "Store " + value.branchId()
                                    : value.storeProfile().storeName());
                            business.setText(
                                value.storeProfile() == null
                                    ? ""
                                    : value.storeProfile().businessName());
                            store.setDisable(true);
                            business.setDisable(true);
                          });
                      progress.setText(
                          existing == null
                              ? ""
                              : "Continue setup for this computer. Existing store details are preserved.");
                      updateButtons();
                    }));
  }

  void onInstalled(Runnable callback) {
    installedCallback = callback;
  }

  private static String computerName() {
    try {
      String name = java.net.InetAddress.getLocalHost().getHostName();
      if (!name.isBlank()
          && name.length() <= 64
          && name.codePoints().noneMatch(Character::isISOControl)) return name;
    } catch (Exception ignored) {
      /* A user can name a computer without DNS. */
    }
    return "Till";
  }

  private Node field(String name, Node input) {
    var label = new Label(name);
    label.setLabelFor(input);
    return new VBox(5, label, input);
  }

  private void render() {
    content.getChildren().clear();
    stepLabel.setText("Step " + step + " of 3");
    progress.setText("");
    next.setId(step == 3 ? "setup-connect" : "setup-next");
    next.setText(step == 3 ? (main.isSelected() ? "Set up store" : "Connect till") : "Continue");
    if (step == 1) {
      content
          .getChildren()
          .addAll(
              new Label("How will this computer be used?"),
              main,
              new Label("Run this store’s local system."),
              till,
              new Label("Connect to this store’s main computer."));
    } else if (step == 2) {
      var options = new VBox(12);
      if (main.isSelected()) {
        content.getChildren().add(field("Store name", store));
        addNetworkChoice();
        options.getChildren().add(field("Business name (optional)", business));
      } else {
        addNetworkChoice();
        content.getChildren().addAll(new Label("Choose your store’s main computer"), masters, find);
        var check = new Button("Check main computer");
        check.setId("setup-check-master");
        check.disableProperty().bind(address.textProperty().isEmpty());
        check.setOnAction(e -> inspect());
        var manual =
            new TitledPane(
                "Enter address manually",
                new VBox(10, field("Main computer address", address), check));
        manual.setId("setup-manual-address");
        manual.setExpanded(false);
        content.getChildren().add(manual);
      }
      options.getChildren().add(field("Terminal name", terminal));
      var more = new TitledPane("More options", options);
      more.setId("setup-more-options");
      more.setExpanded(false);
      content.getChildren().add(more);
      loadNetworks();
      if (till.isSelected() && networks.getValue() != null && selected == null) discover();
    } else {
      summary.setText(
          (main.isSelected() ? store.getText().strip() : selected.toString())
              + "\n"
              + (main.isSelected() ? "Main computer" : "Additional till")
              + " · "
              + terminal.getText().strip());
      content.getChildren().addAll(new Label("Ready to finish"), summary);
      if (main.isSelected()) {
        password.setPromptText("At least 5 characters");
        content.getChildren().add(field("Installation administrator password", password));
        addNetworkChoice();
      }
      var consent = new Label("Windows may ask for administrator permission to finish setup.");
      consent.setWrapText(true);
      content.getChildren().add(consent);
    }
    updateButtons();
  }

  private void addNetworkChoice() {
    if (networks.getItems().size() > 1) content.getChildren().add(field("Store network", networks));
  }

  private void loadNetworks() {
    if (networksLoaded) return;
    networksLoaded = true;
    progress.setText("Checking store network…");
    presenter
        .networks()
        .whenComplete(
            (choices, error) ->
                Platform.runLater(
                    () -> {
                      if (error != null || choices.isEmpty()) {
                        networksLoaded = false;
                        progress.setText(
                            "No store network found. Check that Ethernet or Wi-Fi is set to Private in Windows, then go Back and retry.");
                        updateButtons();
                        return;
                      }
                      networks.getItems().setAll(choices);
                      if (choices.size() == 1) networks.setValue(choices.getFirst());
                      if (step > 1) render();
                    }));
  }

  private void discover() {
    if (scanning || networks.getValue() == null || !till.isSelected()) return;
    scanning = true;
    long attempt = lookupGeneration;
    LanPolicy network = networks.getValue();
    find.setDisable(true);
    progress.setText("Looking for main computers…");
    presenter
        .findMasters(network)
        .whenComplete(
            (choices, error) ->
                Platform.runLater(
                    () -> {
                      scanning = false;
                      find.setDisable(false);
                      if (attempt != lookupGeneration || step != 2 || !till.isSelected()) return;
                      masters.getItems().setAll(error == null ? choices : List.of());
                      progress.setText(
                          error != null || choices.isEmpty()
                              ? "No main computers found. Enter the address manually or search again."
                              : "Select the main computer for your store.");
                    }));
  }

  private void inspect() {
    if (checking || networks.getValue() == null) {
      progress.setText("Select your store network first.");
      return;
    }
    selected = null;
    checking = true;
    updateButtons();
    long attempt = ++lookupGeneration;
    progress.setText("Checking main computer…");
    presenter
        .inspectMaster(address.getText().strip(), networks.getValue())
        .whenComplete(
            (value, error) ->
                Platform.runLater(
                    () -> {
                      checking = false;
                      if (attempt != lookupGeneration || step != 2) {
                        updateButtons();
                        return;
                      }
                      if (error != null)
                        progress.setText(
                            "Cannot connect. Check the main computer is running and the address is on your store network.");
                      else {
                        selected = value;
                        progress.setText("Selected " + value);
                      }
                      updateButtons();
                    }));
  }

  private void advance() {
    if (loading || busy) return;
    progress.setText("");
    if (step == 1) {
      if (!main.isSelected() && !till.isSelected()) {
        progress.setText("Choose Main computer or Additional till.");
        return;
      }
      step = 2;
      render();
      return;
    }
    if (step == 2) {
      try {
        new SetupSelection(
            new za.co.onlinepos.contracts.Branch(
                existing == null ? 1 : existing.branchId(),
                main.isSelected() ? store.getText() : "Selected store"),
            main.isSelected() ? InstallationRole.MASTER : InstallationRole.SLAVE,
            terminal.getText());
        if (main.isSelected() && existing == null) profile();
      } catch (IllegalArgumentException error) {
        progress.setText(error.getMessage());
        return;
      }
      if (networks.getValue() == null) {
        progress.setText("Select your store network before continuing.");
        return;
      }
      if (till.isSelected() && selected == null) {
        progress.setText("Choose or check a main computer first.");
        return;
      }
      step = 3;
      render();
      return;
    }
    install();
  }

  private StoreProfile profile() {
    if (existing != null) return existing.storeProfile();
    return new StoreProfile(
        store.getText(),
        business.getText().isBlank() ? store.getText() : business.getText(),
        generatedCode);
  }

  private void install() {
    char[] secret = password.getText().toCharArray();
    password.clear();
    if (main.isSelected() && secret.length < 5) {
      java.util.Arrays.fill(secret, '\0');
      progress.setText("Enter an administrator password of at least 5 characters.");
      return;
    }
    busy = true;
    updateButtons();
    content.setDisable(true);
    progress.setText("Finishing setup. Check for a Windows permission prompt.");
    var request =
        new SetupRequest(
            main.isSelected() ? (existing == null ? 1 : existing.branchId()) : selected.branchId(),
            main.isSelected() ? InstallationRole.MASTER : InstallationRole.SLAVE,
            terminal.getText().strip(),
            "",
            main.isSelected() ? "" : java.net.URI.create(selected.origin()).getHost(),
            networks.getValue(),
            main.isSelected() ? profile() : null,
            main.isSelected() ? null : selected);
    presenter
        .install(request, secret)
        .whenComplete(
            (result, error) ->
                Platform.runLater(
                    () -> {
                      java.util.Arrays.fill(secret, '\0');
                      busy = false;
                      content.setDisable(false);
                      updateButtons();
                      if (error == null) {
                        progress.setText("Setup complete.");
                        installedCallback.run();
                      } else
                        progress.setText(
                            "Setup did not finish. Your details are kept. Check the connection and Windows permission prompt, then try again. More help is available in installation diagnostics.");
                    }));
  }

  private void updateButtons() {
    back.setVisible(step > 1);
    back.setManaged(step > 1);
    back.setDisable(busy);
    next.setDisable(
        loading
            || busy
            || checking
            || (existing != null && existing.role() == InstallationRole.BACKUP_MASTER));
  }

  private void help() {
    var alert = new Alert(Alert.AlertType.INFORMATION);
    alert.setTitle("Setup Help");
    alert.setHeaderText("Set up one store at a time");
    alert.setContentText(
        "Main computer: creates this store’s local database and services. Enter any store name. IDs and a store code are generated automatically. Each store has its own main computer.\n\nAdditional till: connects to the main computer on the same private network and inherits its store. Check the displayed store before connecting. First connection trusts that selected computer; existing certificate trust cannot be replaced.\n\nMore options lets you change the terminal name and optional business name before first setup. The installation administrator password protects installation management; it is separate from staff PINs. Windows may request administrator permission to create services, certificates and private-network firewall rules.\n\nSetup can resume after interruption without changing existing store identities. Developer PIN recovery requires separate deliberate enrolment in Administration → System Access.");
    alert.getDialogPane().setPrefWidth(620);
    if (getScene() != null) alert.initOwner(getScene().getWindow());
    alert.showAndWait();
  }

  void verifySmokeScenario() {
    next.fire();
    if (progress.getText().isBlank() || step != 1)
      throw new IllegalStateException("Incomplete setup must not proceed");
    main.setSelected(true);
    next.fire();
    store.setText("Oak Street Pizza");
    terminal.setText("Smoke terminal");
    if (networks.getValue() == null)
      networks.getItems().setAll(new LanPolicy("test", "Store network", "192.168.1.10", 24));
    if (networks.getValue() == null) networks.setValue(networks.getItems().getFirst());
    next.fire();
    if (step != 3 || !summary.getText().contains("Oak Street Pizza"))
      throw new IllegalStateException("Store setup review failed");
  }
}
