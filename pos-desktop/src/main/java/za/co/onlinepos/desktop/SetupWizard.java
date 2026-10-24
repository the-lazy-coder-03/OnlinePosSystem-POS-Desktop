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

  SetupWizard() {
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
    Label notice = new Label("Setup preview · Installation and sales are not available yet.");
    notice.setWrapText(true);
    notice.getStyleClass().add("notice");
    BorderPane.setMargin(notice, new Insets(22, 0, 0, 0));
    setBottom(notice);
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
      Label explanation =
          new Label("Your selection is ready to review. Nothing has been installed or saved.");
      explanation.setWrapText(true);
      Button back = new Button("Back to setup");
      back.setOnAction(event -> setCenter(form));
      VBox confirmation =
          new VBox(20, new Label("Review your terminal"), summary, explanation, back);
      confirmation.getStyleClass().add("card");
      setCenter(confirmation);
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
