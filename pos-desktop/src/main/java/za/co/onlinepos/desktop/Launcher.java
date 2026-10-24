package za.co.onlinepos.desktop;

import javafx.application.Application;

/** Separate launcher supports the non-modular development classpath. */
public final class Launcher {
  private Launcher() {}

  public static void main(String[] args) {
    Application.launch(PosApplication.class, args);
  }
}
