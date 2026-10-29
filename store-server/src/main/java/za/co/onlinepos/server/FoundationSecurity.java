package za.co.onlinepos.server;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import za.co.onlinepos.installation.TerminalRegistry;
import za.co.onlinepos.server.local.StaffService;

@Configuration(proxyBeanMethods = false)
public class FoundationSecurity {
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectProvider<TerminalRegistry> registry,
      ObjectProvider<za.co.onlinepos.installation.LanPolicy> policy,
      ObjectProvider<StaffService> staff,
      ObjectProvider<za.co.onlinepos.server.local.ModuleAccess> modules,
      ObjectProvider<za.co.onlinepos.server.dev.DevDatabase.Identity> development,
      Environment env)
      throws Exception {
    if (env.matchesProfiles("dev")) {
      var identity = development.getObject();
      return http.csrf(csrf -> csrf.disable())
          .sessionManagement(
              session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
          .addFilterBefore(
              new za.co.onlinepos.server.dev.DevAuthenticationFilter(identity),
              AnonymousAuthenticationFilter.class)
          .authorizeHttpRequests(
              requests ->
                  requests
                      .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                      .permitAll()
                      .requestMatchers(HttpMethod.GET, "/api/local/health")
                      .permitAll()
                      .requestMatchers("/api/local/dev/data-manager/**")
                      .hasAuthority("DEV_DATA")
                      .anyRequest()
                      .denyAll())
          .exceptionHandling(
              ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN)))
          .build();
    }
    if (registry.getIfAvailable() != null || staff.getIfAvailable() != null) {
      http.csrf(csrf -> csrf.disable())
          .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
      if (registry.getIfAvailable() != null)
        http.addFilterBefore(
            new TerminalAuthenticationFilter(registry.getObject(), policy.getIfAvailable()),
            AnonymousAuthenticationFilter.class);
      if (staff.getIfAvailable() != null) {
        String dev = null;
        if (!env.matchesProfiles("installed") && env.matchesProfiles("local-store")) {
          dev = env.getProperty("pos.development-terminal");
          if (dev != null && dev.isBlank()) dev = null;
          if (dev != null) java.util.UUID.fromString(dev);
          if (!"127.0.0.1".equals(env.getProperty("server.address")))
            throw new IllegalStateException("Development local-store must bind loopback");
        }
        var filter = new StaffAuthenticationFilter(staff.getObject(), dev, modules.getObject());
        if (registry.getIfAvailable() != null)
          http.addFilterAfter(filter, TerminalAuthenticationFilter.class);
        else http.addFilterBefore(filter, AnonymousAuthenticationFilter.class);
      }
      return http.authorizeHttpRequests(
              requests ->
                  requests
                      .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                      .permitAll()
                      .requestMatchers(HttpMethod.GET, "/api/local/health")
                      .permitAll()
                      .requestMatchers(
                          HttpMethod.GET, "/api/local/setup/bootstrap", "/api/local/setup/store")
                      .permitAll()
                      .requestMatchers(
                          HttpMethod.POST,
                          "/api/local/setup/pair",
                          "/api/local/setup/challenge",
                          "/api/local/setup/enrol")
                      .permitAll()
                      .requestMatchers(
                          HttpMethod.GET,
                          "/api/local/identity",
                          "/api/local/diagnostics",
                          "/api/local/terminal/heartbeat")
                      .authenticated()
                      .requestMatchers(
                          HttpMethod.POST, "/api/local/auth/login", "/api/local/modules/enter")
                      .hasAuthority("POS_TERMINAL")
                      .requestMatchers(
                          "/api/local/modules/me",
                          "/api/local/modules/activity",
                          "/api/local/modules/logout",
                          "/api/local/admin/**",
                          "/api/local/customers",
                          "/api/local/customers/**",
                          "/api/local/catalogue/**")
                      .hasAuthority("POS_MODULE")
                      .requestMatchers(
                          "/api/local/menu",
                          "/api/local/auth/me",
                          "/api/local/auth/logout",
                          "/api/local/branches/*/menu",
                          "/api/local/branches/*/catalogue",
                          "/api/local/branches/*/catalogue/bootstrap",
                          "/api/local/branches/*/prices/*",
                          "/api/local/branches/*/drafts",
                          "/api/local/branches/*/drafts/*",
                          "/api/local/branches/*/drafts/*/cancel",
                          "/api/local/staff",
                          "/api/local/printer",
                          "/api/local/configuration")
                      .hasAuthority("POS_STAFF")
                      .anyRequest()
                      .denyAll())
          .exceptionHandling(
              ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN)))
          .build();
    }
    return http.authorizeHttpRequests(
            requests ->
                requests
                    .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/local/health")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN)))
        .build();
  }
}
