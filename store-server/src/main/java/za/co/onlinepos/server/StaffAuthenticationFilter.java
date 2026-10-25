package za.co.onlinepos.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import za.co.onlinepos.server.local.LocalFailure;
import za.co.onlinepos.server.local.StaffService;

public final class StaffAuthenticationFilter extends OncePerRequestFilter {
  private final StaffService staff;
  private final za.co.onlinepos.server.local.ModuleAccess modules;
  private final String developmentTerminal;
  private final Map<String, long[]> attempts = new HashMap<>();

  public StaffAuthenticationFilter(
      StaffService staff,
      String developmentTerminal,
      za.co.onlinepos.server.local.ModuleAccess modules) {
    this.staff = staff;
    this.modules = modules;
    this.developmentTerminal = developmentTerminal;
  }

  private synchronized boolean loginAllowed(String terminal) {
    long now = System.currentTimeMillis();
    attempts.entrySet().removeIf(e -> e.getValue()[0] < now - 60000);
    if (attempts.size() >= 1024 && !attempts.containsKey(terminal)) return false;
    return ++attempts.computeIfAbsent(terminal, key -> new long[] {now, 0})[1] <= 10;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    response.setHeader("Cache-Control", "no-store");
    String path = request.getRequestURI();
    if (List.of(
            "/api/local/health",
            "/api/local/setup/pair",
            "/api/local/identity",
            "/api/local/diagnostics")
        .contains(path)) {
      chain.doFilter(request, response);
      return;
    }
    String terminal = (String) request.getAttribute("pos.terminal");
    if (terminal == null
        && developmentTerminal != null
        && (request.getRemoteAddr().equals("127.0.0.1") || request.getRemoteAddr().equals("::1"))) {
      terminal = "development:" + developmentTerminal;
      request.setAttribute("pos.terminal", terminal);
    }
    if (terminal == null) {
      response.sendError(403);
      return;
    }
    if ((List.of("POST", "PUT", "PATCH").contains(request.getMethod())
            && request.getContentLengthLong() < 0)
        || request.getContentLengthLong()
            > (path.matches("/api/local/branches/[12]/catalogue/bootstrap") ? 16777216 : 65536)) {
      response.sendError(413);
      return;
    }
    if ((path.startsWith("/api/local/modules/") && !path.equals("/api/local/modules/enter"))
        || path.startsWith("/api/local/admin/")
        || path.startsWith("/api/local/catalogue/")) {
      String header = request.getHeader("Authorization");
      try {
        var session =
            modules.authenticate(
                header != null && header.startsWith("Bearer ") ? header.substring(7) : null,
                terminal,
                false);
        request.setAttribute("pos.module", session);
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    session.staffId(), null, List.of(new SimpleGrantedAuthority("POS_MODULE"))));
      } catch (LocalFailure failure) {
        if (path.equals("/api/local/admin/audit/settings") && request.getMethod().equals("PUT")) {
          try {
            modules.deniedAuditSettings(terminal);
          } catch (org.springframework.dao.DataAccessException unavailable) {
            response.sendError(503);
            return;
          }
        }
        response.sendError(failure.status());
        return;
      }
    } else if (path.equals("/api/local/modules/enter") && request.getMethod().equals("POST")) {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  terminal, null, List.of(new SimpleGrantedAuthority("POS_TERMINAL"))));
    } else if (path.equals("/api/local/auth/login") && request.getMethod().equals("POST")) {
      if (!loginAllowed(terminal)) {
        response.sendError(429);
        return;
      }
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  terminal, null, List.of(new SimpleGrantedAuthority("POS_TERMINAL"))));
    } else {
      String header = request.getHeader("Authorization");
      try {
        StaffService.Actor actor =
            staff.authenticate(
                header != null && header.startsWith("Bearer ") ? header.substring(7) : null,
                terminal);
        var permissions = modules.permissions(actor.staffId());
        String required =
            path.equals("/api/local/staff")
                ? "staff.manage"
                : path.contains("/drafts")
                    ? "module.sales_orders"
                    : path.contains("/prices/") || path.endsWith("/catalogue/bootstrap")
                        ? "module.catalogue"
                        : null;
        boolean catalogueRead =
            path.equals("/api/local/menu") || path.endsWith("/menu") || path.endsWith("/catalogue");
        if ((required != null && !permissions.contains(required))
            || (catalogueRead
                && !permissions.contains("module.sales_orders")
                && !permissions.contains("module.catalogue"))) {
          response.sendError(403);
          return;
        }
        request.setAttribute("pos.staff", actor);
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    actor.staffId(), null, List.of(new SimpleGrantedAuthority("POS_STAFF"))));
      } catch (LocalFailure failure) {
        response.sendError(failure.status());
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
