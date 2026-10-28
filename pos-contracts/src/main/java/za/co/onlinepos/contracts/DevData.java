package za.co.onlinepos.contracts;

import java.util.List;
import java.util.UUID;

/** Local development data manager contracts; never an installed-terminal API. */
public final class DevData {
  private DevData() {}

  public record Category(
      int id, String name, int sortOrder, boolean active, long revision, boolean archived) {
    public Category(int id, String name, int sortOrder, boolean active, long revision) {
      this(id, name, sortOrder, active, revision, false);
    }
  }

  public record Page(List<Category> records, long total, int page, int size) {}

  public record Save(
      UUID commandId,
      Long expectedRevision,
      String name,
      Integer sortOrder,
      Boolean active,
      Boolean archived) {
    public Save(
        UUID commandId, Long expectedRevision, String name, Integer sortOrder, Boolean active) {
      this(commandId, expectedRevision, name, sortOrder, active, null);
    }
  }

  public record Status(
      String application,
      String profile,
      String database,
      String connection,
      String instanceId,
      List<String> entities) {}

  public record Error(String code, String field, String message) {}
}
