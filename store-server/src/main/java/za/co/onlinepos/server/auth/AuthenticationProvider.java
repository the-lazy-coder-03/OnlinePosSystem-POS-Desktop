package za.co.onlinepos.server.auth;

import java.util.UUID;
import za.co.onlinepos.contracts.StaffAuthentication.Method;

/**
 * Internal provider boundary. Evidence is verified for an exact identity, never a role or card UID.
 */
public interface AuthenticationProvider<E> {
  Method method();

  boolean verify(UUID staffId, E evidence, String storedVerifier);
}
