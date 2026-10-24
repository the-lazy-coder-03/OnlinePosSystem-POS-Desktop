package za.co.onlinepos.installation;

import java.io.IOException;
import java.util.Optional;

public interface InstallationRepository {
  Optional<Installation> load() throws IOException;

  void save(Installation installation) throws IOException;
}
