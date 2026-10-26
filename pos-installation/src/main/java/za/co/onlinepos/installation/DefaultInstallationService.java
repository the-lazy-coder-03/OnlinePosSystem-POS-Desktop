package za.co.onlinepos.installation;

import java.util.Arrays;

public final class DefaultInstallationService implements InstallationService {
  private final PlatformProvisioner provisioner;

  public DefaultInstallationService(PlatformProvisioner provisioner) {
    this.provisioner = provisioner;
  }

  @Override
  public java.util.List<LanPolicy> networks() throws Exception {
    return provisioner.networks();
  }

  public void install(SetupRequest request, char[] password) throws Exception {
    try {
      if (request.role() != za.co.onlinepos.contracts.InstallationRole.SLAVE && password.length < 5)
        throw new IllegalArgumentException("Administrator password requires at least 5 characters");
      if (request.role() == za.co.onlinepos.contracts.InstallationRole.SLAVE
          && request.invitation().isBlank()
          && !LanPolicy.privateAddress(request.masterAddress()))
        throw new IllegalArgumentException("Enter the Master's private LAN IPv4 address");
      SetupRequest effective = request;
      provisioner.provision(effective, password);
    } finally {
      Arrays.fill(password, '\0');
    }
  }
}
