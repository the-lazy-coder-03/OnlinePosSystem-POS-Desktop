package za.co.onlinepos.installation;

import java.util.UUID;

public interface PairingClient {
  Pairing.Identity pair(Pairing.Invitation invitation, UUID terminalId, SecretStore secrets)
      throws Exception;
}
