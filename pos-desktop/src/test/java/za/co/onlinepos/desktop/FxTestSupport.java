package za.co.onlinepos.desktop;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;

final class FxTestSupport {
  private FxTestSupport() {}

  static void start() throws Exception {
    var ready = new CountDownLatch(1);
    try {
      Platform.startup(
          () -> {
            Platform.setImplicitExit(false);
            ready.countDown();
          });
    } catch (IllegalStateException alreadyRunning) {
      Platform.runLater(ready::countDown);
    }
    if (!ready.await(15, TimeUnit.SECONDS))
      throw new IllegalStateException("JavaFX startup timed out");
  }
}
