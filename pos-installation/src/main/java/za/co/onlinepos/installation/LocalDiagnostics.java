package za.co.onlinepos.installation;

import java.util.Map;

public final class LocalDiagnostics implements DiagnosticsService {
  private final InstallationRepository installations;

  public LocalDiagnostics(InstallationRepository installations) {
    this.installations = installations;
  }

  public Map<String, String> inspect() throws Exception {
    var state = installations.load();
    return state.isEmpty()
        ? Map.of("installation", "NOT_CONFIGURED", "sales", "UNAVAILABLE")
        : Map.of(
            "installation",
            state.get().state(),
            "role",
            state.get().role().name(),
            "sales",
            "UNAVAILABLE",
            "automaticPromotion",
            "DISABLED");
  }
}
