package za.co.onlinepos.contracts;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Stage 3 draft commands. No sale acceptance, tender or cloud transport contract. */
public final class LocalCommands {
  private LocalCommands() {}

  public record PriceEdit(
      UUID commandId, long expectedRevision, BigDecimal amount, boolean available) {}

  public record DraftLine(UUID lineId, UUID priceId, long expectedPriceRevision, int quantity) {}

  public record CreateDraft(UUID commandId, UUID orderId, List<DraftLine> lines) {}

  public record CancelDraft(UUID commandId, long expectedRevision) {}
}
