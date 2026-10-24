package za.co.onlinepos.desktop;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import za.co.onlinepos.contracts.Branch;
import za.co.onlinepos.contracts.InstallationRole;

final class SetupWizard extends BorderPane {
  private final ComboBox<Branch> branch = new ComboBox<>();
  private final TextField terminalName = new TextField();
  private final RadioButton master = new RadioButton("Master — hosts the store service");
  private final RadioButton slave = new RadioButton("Slave — connects to the store Master");
  private final Label validation = new Label();
  private final Button review = new Button("Review setup");
  private final Label summary = new Label();
  private final VBox form;

  private final za.co.onlinepos.desktop.setup.SetupPresenter presenter;

  SetupWizard(za.co.onlinepos.desktop.setup.SetupPresenter presenter) {
    this.presenter = presenter;
    setPadding(new Insets(32));
    getStyleClass().add("wizard");
    Label brand = new Label("ONLINEPOS");
    brand.getStyleClass().add("brand");
    Label title = new Label("Set up your store terminal");
    title.getStyleClass().add("title");
    Label introduction =
        new Label("Choose where this terminal belongs and how it will connect to your store.");
    introduction.setWrapText(true);
    VBox heading = new VBox(12, brand, title, introduction);
    heading.setPadding(new Insets(0, 0, 28, 0));
    setTop(heading);

    branch.getItems().setAll(Branch.values());
    branch.setPromptText("Select a branch");
    branch.setMaxWidth(Double.MAX_VALUE);
    branch.setConverter(
        new StringConverter<>() {
          @Override
          public String toString(Branch value) {
            return value == null ? "" : value.displayName();
          }

          @Override
          public Branch fromString(String value) {
            throw new UnsupportedOperationException("Select an existing branch");
          }
        });
    terminalName.setPromptText("For example: Front counter 1");
    ToggleGroup roles = new ToggleGroup();
    master.setToggleGroup(roles);
    slave.setToggleGroup(roles);
    validation.getStyleClass().add("validation");
    validation.setWrapText(true);
    review.getStyleClass().add("primary");
    review.setDefaultButton(true);
    review.setOnAction(event -> reviewSelection());
    form =
        new VBox(
            14,
            fieldLabel("Branch", branch),
            branch,
            fieldLabel("Terminal name", terminalName),
            terminalName,
            new Label("Installation role"),
            master,
            slave,
            validation,
            review);
    form.getStyleClass().add("card");
    setCenter(form);
    Label notice = new Label("Secure terminal setup · Sales are not available in this release.");
    notice.setWrapText(true);
    notice.getStyleClass().add("notice");
    BorderPane.setMargin(notice, new Insets(22, 0, 0, 0));
    setBottom(notice);
    presenter
        .currentInstallation()
        .whenComplete(
            (state, error) ->
                javafx.application.Platform.runLater(
                    () -> {
                      if (error != null) {
                        notice.setText(
                            "Installation state could not be read. Use protected repair diagnostics.");
                        return;
                      }
                      state.ifPresent(
                          saved -> {
                            branch.setValue(Branch.fromId(saved.branchId()));
                            terminalName.setText(saved.terminalName());
                            master.setSelected(saved.role() == InstallationRole.MASTER);
                            slave.setSelected(saved.role() == InstallationRole.SLAVE);
                            branch.setDisable(true);
                            master.setDisable(true);
                            slave.setDisable(true);
                            notice.setText(
                                "Installation: "
                                    + saved.role()
                                    + " · "
                                    + saved.state()
                                    + " · Sales unavailable. Role changes require protected administration.");
                            if (saved.role() == InstallationRole.BACKUP_MASTER)
                              review.setDisable(true);
                          });
                    }));
  }

  private Label fieldLabel(String text, javafx.scene.Node control) {
    Label label = new Label(text);
    label.setLabelFor(control);
    return label;
  }

