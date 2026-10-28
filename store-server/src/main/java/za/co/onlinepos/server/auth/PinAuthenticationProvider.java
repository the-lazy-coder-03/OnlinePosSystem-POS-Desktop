package za.co.onlinepos.server.auth;

import java.util.UUID;
import za.co.onlinepos.contracts.StaffAuthentication.Method;

/** Uses the same salted, expensive verifier as existing local PIN provisioning. */
public final class PinAuthenticationProvider implements AuthenticationProvider<char[]> {
  @Override
  public Method method() {
    return Method.PIN;
  }

  /** Caller owns and clears evidence. No fingerprint or card provider is registered. */
  @Override
  public boolean verify(UUID staffId, char[] evidence, String storedVerifier) {
    if (evidence == null || evidence.length != 6 || storedVerifier == null) return false;
    for (char digit : evidence) if (digit < '0' || digit > '9') return false;
    boolean verified = SecretVerifiers.matches(evidence, storedVerifier);
    return staffId != null && verified;
  }
}
