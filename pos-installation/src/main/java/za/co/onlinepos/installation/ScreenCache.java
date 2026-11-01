package za.co.onlinepos.installation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import za.co.onlinepos.contracts.screen.SalesScreens.Publication;
import za.co.onlinepos.contracts.screen.ScreenValidation;

/**
 * Durable ordered configuration inbox. Transaction replication deliberately has no dependency here.
 */
public final class ScreenCache {
  public record Receipt(UUID eventId, long sequence, String hash) {}

  public record State(
      UUID storeId, Publication active, Publication pending, java.util.List<Receipt> receipts) {}

  private final Path path;
  private final UUID store;
  private State state;

  public ScreenCache(Path path, UUID store) {
    this.path = path;
    this.store = store;
    state = new State(store, null, null, java.util.List.of());
    try {
      if (Files.exists(path) && Files.size(path) <= 12 * 1024 * 1024) {
        var loaded = ScreenCodec.JSON.readValue(Files.readAllBytes(path), State.class);
        if (store.equals(loaded.storeId()) && loaded.receipts() != null) {
          check(loaded.active());
          state = new State(store, loaded.active(), null, loaded.receipts());
          try {
            check(loaded.pending());
            state = loaded;
          } catch (IllegalArgumentException incomplete) {
            long active = loaded.active() == null ? 0 : loaded.active().sequence();
            state =
                new State(
                    store,
                    loaded.active(),
                    null,
                    loaded.receipts().stream().filter(r -> r.sequence() <= active).toList());
          }
        }
      }
    } catch (Exception invalid) {
      /* Retain no unverified configuration. Fetch again from authenticated Master. */
    }
  }

  private void check(Publication p) {
    if (p == null) return;
    if (!store.equals(p.storeId())
        || !store.equals(p.document().storeId())
        || p.sequence() < 1
        || !p.screenId().equals(p.document().screenId())
        || !ScreenCodec.hash(p.document()).equals(p.hash()))
      throw new IllegalArgumentException("Invalid publication hash or store");
    for (String hash : ScreenAssets.required(p.document()))
      try {
        ScreenAssets.read(path, hash);
      } catch (Exception invalid) {
        throw new IllegalArgumentException("Missing or invalid publication asset");
      }
    var prices =
        p.document().pages().stream()
            .flatMap(page -> page.widgets().stream())
            .filter(w -> w.binding().containsKey("priceId"))
            .map(w -> ScreenValidation.uuid(w.binding().get("priceId")))
            .collect(java.util.stream.Collectors.toSet());
    ScreenValidation.validate(p.document(), store, prices, ScreenValidation.Limits.standard());
  }

  public synchronized long received() {
    return state.pending() != null
        ? state.pending().sequence()
        : state.active() == null ? 0 : state.active().sequence();
  }

  public synchronized Publication active() {
    return state.active();
  }

  public synchronized boolean pending() {
    return state.pending() != null;
  }

  public synchronized boolean receive(Publication p) throws java.io.IOException {
    check(p);
    long current = received();
    for (var receipt : state.receipts())
      if (receipt.sequence() == p.sequence() || receipt.eventId().equals(p.eventId())) {
        if (receipt.sequence() != p.sequence()
            || !receipt.eventId().equals(p.eventId())
            || !receipt.hash().equals(p.hash()))
          throw new IllegalArgumentException("Conflicting publication identity");
        return false;
      }
    if (p.sequence() <= current)
      throw new IllegalArgumentException("Unknown stale publication identity");
    if (p.sequence() != current + 1)
      throw new IllegalArgumentException("Publication gap; request configuration catch-up");
    var receipts = new java.util.ArrayList<>(state.receipts());
    receipts.add(new Receipt(p.eventId(), p.sequence(), p.hash()));
    var next = new State(store, state.active(), p, java.util.List.copyOf(receipts));
    persist(next);
    return true;
  }

  public synchronized boolean activate(boolean orderEmpty, boolean interactionPending)
      throws java.io.IOException {
    if (!orderEmpty || interactionPending || state.pending() == null) return false;
    persist(new State(store, state.pending(), null, state.receipts()));
    return true;
  }

  private void persist(State next) throws java.io.IOException {
    JsonFiles.writeBytes(path, ScreenCodec.JSON.writeValueAsBytes(next));
    state = next;
  }
}
