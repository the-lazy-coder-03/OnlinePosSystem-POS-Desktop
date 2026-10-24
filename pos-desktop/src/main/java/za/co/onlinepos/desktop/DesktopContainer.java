package za.co.onlinepos.desktop;

import jakarta.enterprise.inject.Instance;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import za.co.onlinepos.desktop.setup.DesktopComponents;
import za.co.onlinepos.desktop.setup.SetupPresenter;

public final class DesktopContainer implements AutoCloseable {
  private final WeldContainer container;
  private final Instance.Handle<DesktopRoot> root;

  public DesktopContainer() {
    WeldContainer initialized =
        new Weld()
            .disableDiscovery()
            .addBeanClasses(DesktopRoot.class, DesktopComponents.class, SetupPresenter.class)
            .initialize();
    try {
      root = initialized.select(DesktopRoot.class).getHandle();
      container = initialized;
    } catch (RuntimeException exception) {
      initialized.close();
      throw exception;
    }
  }

  SetupWizard view() {
    return root.get().view();
  }

  public void close() {
    try {
      root.destroy();
    } finally {
      container.close();
    }
  }
}