  private void reviewSelection() {
    if (branch.getValue() == null || (!master.isSelected() && !slave.isSelected())) {
      validation.setText("Select your branch and installation role before continuing.");
      return;
    }
    try {
      SetupSelection selection =
          new SetupSelection(
              branch.getValue(),
              master.isSelected() ? InstallationRole.MASTER : InstallationRole.SLAVE,
              terminalName.getText());
      summary.setText(
          selection.branch().displayName()
              + "\n"
              + selection.terminalName()
              + "\n"
              + (selection.role() == InstallationRole.MASTER ? "Master" : "Slave"));
      summary.getStyleClass().add("summary");
      javafx.scene.control.PasswordField administrator = new javafx.scene.control.PasswordField();
      administrator.setPromptText("Installation administrator password (5+ characters)");
      javafx.scene.control.TextArea invitation = new javafx.scene.control.TextArea();
      invitation.setPromptText("Slave: paste the pairing invitation JSON from your Master");
      javafx.scene.control.TextField masterAddress = new javafx.scene.control.TextField();
      masterAddress.setPromptText(
          "Optional manual HTTPS Master address (same trusted certificate required)");
      masterAddress.setVisible(selection.role() == InstallationRole.SLAVE);
      masterAddress.setManaged(masterAddress.isVisible());
      invitation.setPrefRowCount(3);
      invitation.setVisible(selection.role() == InstallationRole.SLAVE);
      invitation.setManaged(invitation.isVisible());
      Button discover = new Button("Discover Masters");
      Label progress = new Label();
      progress.setWrapText(true);
      discover.setOnAction(
          event -> {
            discover.setDisable(true);
            presenter
                .discover()
                .whenComplete(
                    (result, error) ->
                        javafx.application.Platform.runLater(
                            () -> {
                              progress.setText(
                                  error == null
                                      ? (result.isBlank()
                                          ? "No candidates found; enter the trusted Master address in the invitation."
                                          : "Unverified candidates:\n" + result)
                                      : "Discovery unavailable; use a trusted invitation with a manual address.");
                              discover.setDisable(false);
                            }));
          });
      Button install = new Button("Install / resume setup");
      install.getStyleClass().add("primary");
      install.setOnAction(
          event -> {
            char[] password = administrator.getText().toCharArray();
            administrator.clear();
            if (password.length < 5) {
              java.util.Arrays.fill(password, '\0');
              progress.setText("Enter an administrator password of at least 5 characters.");
              return;
            }
            install.setDisable(true);
            progress.setText("Waiting for Windows administrator consent and setup checks…");
            presenter
                .install(
                    new za.co.onlinepos.installation.SetupRequest(
                        selection.branch().id(),
                        selection.role(),
                        selection.terminalName(),
                        invitation.getText(),
                        masterAddress.getText()),
                    password)
                .whenComplete(
                    (result, error) ->
                        javafx.application.Platform.runLater(
                            () -> {
                              install.setDisable(false);
                              progress.setText(
                                  error == null
                                      ? "Installation recorded. Sales remain unavailable. " + result
                                      : "Setup did not complete: "
                                          + (error.getCause() == null
                                              ? error.getMessage()
                                              : error.getCause().getMessage()));
                            }));
          });
      Label explanation =
          new Label(
              selection.role() == InstallationRole.MASTER
                  ? "Setup creates a dedicated PostgreSQL cluster, database identities, HTTPS certificates, Windows backend/database services and Private-network firewall rules. Existing data is preserved. Windows will request administrator consent."
                  : "Setup registers this terminal with the invited Master and stores protected terminal credentials. It installs no database or backend service. Windows will request administrator consent.");
      explanation.setWrapText(true);
      Button back = new Button("Back to setup");
      back.setOnAction(event -> setCenter(form));
      VBox confirmation =
          new VBox(
              12,
              new Label("Review your terminal"),
              summary,
              explanation,
              administrator,
              invitation,
              masterAddress,
              discover,
              install,
              progress,
              back);
      confirmation.getStyleClass().add("card");
      javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(confirmation);
      scroll.setFitToWidth(true);
      setCenter(scroll);
      validation.setText("");
    } catch (IllegalArgumentException exception) {
      validation.setText(exception.getMessage());
    }
  }

  void verifySmokeScenario() {
    review.fire();
    if (validation.getText().isBlank() || getCenter() != form) {
      throw new IllegalStateException("Incomplete setup must not proceed");
    }
    branch.setValue(Branch.UITZICHT);
    slave.setSelected(true);
    terminalName.setText("Smoke terminal");
    review.fire();
    if (getCenter() == form || !summary.getText().equals("Uitzicht\nSmoke terminal\nSlave")) {
      throw new IllegalStateException("Setup review did not preserve branch and role");
    }
  }
}
