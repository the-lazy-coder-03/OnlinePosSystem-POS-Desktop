package za.co.onlinepos.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class StoreServerApplication extends SpringBootServletInitializer {
  @Override
  protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
    return application.sources(StoreServerApplication.class);
  }

  public static void main(String[] args) throws Exception {
    boolean installed =
        java.util.Arrays.asList(args).contains("--spring.profiles.active=installed");
    SpringApplication application = new SpringApplication(StoreServerApplication.class);
    if (installed) {
      // System properties override development defaults without exposing secrets as CLI arguments.
      InstalledTls.properties().forEach((key, value) -> System.setProperty(key, value.toString()));
    }
    application.run(args);
  }
}
