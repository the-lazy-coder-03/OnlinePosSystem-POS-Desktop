package za.co.onlinepos.installation;

import java.util.Arrays;

public final class DefaultInstallationService implements InstallationService {
  private final PlatformProvisioner provisioner;

  public DefaultInstallationService(PlatformProvisioner provisioner) {
    this.provisioner = provisioner;
  }

  public void install(SetupRequest request, char[] password) throws Exception {
    if (password.length < 12)
      throw new IllegalArgumentException("Administrator password requires at least 12 characters");
    try {
      provisioner.provision(request, password);
    } finally {
      Arrays.fill(password, '\0');
    }
  }
}
