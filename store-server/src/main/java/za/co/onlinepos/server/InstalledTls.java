package za.co.onlinepos.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import za.co.onlinepos.installation.JsonFiles;
import za.co.onlinepos.installation.WindowsSecretStore;

public final class InstalledTls {
  private InstalledTls() {}

  public static Map<String, Object> properties() throws Exception {
    Path root = InstalledConfiguration.dataRoot();
    var secrets = new WindowsSecretStore(root);
    Path runtime = root.resolve("runtime");
    Files.createDirectories(runtime);
    JsonFiles.writeBytes(runtime.resolve("server.p12"), secrets.get("server-p12"));
    JsonFiles.writeBytes(runtime.resolve("trust.p12"), secrets.get("trust-p12"));
    String password =
        new String(secrets.get("pki-password"), java.nio.charset.StandardCharsets.US_ASCII);
    Map<String, Object> config = new HashMap<>();
    config.put("server.address", "0.0.0.0");
    config.put(
        "server.port",
        Integer.toString(za.co.onlinepos.installation.NetworkSettings.load(root).httpsPort()));
    config.put("server.ssl.enabled", "true");
    config.put("server.ssl.client-auth", "want");
    config.put("server.ssl.key-store", runtime.resolve("server.p12").toUri().toString());
    config.put("server.ssl.key-store-password", password);
    config.put("server.ssl.trust-store", runtime.resolve("trust.p12").toUri().toString());
    config.put("server.ssl.trust-store-password", password);
    var installed =
        new za.co.onlinepos.installation.FileInstallationRepository(root).load().orElseThrow();
    config.put("pos.branch-id", installed.branchId());
    config.put("pos.cluster-id", installed.clusterId());
    config.put("pos.installation-id", installed.installationId());
    config.put(
        "pos.database.url",
        "jdbc:postgresql://127.0.0.1:"
            + za.co.onlinepos.installation.NetworkSettings.load(root).postgresPort()
            + "/pos_store");
    config.put(
        "pos.database.password",
        new String(secrets.get("database-runtime"), java.nio.charset.StandardCharsets.UTF_8));
    config.put(
        "pos.pin-key",
        new String(secrets.get("pin-key"), java.nio.charset.StandardCharsets.US_ASCII));
    return config;
  }
}
