package za.co.onlinepos.installation;

import java.io.IOException;

public interface SecretStore {
  void put(String name, byte[] value) throws IOException;

  byte[] get(String name) throws IOException;
}
