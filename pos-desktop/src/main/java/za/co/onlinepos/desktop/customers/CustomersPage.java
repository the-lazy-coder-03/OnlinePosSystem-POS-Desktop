package za.co.onlinepos.desktop.customers;

import java.io.IOException;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

/** Session-only customer prototype shared by authenticated navigation and the explicit preview. */
public final class CustomersPage {
  private CustomersPage() {}

  public static Parent load() throws IOException {
    return FXMLLoader.load(CustomersPage.class.getResource("customers.fxml"));
  }
}
