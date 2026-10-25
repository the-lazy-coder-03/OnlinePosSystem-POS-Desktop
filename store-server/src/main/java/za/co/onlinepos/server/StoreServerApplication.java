package za.co.onlinepos.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@SpringBootApplication(
    exclude = {
      UserDetailsServiceAutoConfiguration.class,
      org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class,
      org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration.class
    })
public class StoreServerApplication extends SpringBootServletInitializer {
  @Override
  protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
    return application.sources(StoreServerApplication.class);
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 3 && args[0].equals("--catalogue-export")) {
      CatalogueExport.main(new String[] {args[1], args[2]});
      return;
    }
    if (java.util.Arrays.stream(args).anyMatch(arg -> arg.startsWith("--database-tool="))) {
      DatabaseTool.main(args);
      return;
    }
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
