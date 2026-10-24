package za.co.onlinepos.contracts;

/** Process liveness is deliberately separate from permission or readiness to accept sales. */
public record HealthResponse(
    int protocolVersion,
    String service,
    String status,
    String role,
    boolean acceptingSales,
    boolean cloudSyncEnabled,
    boolean automaticPromotionEnabled) {
  public static HealthResponse foundation() {
    return new HealthResponse(
        1, "onlinepos-store-server", "UP", "UNCONFIGURED", false, false, false);
  }
}
