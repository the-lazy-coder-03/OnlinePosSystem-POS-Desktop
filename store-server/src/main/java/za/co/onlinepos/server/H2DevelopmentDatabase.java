package za.co.onlinepos.server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * SQL-source structural mirror only; PostgreSQL authority services are deliberately not enabled.
 */
@Configuration(proxyBeanMethods = false)
@Profile("h2-development")
public class H2DevelopmentDatabase {
  @Bean(destroyMethod = "close")
  HikariDataSource h2DataSource(Environment env) {
    if (env.matchesProfiles("installed", "local-store"))
      throw new IllegalStateException("H2 development cannot be combined with PostgreSQL profiles");
    if (!"127.0.0.1".equals(env.getProperty("server.address")))
      throw new IllegalStateException("H2 development must bind loopback");
    var config = new HikariConfig();
    config.setJdbcUrl(env.getRequiredProperty("pos.h2.url"));
    config.setUsername("onlinepos");
    config.setPassword(env.getRequiredProperty("pos.h2.password"));
    config.setMaximumPoolSize(4);
    config.setConnectionTimeout(5000);
    var source = new HikariDataSource(config);
    try {
      new ResourceDatabasePopulator(new ClassPathResource("db/h2/schema.sql")).execute(source);
      return source;
    } catch (RuntimeException failure) {
      source.close();
      throw failure;
    }
  }
}
