package za.co.onlinepos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Arrays;
import za.co.onlinepos.contracts.PosModule;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.Pairing;
import za.co.onlinepos.installation.SecretStore;
import za.co.onlinepos.installation.TrustedStoreClient;
import za.co.onlinepos.installation.WindowsSecretStore;

/** No session or PIN is written to disk. Called only on worker threads. */
public class ModuleGateway {
  private final Path root;
  private final TrustedStoreClient client = new TrustedStoreClient();

  public ModuleGateway(Path root) {
    this.root = root;
  }

  private SecretStore secrets() {
    return new WindowsSecretStore(root);
  }

  private Pairing.Invitation trust(SecretStore secrets) throws Exception {
    return JsonFiles.JSON.readValue(secrets.get("pairing-trust"), Pairing.Invitation.class);
  }

  public String localLanAddress() throws Exception {
    return za.co.onlinepos.installation.LanPolicy.load(root).address();
  }

  public void check() throws Exception {
    var secrets = secrets();
    client.identity(trust(secrets), secrets);
  }

  public boolean ownerRecoveryAvailable() {
    return new za.co.onlinepos.installation.OwnerRecoveryLauncher(root).available();
  }

  public void recoverOwner() throws Exception {
    new za.co.onlinepos.installation.OwnerRecoveryLauncher(root).open();
  }

  public JsonNode enter(PosModule module, char[] pin) throws Exception {
    byte[] body = null;
    try {
      body =
          JsonFiles.JSON.writeValueAsBytes(
              java.util.Map.of("pin", new String(pin), "module", module));
      return request("enter", "POST", body, null);
    } finally {
      Arrays.fill(pin, '\0');
      if (body != null) Arrays.fill(body, (byte) 0);
    }
  }

  public JsonNode request(String endpoint, String method, byte[] body, String token)
      throws Exception {
    var secrets = secrets();
    return JsonFiles.JSON.readTree(
        client.moduleRequest(
            trust(secrets), secrets, "/api/local/modules/" + endpoint, method, body, token));
  }

  public JsonNode admin(String path, String method, Object payload, String token) throws Exception {
    var secrets = secrets();
    byte[] body = null;
    try {
      if (payload != null) body = JsonFiles.JSON.writeValueAsBytes(payload);
      return JsonFiles.JSON.readTree(
          client.adminRequest(
              trust(secrets), secrets, "/api/local/admin/" + path, method, body, token));
    } finally {
      if (body != null) Arrays.fill(body, (byte) 0);
    }
  }

  public JsonNode catalogue(String path, String method, Object payload, String token)
      throws Exception {
    var secrets = secrets();
    byte[] body = null;
    try {
      if (payload != null) body = JsonFiles.JSON.writeValueAsBytes(payload);
      return JsonFiles.JSON.readTree(
          client.catalogueRequest(
              trust(secrets), secrets, "/api/local/catalogue/" + path, method, body, token));
    } finally {
      if (body != null) Arrays.fill(body, (byte) 0);
    }
  }
}
