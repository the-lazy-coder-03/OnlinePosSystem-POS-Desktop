package za.co.onlinepos.server.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Resolves identity before verification. Ambiguous PIN lookups never choose an arbitrary row. */
public final class PinIdentitySelection {
  private PinIdentitySelection() {}

  public static Map<String, Object> select(
      List<Map<String, Object>> candidates, UUID selectedStaff) {
    var eligible =
        candidates.stream()
            .filter(row -> Boolean.TRUE.equals(row.get("enabled")))
            .filter(row -> selectedStaff == null || selectedStaff.equals(row.get("staff_id")))
            .toList();
    return eligible.size() == 1 ? eligible.getFirst() : null;
  }
}
