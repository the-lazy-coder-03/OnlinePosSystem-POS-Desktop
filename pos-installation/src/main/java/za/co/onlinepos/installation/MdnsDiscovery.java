package za.co.onlinepos.installation;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

public final class MdnsDiscovery implements MasterDiscovery {
  public static final String TYPE = "_onlinepos._tcp.local.";
  private final List<JmDNS> listeners = new ArrayList<>();

  private LanPolicy policy;

  public MdnsDiscovery() {}

  public MdnsDiscovery(LanPolicy policy) {
    this.policy = policy;
  }

  public synchronized void start() throws Exception {
    if (!listeners.isEmpty()) return;
    if (policy == null) {
      if (!System.getProperty("os.name").startsWith("Windows")) return;
      policy =
          LanPolicy.load(
              java.nio.file.Path.of(
                  System.getenv().getOrDefault("POS_DATA_ROOT", "C:\\ProgramData\\OnlinePOS")));
    }
    if (System.getProperty("os.name").startsWith("Windows")) {
      var approved = policy;
      policy =
          WindowsNetworks.available().stream()
              .filter(
                  n ->
                      n.adapterId().equals(approved.adapterId())
                          && n.subnet().equals(approved.subnet()))
              .findFirst()
              .orElseThrow(() -> new java.io.IOException("Approved private LAN unavailable"));
    }
    var address = InetAddress.getByName(policy.address());
    var network = java.net.NetworkInterface.getByInetAddress(address);
    if (network == null || !network.isUp() || network.isLoopback() || network.isVirtual())
      throw new java.io.IOException("Approved LAN unavailable");
    listeners.add(JmDNS.create(address));
  }

  public synchronized void advertise(Installation installation, int port) throws Exception {
    start();
    for (JmDNS listener : listeners)
      listener.registerService(
          ServiceInfo.create(
              TYPE,
              "OnlinePOS-" + installation.clusterId(),
              port,
              0,
              0,
              Map.of(
                  "branch",
                  Integer.toString(installation.branchId()),
                  "cluster",
                  installation.clusterId().toString())));
  }

  public synchronized List<String> candidates() throws Exception {
    start();
    var results = new LinkedHashSet<String>();
    for (JmDNS listener : listeners)
      for (ServiceInfo service : listener.list(TYPE, 1500))
        results.addAll(
            endpoints(service.getServer(), service.getPort(), service.getInet4Addresses()));
    if (policy != null)
      results.removeIf(
          origin -> {
            var host = java.net.URI.create(origin).getHost();
            return !policy.contains(host);
          });
    return List.copyOf(results);
  }

  // JmDNS can generate an address-derived DNS alias which is not a certificate SAN. The
  // advertised addresses are candidates only: TrustedStoreClient still validates CA, SAN and
  // branch.
  static List<String> endpoints(String server, int port, java.net.Inet4Address[] addresses) {
    if (port < 1 || port > 65535) return List.of();
    var result = new LinkedHashSet<String>();
    for (var address : addresses) result.add("https://" + address.getHostAddress() + ":" + port);
    if (server != null) {
      try {
        var endpoint = java.net.URI.create("https://" + server.replaceAll("\\.$", "") + ":" + port);
        if (endpoint.getHost() != null
            && endpoint.getUserInfo() == null
            && endpoint.getPath().isEmpty()
            && endpoint.getQuery() == null
            && endpoint.getFragment() == null) result.add(endpoint.toString());
      } catch (IllegalArgumentException ignored) {
        // A malformed discovery name must not hide usable advertised addresses.
      }
    }
    return List.copyOf(result);
  }

  public synchronized void close() {
    for (JmDNS listener : listeners)
      try {
        listener.close();
      } catch (java.io.IOException ignored) {
        /* shutdown best effort */
      }
    listeners.clear();
  }
}
