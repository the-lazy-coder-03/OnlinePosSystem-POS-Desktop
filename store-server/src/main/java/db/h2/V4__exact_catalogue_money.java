package db.h2;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Preflight all values before changing any column; never silently round an existing amount. */
public class V4__exact_catalogue_money extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws Exception {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var fields = new ArrayList<String[]>();
    try (var input = getClass().getResourceAsStream("/catalogue-schema-v1.json")) {
      var schema = json.readTree(input);
      for (var table : schema.properties())
        for (var column : table.getValue().path("columns").properties())
          if (List.of("price", "additional_price", "bundle_price").contains(column.getKey()))
            fields.add(new String[] {table.getKey(), column.getKey()});
    }
    for (var field : fields)
      try (var statement = context.getConnection().createStatement();
          var rows = statement.executeQuery("SELECT " + field[1] + " FROM public." + field[0])) {
        while (rows.next()) {
          BigDecimal value = rows.getBigDecimal(1);
          if (value != null) {
            try {
              value = value.setScale(2, RoundingMode.UNNECESSARY);
              if (value.precision() > 18) throw new ArithmeticException();
            } catch (ArithmeticException lossy) {
              throw new IllegalStateException(
                  "Monetary preflight failed for "
                      + field[0]
                      + "."
                      + field[1]
                      + "; original values retained");
            }
          }
        }
      }
    for (var field : fields)
      try (var statement = context.getConnection().createStatement()) {
        statement.execute(
            "ALTER TABLE public." + field[0] + " ALTER COLUMN " + field[1] + " DECIMAL(18,2)");
      }
  }
}
