package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import org.jboss.weld.environment.se.Weld;
import org.junit.jupiter.api.Test;

class DesktopCdiTest {
  interface Dependency {}

  @Dependent
  static class NeedsDependency {
    @Inject
    NeedsDependency(Dependency dependency) {}
  }

  @Dependent
  static class First implements Dependency {}

  @Dependent
  static class Second implements Dependency {}

  @Test
  void productionContainerStartsAndClosesWithoutConstructingControlsOffTheFxThread() {
    try (var container = new DesktopContainer()) {
      assertNotNull(container);
    }
  }

  @Test
  void missingDependencyFailsStartup() {
    assertThrows(
        org.jboss.weld.exceptions.DeploymentException.class,
        () -> new Weld().disableDiscovery().addBeanClasses(NeedsDependency.class).initialize());
  }

  @Test
  void ambiguousDependencyFailsStartup() {
    assertThrows(
        org.jboss.weld.exceptions.DeploymentException.class,
        () ->
            new Weld()
                .disableDiscovery()
                .addBeanClasses(NeedsDependency.class, First.class, Second.class)
                .initialize());
  }
}
