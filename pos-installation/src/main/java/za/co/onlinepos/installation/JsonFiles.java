package za.co.onlinepos.installation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class JsonFiles {
  public static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private JsonFiles() {}

  public static void write(Path path, Object value) throws IOException {
    writeBytes(path, JSON.writeValueAsBytes(value));
  }

  public static void writeBytes(Path path, byte[] bytes) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".pending-", ".tmp");
    try {
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      Files.move(
          temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
