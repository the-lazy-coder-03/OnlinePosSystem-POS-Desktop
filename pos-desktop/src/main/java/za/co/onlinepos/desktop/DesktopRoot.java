package za.co.onlinepos.desktop;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import za.co.onlinepos.desktop.setup.SetupPresenter;

@Dependent
public class DesktopRoot {
  private final SetupPresenter presenter;

  @Inject
  public DesktopRoot(SetupPresenter presenter) {
    this.presenter = presenter;
  }

  SetupWizard view() {
    return new SetupWizard(presenter);
  }
}
