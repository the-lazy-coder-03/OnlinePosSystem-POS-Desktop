package za.co.onlinepos.installation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.screen.SalesScreens;
import za.co.onlinepos.contracts.screen.ScreenDocuments;

class ScreenCacheTest {
  @TempDir Path directory;

  @Test
  void orderedDurableDeliveryAndSafeActivation() throws Exception {
    var store = UUID.randomUUID();
    var doc =
        ScreenDocuments.starter(
            store, new ItemCustomization.Catalog(1, Map.of(), Map.of(), Map.of(), null, false));
    var p =
        new SalesScreens.Publication(
            UUID.randomUUID(), store, 1, doc.screenId(), 1, ScreenCodec.hash(doc), doc);
    var file = directory.resolve("cache.json");
    var cache = new ScreenCache(file, store);
    assertTrue(cache.receive(p));
    assertFalse(cache.receive(p));
    var conflicting =
        new SalesScreens.Publication(UUID.randomUUID(), store, 1, doc.screenId(), 1, p.hash(), doc);
    var existing = cache;
    assertThrows(IllegalArgumentException.class, () -> existing.receive(conflicting));
    assertFalse(cache.activate(false, false));
    assertFalse(cache.activate(true, true));
    cache = new ScreenCache(file, store);
    assertTrue(cache.pending());
    assertTrue(cache.activate(true, false));
    assertEquals(p, new ScreenCache(file, store).active());
    var gap =
        new SalesScreens.Publication(
            UUID.randomUUID(), store, 3, doc.screenId(), 1, ScreenCodec.hash(doc), doc);
    var finalCache = cache;
    assertThrows(IllegalArgumentException.class, () -> finalCache.receive(gap));
    var wrong =
        new SalesScreens.Publication(
            UUID.randomUUID(), UUID.randomUUID(), 2, doc.screenId(), 1, p.hash(), doc);
    assertThrows(IllegalArgumentException.class, () -> finalCache.receive(wrong));
    var bad =
        new SalesScreens.Publication(
            UUID.randomUUID(), store, 2, doc.screenId(), 1, "0".repeat(64), doc);
    assertThrows(IllegalArgumentException.class, () -> finalCache.receive(bad));
  }
}
