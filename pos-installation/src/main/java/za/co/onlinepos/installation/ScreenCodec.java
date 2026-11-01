package za.co.onlinepos.installation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Canonical transport representation used for immutable screen hashes and import/export. */
public final class ScreenCodec {
  private ScreenCodec() {}

  public static final ObjectMapper JSON =
      JsonFiles.JSON.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  public static String encode(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid screen JSON", e);
    }
  }

  public static String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(encode(value).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }
}
