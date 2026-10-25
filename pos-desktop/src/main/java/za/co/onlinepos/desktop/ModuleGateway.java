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

  public ModuleGateway(Path root) {
    this.root = root;
  }

  private SecretStore secrets() {
    return new WindowsSecretStore(root);
  }

  private Pairing.Invitation trust(SecretStore secrets) throws Exception {
    return JsonFiles.JSON.readValue(secrets.get("pairing-trust"), Pairing.Invitation.class);
  }

  public void check() throws Exception {
    var secrets = secrets();
    new TrustedStoreClient().identity(trust(secrets), secrets);
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
        new TrustedStoreClient()
            .moduleRequest(
                trust(secrets), secrets, "/api/local/modules/" + endpoint, method, body, token));
  }

  public JsonNode admin(String path, String method, Object payload, String token) throws Exception {
    var secrets = secrets();
    byte[] body = null;
    try {
      if (payload != null) body = JsonFiles.JSON.writeValueAsBytes(payload);
      return JsonFiles.JSON.readTree(
          new TrustedStoreClient()
              .adminRequest(
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
          new TrustedStoreClient()
              .catalogueRequest(
                  trust(secrets), secrets, "/api/local/catalogue/" + path, method, body, token));
    } finally {
      if (body != null) Arrays.fill(body, (byte) 0);
    }
  }
}
