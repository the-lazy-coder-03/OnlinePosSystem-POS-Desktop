package za.co.onlinepos.contracts;

import java.util.Objects;
import java.util.UUID;

/** Reuse this identity unchanged after a timeout. Durable deduplication is added in Stage 3. */
public record CommandIdentity(
    UUID commandId, UUID terminalId, OrderIdentity order, long expectedRevision) {
  public CommandIdentity {
    Objects.requireNonNull(commandId, "commandId");
    Objects.requireNonNull(terminalId, "terminalId");
    Objects.requireNonNull(order, "order");
    if (commandId.equals(new UUID(0, 0)) || terminalId.equals(new UUID(0, 0))) {
      throw new IllegalArgumentException("command and terminal IDs must not be nil");
    }
    if (expectedRevision < 0) {
      throw new IllegalArgumentException("expectedRevision must not be negative");
    }
  }
}
