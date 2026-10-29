package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.InstallationRole;
import za.co.onlinepos.desktop.setup.SetupPresenter;
import za.co.onlinepos.installation.Installation;
import za.co.onlinepos.installation.LanPolicy;
import za.co.onlinepos.installation.MasterSelection;
import za.co.onlinepos.installation.SetupRequest;
import za.co.onlinepos.installation.StoreProfile;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class SetupWizardTest {
  @BeforeAll
  static void start() throws Exception {
    FxTestSupport.start();
  }

  static class Fixture implements AutoCloseable {
    final java.util.concurrent.ExecutorService executor =
        Executors.newVirtualThreadPerTaskExecutor();
    final List<SetupRequest> requests = new ArrayList<>();
    final MasterSelection master =
        new MasterSelection(
            "https://192.168.1.20:18443",
            789,
            UUID.randomUUID(),
            "test-fingerprint",
            new StoreProfile("Harbour Pizza", "Harbour Company", "HARBOUR"));
    CompletableFuture<MasterSelection> inspection =
        CompletableFuture.failedFuture(new IllegalStateException("Offline"));
    final SetupWizard wizard;
    final Stage stage;

    Fixture(Installation saved, List<LanPolicy> networks, List<MasterSelection> discovered)
        throws Exception {
      var presenter =
          new SetupPresenter(null, null, null, executor, null) {
            @Override
            public CompletableFuture<Optional<Installation>> currentInstallation() {
              return CompletableFuture.completedFuture(Optional.ofNullable(saved));
            }

            @Override
            public CompletableFuture<List<LanPolicy>> networks() {
              return CompletableFuture.completedFuture(networks);
            }

            @Override
            public CompletableFuture<List<MasterSelection>> findMasters(LanPolicy network) {
              return CompletableFuture.completedFuture(discovered);
            }

            @Override
            public CompletableFuture<MasterSelection> inspectMaster(
                String address, LanPolicy network) {
              return inspection;
            }

            @Override
            public CompletableFuture<String> install(SetupRequest request, char[] secret) {
              requests.add(request);
              java.util.Arrays.fill(secret, '\0');
              return CompletableFuture.failedFuture(new IllegalStateException("Simulated failure"));
            }
          };
      wizard = GridModulesLiveTest.fx(() -> new SetupWizard(presenter));
      stage =
          GridModulesLiveTest.fx(
              () -> {
                var s = new Stage();
                var scene = new Scene(wizard, 1050, 800);
                scene
                    .getStylesheets()
                    .add(SetupWizard.class.getResource("setup.css").toExternalForm());
                s.setScene(scene);
                s.show();
                return s;
              });
      GridModulesLiveTest.await(() -> !((Button) wizard.lookup("#setup-next")).isDisabled());
    }

    void act(Runnable action) throws Exception {
      GridModulesLiveTest.fx(
          () -> {
            wizard.applyCss();
            wizard.layout();
            action.run();
            wizard.applyCss();
            wizard.layout();
            return null;
          });
    }

    void click(String id) {
      ((Button) wizard.lookup(id)).fire();
      wizard.applyCss();
      wizard.layout();
    }

    void text(String id, String value) {
      ((TextField) wizard.lookup(id)).setText(value);
    }

    String message() {
      return ((Label) wizard.lookup("#setup-feedback")).getText();
    }

    void snapshot(String name) throws Exception {
      act(
          () -> {
            try {
              var image = wizard.snapshot(null, null);
              var bitmap =
                  new java.awt.image.BufferedImage(
                      (int) image.getWidth(),
                      (int) image.getHeight(),
                      java.awt.image.BufferedImage.TYPE_INT_ARGB);
              for (int y = 0; y < bitmap.getHeight(); y++)
                for (int x = 0; x < bitmap.getWidth(); x++)
                  bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
              var path = java.nio.file.Path.of("target/setup-ui", name + ".png");
              java.nio.file.Files.createDirectories(path.getParent());
              javax.imageio.ImageIO.write(bitmap, "png", path.toFile());
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
          });
    }

    @Override
    public void close() throws Exception {
      GridModulesLiveTest.fx(
          () -> {
            stage.close();
            return null;
          });
      executor.shutdownNow();
    }
  }

  static final LanPolicy LAN = new LanPolicy("lan", "Store Ethernet", "192.168.1.10", 24);

  @Test
  void newStoreUsesAutomaticIdentityAndPreservesDetailsOnBackAndFailure() throws Exception {
    try (var f = new Fixture(null, List.of(LAN), List.of())) {
      f.snapshot("01-purpose");
      f.act(
          () -> {
            f.click("#setup-next");
            assertTrue(f.message().contains("Choose"));
            ((RadioButton) f.wizard.lookup("#setup-main")).fire();
            f.click("#setup-next");
          });
      f.act(
          () -> {
            f.text("#setup-store-name", "Oak Street Pizza");
            ((TitledPane) f.wizard.lookup("#setup-more-options")).setExpanded(true);
          });
      f.act(
          () -> {
            assertFalse(((TextField) f.wizard.lookup("#terminal-name")).getText().isBlank());
            f.text("#setup-business-name", "Pizza Company");
            f.text("#terminal-name", "Front Counter");
            ((TitledPane) f.wizard.lookup("#setup-more-options")).setExpanded(false);
          });
      f.snapshot("02-store");
      f.act(
          () -> {
            f.click("#setup-next");
            assertNotNull(f.wizard.lookup("#setup-password"));
            assertTrue(f.wizard.lookup("#store-network") == null);
            f.click("#setup-back");
            assertEquals(
                "Oak Street Pizza", ((TextField) f.wizard.lookup("#setup-store-name")).getText());
            f.click("#setup-next");
          });
      f.snapshot("03-finish");
      f.act(
          () -> {
            f.click("#setup-connect");
            assertTrue(f.message().contains("password"));
            assertTrue(f.requests.isEmpty());
            f.text("#setup-password", "test-password");
            f.click("#setup-connect");
          });
      GridModulesLiveTest.await(() -> !((Button) f.wizard.lookup("#setup-connect")).isDisabled());
      f.act(
          () -> {
            assertTrue(((PasswordField) f.wizard.lookup("#setup-password")).getText().isEmpty());
            assertTrue(f.message().contains("details are kept"));
            f.text("#setup-password", "test-password");
            f.click("#setup-connect");
          });
      f.act(
          () -> {
            assertEquals(2, f.requests.size());
            assertEquals(f.requests.get(0).storeProfile(), f.requests.get(1).storeProfile());
            assertEquals("Pizza Company", f.requests.get(0).storeProfile().businessName());
            assertTrue(f.requests.get(0).storeProfile().code().startsWith("STORE-"));
          });
    }
  }

  @Test
  void additionalTillUsesDiscoveredStoreAndNeedsNoAdministratorPassword() throws Exception {
    try (var f =
        new Fixture(
            null,
            List.of(LAN),
            List.of(
                new MasterSelection(
                    "https://192.168.1.20:18443",
                    987,
                    UUID.randomUUID(),
                    "fingerprint",
                    new StoreProfile("New Store", "Business", "NEW"))))) {
      f.act(
          () -> {
            ((RadioButton) f.wizard.lookup("#setup-till")).fire();
            f.click("#setup-next");
          });
      GridModulesLiveTest.await(
          () -> !((ComboBox<?>) f.wizard.lookup("#setup-masters")).getItems().isEmpty());
      f.act(
          () -> {
            ((ComboBox<?>) f.wizard.lookup("#setup-masters")).getSelectionModel().selectFirst();
            f.click("#setup-next");
            assertTrue(f.wizard.lookup("#setup-password") == null);
            f.click("#setup-connect");
          });
      f.act(
          () -> {
            assertEquals(987, f.requests.getFirst().branchId());
            assertEquals(InstallationRole.SLAVE, f.requests.getFirst().role());
            assertEquals(
                18443,
                java.net.URI.create(f.requests.getFirst().selectedMaster().origin()).getPort());
            assertTrue(f.requests.getFirst().storeProfile() == null);
          });
    }
  }

  @Test
  void manualAddressChecksFailureThenRetainsVerifiedSelectionOnBack() throws Exception {
    try (var f = new Fixture(null, List.of(LAN), List.of())) {
      f.act(
          () -> {
            ((RadioButton) f.wizard.lookup("#setup-till")).fire();
            f.click("#setup-next");
          });
      f.act(
          () -> {
            ((TitledPane) f.wizard.lookup("#setup-manual-address")).setExpanded(true);
          });
      f.act(
          () -> {
            f.text("#master-lan-ip", "192.168.1.20");
            f.click("#setup-check-master");
          });
      f.act(
          () -> {
            assertTrue(f.message().contains("Cannot connect"));
            f.inspection = CompletableFuture.completedFuture(f.master);
            f.click("#setup-check-master");
          });
      f.act(
          () -> {
            f.click("#setup-next");
            f.click("#setup-back");
            ((TitledPane) f.wizard.lookup("#setup-manual-address")).setExpanded(true);
          });
      f.act(
          () -> {
            assertEquals("192.168.1.20", ((TextField) f.wizard.lookup("#master-lan-ip")).getText());
            f.click("#setup-next");
            f.click("#setup-connect");
          });
      f.act(() -> assertEquals(789, f.requests.getFirst().branchId()));
    }
  }

  @Test
  void resumeKeepsArbitraryStoreIdAndRequiresNetworkChoiceWhenAmbiguous() throws Exception {
    var profile = new StoreProfile("Third Store", "Business", "THIRD");
    var saved =
        new Installation(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            123,
            InstallationRole.MASTER,
            "Original name",
            "CONFIGURING",
            profile);
    try (var f =
        new Fixture(
            saved,
            List.of(LAN, new LanPolicy("lan2", "Other Ethernet", "192.168.2.10", 24)),
            List.of())) {
      f.act(() -> f.click("#setup-next"));
      f.act(
          () -> {
            assertTrue(((TextField) f.wizard.lookup("#setup-store-name")).isDisabled());
            f.click("#setup-next");
            assertTrue(f.message().contains("network"));
          });
      // Main-computer network choice must be available before Continue can succeed.
      f.act(
          () -> {
            ((ComboBox<?>) f.wizard.lookup("#store-network")).getSelectionModel().selectFirst();
            f.click("#setup-next");
            f.text("#setup-password", "test-password");
            f.click("#setup-connect");
          });
      f.act(
          () -> {
            assertEquals(123, f.requests.getFirst().branchId());
            assertEquals(profile, f.requests.getFirst().storeProfile());
          });
    }
  }
}
