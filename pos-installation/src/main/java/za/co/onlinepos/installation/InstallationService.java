package za.co.onlinepos.installation;

public interface InstallationService {
  void install(SetupRequest request, char[] administratorPassword) throws Exception;

  default java.util.List<LanPolicy> networks() throws Exception {
    throw new UnsupportedOperationException("Network enumeration is not configured");
  }
}
