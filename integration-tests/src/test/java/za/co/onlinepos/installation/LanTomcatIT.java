package za.co.onlinepos.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class LanTomcatIT {
  @TempDir Path root;

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(
      exclude = {
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
            .class
      })
  static class Config {
    @Bean
    Controller controller() {
      return new Controller();
    }

    @Bean
    org.springframework.security.web.SecurityFilterChain security(
        org.springframework.security.config.annotation.web.builders.HttpSecurity http)
        throws Exception {
      return http.authorizeHttpRequests(r -> r.anyRequest().permitAll()).build();
    }
  }

  @RestController
  static class Controller {
    Pairing.Bootstrap bootstrap;

    @GetMapping("/api/local/setup/bootstrap")
    Pairing.Bootstrap get() {
      return bootstrap;
    }
  }

  @Test
  void firstUseWorksWithTheRealBootTomcatTlsConfiguration() throws Exception {
    UUID cluster = UUID.randomUUID();
    var caKey = Certificates.keyPair();
    var ca =
        Certificates.issue(
            "OnlinePOS-" + cluster,
            caKey.getPublic(),
            null,
            caKey.getPrivate(),
            true,
            false,
            List.of());
    var key = Certificates.keyPair();
    var cert =
        Certificates.server(cluster, key.getPublic(), ca, caKey.getPrivate(), List.of("127.0.0.1"));
    char[] password = UUID.randomUUID().toString().toCharArray();
    JsonFiles.writeBytes(
        root.resolve("server.p12"), Certificates.store(key.getPrivate(), cert, ca, password));
    JsonFiles.writeBytes(root.resolve("trust.p12"), Certificates.store(null, null, ca, password));
    var stored = java.security.KeyStore.getInstance("PKCS12");
    try (var input = java.nio.file.Files.newInputStream(root.resolve("server.p12"))) {
      stored.load(input, password);
    }
    assertEquals(2, stored.getCertificateChain("identity").length);
    Map<String, Object> properties = new java.util.HashMap<>();
    properties.put("server.port", 0);
    properties.put("server.address", "127.0.0.1");
    properties.put("server.ssl.enabled", true);
    properties.put("server.ssl.client-auth", "want");
    properties.put("server.ssl.key-store", root.resolve("server.p12").toUri().toString());
    properties.put("server.ssl.key-store-password", new String(password));
    properties.put("server.ssl.trust-store", root.resolve("trust.p12").toUri().toString());
    properties.put("server.ssl.trust-store-password", new String(password));
    try (var context =
        new SpringApplicationBuilder(Config.class)
            .initializers(
                c ->
                    c.getEnvironment()
                        .getPropertySources()
                        .addFirst(
                            new org.springframework.core.env.MapPropertySource(
                                "test-tls", properties)))
            .run()) {
      var bootstrap =
          new Pairing.Bootstrap(
              1,
              UUID.randomUUID(),
              UUID.randomUUID(),
              1,
              "MASTER",
              "INSTALLED",
              Certificates.encode(ca),
              Certificates.fingerprint(ca));
      context.getBean(Controller.class).bootstrap = bootstrap;
      int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
      var pin = LanPairingClient.firstUse("https://127.0.0.1:" + port, 1);
      assertEquals(bootstrap.clusterId(), pin.clusterId());
    }
  }
}
