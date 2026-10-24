package za.co.onlinepos.installation;

import java.util.List;

public interface MasterDiscovery extends AutoCloseable {
  List<String> candidates() throws Exception;

  @Override
  void close();
}
