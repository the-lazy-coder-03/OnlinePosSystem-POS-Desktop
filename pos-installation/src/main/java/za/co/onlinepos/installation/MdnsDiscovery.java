package za.co.onlinepos.installation;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

public final class MdnsDiscovery implements MasterDiscovery {
  public static final String TYPE = "_onlinepos._tcp.local.";
  private final List<JmDNS> listeners = new ArrayList<>();

  public synchronized void start() throws Exception {
    if (!listeners.isEmpty()) return;
    var interfaces = java.net.NetworkInterface.getNetworkInterfaces();
    while (interfaces.hasMoreElements()) {
      var network = interfaces.nextElement();
      if (!network.isUp() || network.isLoopback()) continue;
      for (InetAddress address : java.util.Collections.list(network.getInetAddresses()))
        if (address instanceof java.net.Inet4Address) listeners.add(JmDNS.create(address));
    }
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
    List<String> results = new ArrayList<>();
    for (JmDNS listener : listeners)
      for (ServiceInfo service : listener.list(TYPE, 1500))
        results.add(
            "https://" + service.getServer().replaceAll("\\.$", "") + ":" + service.getPort());
    return List.copyOf(results);
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
