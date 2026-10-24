package za.co.onlinepos.installation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Administrator {
  public record Verifier(String salt, String hash, int attempts, long lockedUntil) {}

  private final Path file;
  private final Clock clock;

  public Administrator(Path root, Clock clock) {
    file = root.resolve("administrator.json");
    this.clock = clock;
  }

  public synchronized void initialize(char[] password) throws IOException {
    if (Files.exists(file)) throw new IllegalStateException("Administrator already enrolled");
    if (password.length < 12) throw new IllegalArgumentException("Use at least 12 characters");
    byte[] salt = new byte[24];
    new SecureRandom().nextBytes(salt);
    JsonFiles.write(
        file, new Verifier(Base64.getEncoder().encodeToString(salt), derive(password, salt), 0, 0));
  }

  public synchronized void verify(char[] password) throws IOException {
    Verifier v = JsonFiles.JSON.readValue(file.toFile(), Verifier.class);
    if (v.lockedUntil() > clock.millis())
      throw new SecurityException("Administrator login temporarily locked");
    boolean matches =
        MessageDigest.isEqual(
            Base64.getDecoder().decode(v.hash()),
            Base64.getDecoder().decode(derive(password, Base64.getDecoder().decode(v.salt()))));
    int attempts = matches ? 0 : v.attempts() + 1;
    JsonFiles.write(
        file,
        new Verifier(v.salt(), v.hash(), attempts, attempts >= 5 ? clock.millis() + 300000 : 0));
    if (!matches) throw new SecurityException("Administrator authentication failed");
  }

  private String derive(char[] password, byte[] salt) {
    PBEKeySpec spec = new PBEKeySpec(password, salt, 600000, 256);
    try {
      return Base64.getEncoder()
          .encodeToString(
              SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                  .generateSecret(spec)
                  .getEncoded());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    } finally {
      spec.clearPassword();
    }
  }
}
