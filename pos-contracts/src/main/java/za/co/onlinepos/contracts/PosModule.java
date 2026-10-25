package za.co.onlinepos.contracts;

public enum PosModule {
  SALES_ORDERS("Sales & Orders"),
  CUSTOMERS("Customers"),
  CATALOGUE("Catalogue Management"),
  CASH("Cash Management"),
  REPORTS("Reports"),
  ADMINISTRATION("Administration");

  private final String label;

  PosModule(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public String permission() {
    return "module." + name().toLowerCase(java.util.Locale.ROOT);
  }
}
