package za.co.onlinepos.server;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration(proxyBeanMethods = false)
public class FoundationSecurity {
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      org.springframework.beans.factory.ObjectProvider<
              za.co.onlinepos.installation.TerminalRegistry>
          registry)
      throws Exception {
    if (registry.getIfAvailable() != null) {
      return http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/local/setup/pair"))
          .sessionManagement(
              sessions ->
                  sessions.sessionCreationPolicy(
                      org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
          .addFilterBefore(
              new TerminalAuthenticationFilter(registry.getObject()),
              org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class)
          .authorizeHttpRequests(
              requests ->
                  requests
                      .requestMatchers(HttpMethod.GET, "/api/local/health")
                      .permitAll()
                      .requestMatchers(HttpMethod.POST, "/api/local/setup/pair")
                      .permitAll()
                      .requestMatchers(
                          HttpMethod.GET, "/api/local/identity", "/api/local/diagnostics")
                      .authenticated()
                      .anyRequest()
                      .denyAll())
          .build();
    }
    return http.authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(HttpMethod.GET, "/api/local/health")
                    .permitAll()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN)))
        .build();
  }
}
