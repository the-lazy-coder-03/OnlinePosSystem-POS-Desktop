package za.co.onlinepos.installation;

import java.util.Arrays;

public final class DefaultInstallationService implements InstallationService {
  private final PlatformProvisioner provisioner;

  public DefaultInstallationService(PlatformProvisioner provisioner) {
    this.provisioner = provisioner;
  }

  public void install(SetupRequest request, char[] password) throws Exception {
    try {
      if (password.length < 12)
        throw new IllegalArgumentException(
            "Administrator password requires at least 12 characters");
      SetupRequest effective = request;
      if (!request.masterAddress().isBlank()) {
        Pairing.Invitation original =
            JsonFiles.JSON.readValue(request.invitation(), Pairing.Invitation.class);
        Pairing.Invitation manual =
            new Pairing.Invitation(
                original.version(),
                original.clusterId(),
                original.branchId(),
                request.masterAddress(),
                original.caCertificate(),
                original.caFingerprint(),
                original.token(),
                original.expiresAt());
        effective =
            new SetupRequest(
                request.branchId(),
                request.role(),
                request.terminalName(),
                JsonFiles.JSON.writeValueAsString(manual));
      }
      provisioner.provision(effective, password);
    } finally {
      Arrays.fill(password, '\0');
    }
  }
}
