package za.co.onlinepos.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class H2SchemaTest {
  @TempDir Path directory;

  @Test
  void sqlMirrorPersistsAcrossReopenAndRetainsFieldsAndConstraints() throws Exception {
    String url =
        "jdbc:h2:file:"
            + directory.resolve("store")
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
    try (var connection = DriverManager.getConnection(url, "test", "temporary-test-password")) {
      var source = new SingleConnectionDataSource(connection, true);
      var schema = new ResourceDatabasePopulator(new ClassPathResource("db/h2/schema.sql"));
      schema.execute(source);
      schema.execute(source);
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(
            "INSERT INTO branch(branch_id,name,active) VALUES(1,'Kenridge',true)");
        statement.executeUpdate(
            "INSERT INTO customers(first_name,phone1,house_number,street,preferred_store) VALUES('Test','+27820000000','1','Test Road','Kenridge')");
        assertThrows(
            java.sql.SQLException.class,
            () -> statement.executeUpdate("INSERT INTO customers(phone1) VALUES('+27820000000')"));
        try (var rows =
            statement.executeQuery(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")) {
          rows.next();
          assertTrue(rows.getInt(1) >= 40);
        }
      }
    }
    try (var connection = DriverManager.getConnection(url, "test", "temporary-test-password");
        var statement = connection.createStatement()) {
      try (var rows = statement.executeQuery("SELECT first_name,street FROM customers")) {
        assertTrue(rows.next());
        assertEquals("Test", rows.getString(1));
        assertEquals("Test Road", rows.getString(2));
      }
      for (String query :
          new String[] {
            "SELECT additional_price FROM modifier_option",
            "SELECT protein_quantity_required FROM burger_recipe_assignment",
            "SELECT allow_customization FROM special_component",
            "SELECT approved_clock_in_at FROM driver_settlement",
            "SELECT details FROM driver_dispatch_event",
            "SELECT country,province,latitude,longitude FROM customers"
          }) {
        try (var ignored = statement.executeQuery(query)) {
          assertNotNull(ignored);
        }
      }
    }
    assertThrows(
        java.sql.SQLException.class,
        () -> DriverManager.getConnection(url, "test", "wrong-password"));
  }
}
