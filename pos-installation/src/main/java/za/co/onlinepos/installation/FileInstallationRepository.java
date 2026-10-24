package za.co.onlinepos.installation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class FileInstallationRepository implements InstallationRepository {
  private final Path path;

  public FileInstallationRepository(Path root) {
    path = root.resolve("installation.json");
  }

  public Optional<Installation> load() throws IOException {
    return Files.exists(path)
        ? Optional.of(JsonFiles.JSON.readValue(path.toFile(), Installation.class))
        : Optional.empty();
  }

  public synchronized void save(Installation installation) throws IOException {
    Optional<Installation> old = load();
    if (old.isPresent() && !old.get().installationId().equals(installation.installationId()))
      throw new IllegalStateException("Installation identity cannot be replaced");
    JsonFiles.write(path, installation);
  }
}
