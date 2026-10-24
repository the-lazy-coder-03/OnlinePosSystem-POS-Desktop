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
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
