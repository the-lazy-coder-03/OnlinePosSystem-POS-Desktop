package za.co.onlinepos.contracts;

/** Account provenance is independent of the employee's roles and permissions. */
public final class StaffAuthentication {
  private StaffAuthentication() {}

  public enum AccountType {
    LOCAL,
    CLOUD
  }

  public enum Method {
    PIN,
    PASSWORD,
    FINGERPRINT,
    RFID_NFC
  }
}
