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

  public TerminalAuthenticationFilter(TerminalRegistry registry) {
    this.registry = registry;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.isSecure()) {
      response.sendError(403);
      return;
    }
    var certificates =
        (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");
    try {
      if (certificates != null && certificates.length > 0 && registry.authorized(certificates[0]))
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    certificates[0].getSubjectX500Principal().getName(), null, List.of()));
    } catch (Exception exception) {
      response.sendError(403);
      return;
    }
    chain.doFilter(request, response);
  }
}
