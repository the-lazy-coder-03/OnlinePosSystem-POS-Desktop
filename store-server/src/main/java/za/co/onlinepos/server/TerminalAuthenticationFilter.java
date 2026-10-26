package za.co.onlinepos.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import za.co.onlinepos.installation.TerminalRegistry;

public final class TerminalAuthenticationFilter extends OncePerRequestFilter {
  private final TerminalRegistry registry;
  private final za.co.onlinepos.installation.LanPolicy policy;
  private final java.util.Map<String, long[]> pairingAttempts = new java.util.HashMap<>();

  private synchronized boolean permitPairing(String address) {
    long now = System.currentTimeMillis();
    pairingAttempts.entrySet().removeIf(e -> e.getValue()[0] < now - 60000);
    if (pairingAttempts.size() >= 1024 && !pairingAttempts.containsKey(address)) return false;
    long[] bucket = pairingAttempts.computeIfAbsent(address, key -> new long[] {now, 0});
    return ++bucket[1] <= 10;
  }

  public TerminalAuthenticationFilter(TerminalRegistry registry) {
    this(registry, null);
  }

  public TerminalAuthenticationFilter(
      TerminalRegistry registry, za.co.onlinepos.installation.LanPolicy policy) {
    this.registry = registry;
    this.policy = policy;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.isSecure()
        || (policy != null && !policy.permits(request.getRemoteAddr(), request.getLocalAddr()))) {
      response.sendError(403);
      return;
    }
    if (request.getRequestURI().startsWith("/api/local/setup/")) {
      response.setHeader("Cache-Control", "no-store");
      if (!request.getMethod().equals("GET")
          && (request.getContentLengthLong() < 0 || request.getContentLengthLong() > 16384)) {
        response.sendError(413);
        return;
      }
      if (!permitPairing(request.getRemoteAddr())) {
        response.sendError(429);
        return;
      }
    }
    var certificates =
        (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");
    try {
      if (certificates != null && certificates.length > 0 && registry.authorized(certificates[0])) {
        registry.contact(
            za.co.onlinepos.installation.Certificates.fingerprint(certificates[0]),
            request.getRemoteAddr());
        request.setAttribute(
            "pos.terminal",
            java.util.HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(certificates[0].getEncoded())));
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    certificates[0].getSubjectX500Principal().getName(), null, List.of()));
      }
    } catch (Exception exception) {
      response.sendError(403);
      return;
    }
    chain.doFilter(request, response);
  }
}
