package za.co.onlinepos.installation;

public interface InstallationService {
  void install(SetupRequest request, char[] administratorPassword) throws Exception;
}
