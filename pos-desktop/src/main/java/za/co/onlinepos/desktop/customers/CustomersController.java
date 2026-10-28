package za.co.onlinepos.desktop.customers;

import java.util.UUID;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import za.co.onlinepos.contracts.CustomerProfile;

public class CustomersController {
  @FXML private TextField search;
  @FXML private TableView<Customer> customers;
  @FXML private VBox details;
  @FXML private Label noSelection, customerCount, customerName, contact;
  private final AccountService service = AccountService.sample();
  private Customer selected;

  @FXML
  private void initialize() {
    customers.getColumns().add(column("Customer", Customer::name, 140));
    customers.getColumns().add(column("Phone", Customer::phone, 110));
    customers.getColumns().add(column("Phone 2", c -> c.profile().phone2(), 110));
    customers.getColumns().add(column("Address", Customer::address, 240));
    customers.setPlaceholder(new Label("No customers match your search"));
    search.textProperty().addListener((o, old, value) -> refresh(null));
    customers
        .getSelectionModel()
        .selectedItemProperty()
        .addListener((o, old, value) -> show(value));
    refresh(null);
  }

  private static <T> TableColumn<T, String> column(
      String title, Function<T, String> value, double width) {
    var column = new TableColumn<T, String>(title);
    column.setCellValueFactory(c -> new ReadOnlyStringWrapper(value.apply(c.getValue())));
    column.setPrefWidth(width);
    return column;
  }

  private void refresh(UUID preferred) {
    UUID id = preferred != null ? preferred : selected == null ? null : selected.id();
    customers.setItems(FXCollections.observableArrayList(service.search(search.getText())));
    customerCount.setText(customers.getItems().size() + " customers · sample data");
    if (id != null)
      customers.getItems().stream()
          .filter(c -> c.id().equals(id))
          .findFirst()
          .ifPresent(c -> customers.getSelectionModel().select(c));
    show(customers.getSelectionModel().getSelectedItem());
  }

  private void show(Customer customer) {
    selected = customer;
    details.setVisible(customer != null);
    details.setManaged(customer != null);
    noSelection.setVisible(customer == null);
    if (customer == null) return;
    customerName.setText(customer.name());
    contact.setText(
        customer.phone()
            + (customer.profile().phone2().isBlank() ? "" : " / " + customer.profile().phone2())
            + (customer.email().isEmpty() ? "" : "  •  " + customer.email())
            + (customer.address().isEmpty() ? "" : "\n" + customer.address())
            + "\nPreferred store: "
            + customer.profile().preferredStore()
            + "  •  Last ordered: "
            + (customer.profile().lastOrderedAt() == null
                ? "Never"
                : customer.profile().lastOrderedAt())
            + "\nDatabase ID: "
            + (customer.databaseId() == null ? "Not assigned (preview)" : customer.databaseId())
            + "  •  Role: "
            + customer.role()
            + "  •  Access level: "
            + customer.accessLevel()
            + "  •  Environment admin: "
            + customer.environmentAdmin());
  }

  @FXML
  private void addCustomer() {
    profile(null);
  }

  @FXML
  private void editCustomer() {
    if (selected != null) profile(selected);
  }

  private Dialog<ButtonType> dialog(String title, GridPane grid) {
    var dialog = new Dialog<ButtonType>();
    dialog.setTitle(title);
    dialog.initOwner(customers.getScene().getWindow());
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    var scroll = new ScrollPane(grid);
    scroll.setFitToWidth(true);
    scroll.setPrefViewportHeight(Math.min(560, 110 + grid.getRowCount() * 65));
    scroll.setPrefViewportWidth(530);
    dialog.getDialogPane().setContent(scroll);
    dialog.setResizable(true);
    dialog
        .getDialogPane()
        .getStylesheets()
        .add(getClass().getResource("accounts.css").toExternalForm());
    ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("Save");
    return dialog;
  }

  private GridPane form() {
    var grid = new GridPane();
    grid.setHgap(14);
    grid.setVgap(12);
    grid.setPadding(new Insets(16));
    return grid;
  }

  private TextField field(GridPane grid, int row, String label, String value, Label error) {
    var input = new TextField(value);
    input.setPrefWidth(310);
    error.getStyleClass().add("error");
    error.setWrapText(true);
    error.setMaxWidth(310);
    grid.add(new Label(label), 0, row);
    grid.add(new VBox(4, input, error), 1, row);
    return input;
  }

  private void profile(Customer customer) {
    var grid = form();
    var inputs = new java.util.LinkedHashMap<String, TextField>();
    var errors = new java.util.HashMap<String, Label>();
    var values =
        customer == null ? java.util.Map.<String, String>of() : customer.profile().values();
    int row = 0;
    for (var entry : CustomerProfile.FIELDS.entrySet()) {
      if (java.util.Set.of("country", "province", "latitude", "longitude").contains(entry.getKey()))
        continue;
      var error = new Label();
      errors.put(entry.getKey(), error);
      inputs.put(
          entry.getKey(),
          field(grid, row++, entry.getValue(), values.getOrDefault(entry.getKey(), ""), error));
    }
    var dialog = dialog(customer == null ? "Add customer" : "Edit customer", grid);
    dialog
        .getDialogPane()
        .lookupButton(ButtonType.OK)
        .addEventFilter(
            ActionEvent.ACTION,
            event -> {
              errors.values().forEach(error -> error.setText(""));
              try {
                var data = new java.util.LinkedHashMap<String, String>(values);
                inputs.forEach((key, input) -> data.put(key, input.getText()));
                var profile =
                    CustomerProfile.from(
                        data, customer == null ? null : customer.profile().lastOrderedAt());
                var saved = service.save(customer == null ? null : customer.id(), profile);
                search.clear();
                refresh(saved.id());
              } catch (CustomerProfile.FieldException e) {
                errors.get(e.field()).setText(e.getMessage());
                inputs.get(e.field()).requestFocus();
                event.consume();
              }
            });
    dialog.showAndWait();
  }
}
