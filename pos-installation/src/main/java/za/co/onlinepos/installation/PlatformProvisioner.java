package za.co.onlinepos.installation;

public interface PlatformProvisioner {
  void provision(SetupRequest request, char[] administratorPassword) throws Exception;
}
