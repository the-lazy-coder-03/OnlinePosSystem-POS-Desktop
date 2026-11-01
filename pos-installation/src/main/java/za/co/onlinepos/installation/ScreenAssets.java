package za.co.onlinepos.installation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import za.co.onlinepos.contracts.screen.SalesScreens;

/** Content-addressed local assets. Never resolves paths or URLs from imported documents. */
public final class ScreenAssets {
  private ScreenAssets() {}

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  public static Set<String> required(SalesScreens.Document d) {
    return d.pages().stream()
        .flatMap(p -> p.widgets().stream())
        .filter(w -> w.style().assetHash() != null)
        .map(w -> w.style().assetHash())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Path path(Path cache, String hash) {
    if (hash == null || !hash.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("Invalid asset hash");
    return cache.toAbsolutePath().getParent().resolve("screen-assets").resolve(hash);
  }

  public static byte[] read(Path cache, String hash) throws java.io.IOException {
    var file = path(cache, hash);
    if (Files.size(file) > 524288) throw new IllegalArgumentException("Oversized asset");
    var content = Files.readAllBytes(file);
    if (!hash.equals(hash(content))) throw new IllegalArgumentException("Asset hash mismatch");
    return content;
  }

  public static void write(Path cache, SalesScreens.Asset asset) throws java.io.IOException {
    if (asset.content() == null
        || asset.content().length > 524288
        || !asset.hash().equals(hash(asset.content()))
        || !("image/png".equals(asset.mediaType()) || "image/jpeg".equals(asset.mediaType())))
      throw new IllegalArgumentException("Invalid asset");
    JsonFiles.writeBytes(path(cache, asset.hash()), asset.content());
  }
}
