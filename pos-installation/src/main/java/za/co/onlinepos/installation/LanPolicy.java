package za.co.onlinepos.installation;

import java.nio.file.Path;

/** Exact, approved IPv4 interface. Never infer trust from an RFC1918 address alone. */
public record LanPolicy(String adapterId, String adapterName, String address, int prefixLength) {
  public LanPolicy {
    if (adapterId == null
        || adapterId.isBlank()
        || adapterName == null
        || adapterName.isBlank()
        || !privateAddress(address)
        || prefixLength < 8
        || prefixLength > 30)
      throw new IllegalArgumentException("Choose an active private store network");
    new NetworkSettings(8443, 55432, subnet(address, prefixLength));
  }

  public static boolean privateAddress(String value) {
    try {
      long ip = number(value);
      return (ip >>> 24 == 10) || (ip >>> 20 == 0xac1) || (ip >>> 16 == 0xc0a8);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  static long number(String value) {
    if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
      throw new IllegalArgumentException("Enter the Master's private LAN IPv4 address");
    long ip = 0;
    for (String part : value.split("\\.")) {
      int n = Integer.parseInt(part);
      if (n > 255 || (part.length() > 1 && part.startsWith("0")))
        throw new IllegalArgumentException("Invalid IPv4 address");
      ip = (ip << 8) | n;
    }
    return ip;
  }

  private static String subnet(String address, int prefix) {
    long ip = number(address) & (0xffffffffL << (32 - prefix));
    return ((ip >>> 24) & 255)
        + "."
        + ((ip >>> 16) & 255)
        + "."
        + ((ip >>> 8) & 255)
        + "."
        + (ip & 255)
        + "/"
        + prefix;
  }

  public String subnet() {
    return subnet(address, prefixLength);
  }

  public boolean contains(String peer) {
    if (!privateAddress(peer)) return false;
    long mask = 0xffffffffL << (32 - prefixLength);
    return (number(peer) & mask) == (number(address) & mask);
  }

  public boolean permits(String peer, String local) {
    return ("127.0.0.1".equals(peer) && "127.0.0.1".equals(local))
        || (address.equals(local) && contains(peer));
  }

  public static LanPolicy load(Path root) throws Exception {
    return JsonFiles.JSON.readValue(root.resolve("lan-policy.json").toFile(), LanPolicy.class);
  }
}
