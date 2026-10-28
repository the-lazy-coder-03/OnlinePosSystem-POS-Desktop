package za.co.onlinepos.server.local;

import za.co.onlinepos.server.auth.SecretVerifiers;

/** Compatibility facade; both local database implementations share the same verifier format. */
public final class Passwords {
  private Passwords() {}

  public static String hash(char[] value) {
    return SecretVerifiers.hash(value);
  }

  public static String hashPin(char[] value) {
    return SecretVerifiers.hashPin(value);
  }

  static String hashRecovery(char[] value) {
    return SecretVerifiers.hashRecovery(value);
  }

  public static String commandProof(char[] value, java.util.UUID command) {
    return SecretVerifiers.commandProof(value, command);
  }

  public static boolean matches(char[] value, String verifier) {
    return SecretVerifiers.matches(value, verifier);
  }
}
