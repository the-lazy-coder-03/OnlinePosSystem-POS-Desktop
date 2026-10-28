package za.co.onlinepos.server.local;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.onlinepos.contracts.Branch;

@Configuration(proxyBeanMethods = false)
@Profile({"installed", "local-store"})
public class LocalDatabase {
  public record Identity(int branchId, UUID clusterId, UUID installationId) {}

  @Bean(destroyMethod = "close")
  HikariDataSource localDataSource(Environment env) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(env.getRequiredProperty("pos.database.url"));
    config.setUsername(
        env.matchesProfiles("installed")
            ? "pos_runtime"
            : env.getProperty("pos.database.username", "pos_runtime"));
    config.setPassword(env.getRequiredProperty("pos.database.password"));
    config.setMaximumPoolSize(8);
    config.setConnectionTimeout(5000);
    config.setInitializationFailTimeout(60000);
    config.setValidationTimeout(3000);
    config.addDataSourceProperty("socketTimeout", "10");
    return new HikariDataSource(config);
  }

  @Bean
  JdbcTemplate localJdbc(DataSource dataSource) {
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.setQueryTimeout(5);
    return jdbc;
  }

  @Bean
  TransactionTemplate localTransactions(DataSource dataSource) {
    return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @Bean
  Identity databaseIdentity(JdbcTemplate jdbc, Environment env) {
    org.flywaydb.core.Flyway.configure()
        .dataSource(jdbc.getDataSource())
        .locations("classpath:db/migration")
        .cleanDisabled(true)
        .load()
        .validate();
    int branch = Integer.parseInt(env.getRequiredProperty("pos.branch-id"));
    Branch.fromId(branch);
    Identity expected =
        new Identity(
            branch,
            UUID.fromString(env.getRequiredProperty("pos.cluster-id")),
            UUID.fromString(env.getRequiredProperty("pos.installation-id")));
    Identity actual =
        jdbc.queryForObject(
            "SELECT branch_id,cluster_id,installation_id FROM pos.store_identity",
            (rs, n) ->
                new Identity(
                    rs.getInt(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)));
    if (!expected.equals(actual))
      throw new IllegalStateException("Database identity does not match this installation");
    return expected;
  }
}
