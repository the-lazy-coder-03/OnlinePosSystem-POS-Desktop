package za.co.onlinepos.server.local;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Passwords {
  private static final int ITERATIONS = 600000;

  private Passwords() {}

  public static String hash(char[] password) {
    if (password.length < 12 || password.length > 128)
      throw new IllegalArgumentException("Staff passwords require 12 to 128 characters");
    byte[] salt = new byte[24];
    new SecureRandom().nextBytes(salt);
    return ITERATIONS
        + ":"
        + Base64.getEncoder().encodeToString(salt)
        + ":"
        + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
  }

  public static boolean matches(char[] password, String encoded) {
    try {
      String[] parts = encoded.split(":");
      int iterations = Integer.parseInt(parts[0]);
      if (iterations != ITERATIONS || password.length > 128) return false;
      return MessageDigest.isEqual(
          Base64.getDecoder().decode(parts[2]),
          derive(password, Base64.getDecoder().decode(parts[1]), iterations));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations) {
    var spec = new PBEKeySpec(password, salt, iterations, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("Password derivation unavailable", exception);
    } finally {
      spec.clearPassword();
    }
  }
}
