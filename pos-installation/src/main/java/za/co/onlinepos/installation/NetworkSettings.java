package za.co.onlinepos.installation;

import java.nio.file.Files;
import java.nio.file.Path;

public record NetworkSettings(int httpsPort, int postgresPort, String storeSubnet) {
  public NetworkSettings(int httpsPort, int postgresPort) {
    this(httpsPort, postgresPort, "LocalSubnet");
  }

  public NetworkSettings {
    if (storeSubnet == null) storeSubnet = "LocalSubnet";
    if (!storeSubnet.equals("LocalSubnet")) {
      if (!storeSubnet.matches("([0-9]{1,3}\\.){3}[0-9]{1,3}/[0-9]{1,2}"))
        throw new IllegalArgumentException("Choose a private IPv4 store subnet");
      String[] parts = storeSubnet.split("/");
      int[] octets =
          java.util.Arrays.stream(parts[0].split("\\.")).mapToInt(Integer::parseInt).toArray();
      int prefix = Integer.parseInt(parts[1]);
      boolean privateRange =
          (octets[0] == 10 && prefix >= 8)
              || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31 && prefix >= 12)
              || (octets[0] == 192 && octets[1] == 168 && prefix >= 16);
      if (!privateRange
          || prefix > 32
          || java.util.Arrays.stream(octets).anyMatch(value -> value > 255))
        throw new IllegalArgumentException("Choose a private IPv4 store subnet");
    }
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
