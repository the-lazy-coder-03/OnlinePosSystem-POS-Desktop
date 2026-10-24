package za.co.onlinepos.installation;

import java.nio.file.Files;
import java.nio.file.Path;

public record NetworkSettings(int httpsPort, int postgresPort) {
  public NetworkSettings {
    if (httpsPort < 1024
        || httpsPort > 65535
        || postgresPort < 1024
        || postgresPort > 65535
        || httpsPort == postgresPort) {
      throw new IllegalArgumentException("Choose distinct unprivileged TCP ports");
    }
  }

  public static NetworkSettings load(Path root) throws Exception {
    Path settings = root.resolve("network.json");
    return Files.exists(settings)
        ? JsonFiles.JSON.readValue(settings.toFile(), NetworkSettings.class)
        : new NetworkSettings(8443, 55432);
  }
}
