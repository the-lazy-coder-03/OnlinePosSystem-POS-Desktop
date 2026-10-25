package za.co.onlinepos.contracts;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Complete branch catalogue only; never staff, customers, credentials or orders. */
public record CatalogueSnapshot(
    int protocolVersion,
    UUID snapshotId,
    int branchId,
    String sourceRevision,
    String capturedAt,
    String pricingPolicy,
    Map<String, List<Map<String, Object>>> tables,
    Map<String, Integer> counts,
    Map<String, String> hashes) {
  public record Import(UUID commandId, String expectedSha256, CatalogueSnapshot snapshot) {}
}
