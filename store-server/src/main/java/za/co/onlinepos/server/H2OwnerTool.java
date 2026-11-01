package za.co.onlinepos.server;

import java.util.Arrays;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import za.co.onlinepos.server.dev.H2LocalAuthentication;

/** Explicit trusted local enrolment, intentionally absent from the HTTP development actor API. */
public final class H2OwnerTool {
  private H2OwnerTool() {}

  public static void main(String[] args) {
    if (args.length != 1 || !java.nio.file.Path.of(args[0]).isAbsolute())
      throw new IllegalArgumentException(
          "Supply the absolute existing H2 development data directory. Stop its backend first.");
    if (!java.nio.file.Files.isRegularFile(
        java.nio.file.Path.of(args[0]).resolve("onlinepos.mv.db")))
      throw new IllegalArgumentException("Existing development database required");
    var console = System.console();
    if (console == null) throw new IllegalStateException("Interactive protected console required");
    String username = console.readLine("New local owner username: ");
    char[] pin = console.readPassword("Six-digit owner PIN: ");
    char[] repeat = console.readPassword("Repeat PIN: ");
    try {
      if (!Arrays.equals(pin, repeat)) throw new IllegalArgumentException("PINs do not match");
      try (var context =
          new SpringApplicationBuilder(StoreServerApplication.class)
              .profiles("dev")
              .run("--server.port=0", "--pos.dev.data-dir=" + args[0])) {
        context
            .getBean(H2LocalAuthentication.class)
            .bootstrapOwner(UUID.randomUUID(), username, pin);
        console.printf(
            "Owner enrolled. Restart the development backend and unlock Sales Screen Builder with this PIN.%n");
      }
    } finally {
      Arrays.fill(pin, '\0');
      Arrays.fill(repeat, '\0');
    }
  }
}
