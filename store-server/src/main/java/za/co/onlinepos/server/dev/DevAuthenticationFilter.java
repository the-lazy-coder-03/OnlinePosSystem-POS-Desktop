package za.co.onlinepos.server.dev;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class DevAuthenticationFilter extends OncePerRequestFilter {
  private final DevDatabase.Identity identity;

  public DevAuthenticationFilter(DevDatabase.Identity identity) {
    this.identity = identity;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("Cache-Control", "no-store");
    if (request.getRequestURI().startsWith("/api/local/dev/")) {
      String header = request.getHeader("Authorization");
      boolean loopback =
          List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr());
      if (!loopback
          || header == null
          || !MessageDigest.isEqual(
              header.getBytes(StandardCharsets.UTF_8),
              ("Bearer " + identity.token()).getBytes(StandardCharsets.UTF_8))) {
        response.sendError(403);
        return;
      }
      if (request.getContentLengthLong()
              > (request.getRequestURI().startsWith("/api/local/dev/data-manager/grid/catalogue")
                  ? 1048576
                  : 65536)
          || (List.of("POST", "PUT").contains(request.getMethod())
              && request.getContentLengthLong() < 0)) {
        response.sendError(413);
        return;
      }
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "development", null, List.of(new SimpleGrantedAuthority("DEV_DATA"))));
    }
    chain.doFilter(request, response);
  }
}
