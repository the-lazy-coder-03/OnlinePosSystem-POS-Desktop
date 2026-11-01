package za.co.onlinepos.contracts;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Stage 3 draft commands. No sale acceptance, tender or cloud transport contract. */
public final class LocalCommands {
  private LocalCommands() {}

  public record PriceEdit(
      UUID commandId, long expectedRevision, BigDecimal amount, boolean available) {}

  public record DraftLine(
      UUID lineId,
      UUID priceId,
      long expectedPriceRevision,
      int quantity,
      ItemCustomization.Selection customization,
      String notes) {
    public DraftLine(
        UUID lineId,
        UUID priceId,
        long expectedPriceRevision,
        int quantity,
        ItemCustomization.Selection customization) {
      this(lineId, priceId, expectedPriceRevision, quantity, customization, "");
    }

    public DraftLine {
      notes = notes == null ? "" : notes;
      if (notes.length() > 500
          || notes.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))
        throw new IllegalArgumentException("Notes must be at most 500 printable characters");
    }

    public DraftLine(UUID lineId, UUID priceId, long expectedPriceRevision, int quantity) {
      this(lineId, priceId, expectedPriceRevision, quantity, null);
    }
  }

  public record CreateDraft(UUID commandId, UUID orderId, List<DraftLine> lines, UUID customerId) {
    public CreateDraft(UUID commandId, UUID orderId, List<DraftLine> lines) {
      this(commandId, orderId, lines, null);
    }
  }

  public record EditDraft(
      UUID commandId, long expectedRevision, List<DraftLine> lines, UUID customerId) {}

  public record CancelDraft(UUID commandId, long expectedRevision) {}
}
