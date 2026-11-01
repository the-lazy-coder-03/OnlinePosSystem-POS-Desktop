package za.co.onlinepos.server.screen;

import java.sql.Connection;
import java.sql.SQLException;
import org.h2.api.Trigger;

/** H2 mirror of immutable PostgreSQL configuration history. */
public final class ImmutableScreenTrigger implements Trigger {
  @Override
  public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
    throw new SQLException("Screen history is immutable", "42501");
  }
}
