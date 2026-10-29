package za.co.onlinepos.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import za.co.onlinepos.contracts.Recipes;
import za.co.onlinepos.installation.JsonFiles;

@EnabledIfEnvironmentVariable(named = "POS_TEST_FX", matches = "true")
class RecipeEditorTest {
  @BeforeAll
  static void start() throws Exception {
    FxTestSupport.start();
  }

  static class Gateway extends ModuleGateway {
    final LinkedHashSet<String> selected = new LinkedHashSet<>(List.of("ingredient:1"));
    final List<Recipes.Save> saves = new ArrayList<>();
    long revision = 1;
    boolean fail = true;

    Gateway() {
      super(Path.of("."));
    }

    @Override
    public synchronized JsonNode catalogue(String path, String method, Object payload, String token)
        throws Exception {
      if (method.equals("PUT")) {
        var save = (Recipes.Save) payload;
        saves.add(save);
        if (fail) {
          fail = false;
          throw new za.co.onlinepos.installation.TrustedStoreClient.ModuleFailure(503);
        }
        selected.clear();
        selected.addAll(save.selected());
        revision++;
        return JsonFiles.JSON.valueToTree(Map.of("revision", revision));
      }
      if (path.equals("state"))
        return JsonFiles.JSON.readTree(
            "{\"revision\":"
                + revision
                + ",\"tables\":{\"price_category\":[],\"pizza\":[],\"menu_item\":[]}}");
      var rows = new ArrayList<Map<String, Object>>();
      for (int i = 1; i <= 3; i++) {
        String key = "ingredient:" + i;
        rows.add(
            Map.of(
                "key",
                key,
                "name",
                i == 1 ? "Ham" : i == 2 ? "Mozzarella" : "Unavailable",
                "available",
                i < 3,
                "compatible",
                true,
                "selected",
                selected.contains(key),
                "origins",
                selected.contains(key)
                    ? List.of(
                        Map.of(
                            "table",
                            "pizza_default_ingredient",
                            "properties",
                            Map.of("default_qty", 2, "is_removable", false, "sort_order", 7)))
                    : List.of()));
      }
      return JsonFiles.JSON.valueToTree(
          Map.of(
              "revision",
              revision,
              "editable",
              true,
              "product",
              new Recipes.Product("pizza", 1),
              "family",
              "ingredient",
              "ingredients",
              rows,
              "affectedItems",
              List.of()));
    }
  }

  @Test
  void filteringVisibleActionsAndFailedSaveRetainSelectionsAndCommandIdentity() throws Exception {
    var gateway = new Gateway();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var editor =
          GridModulesLiveTest.fx(
              () ->
                  new RecipeEditor(
                      gateway, executor, null, new Recipes.Product("pizza", 1), () -> {}));
      var stage =
          GridModulesLiveTest.fx(
              () -> {
                var s = new Stage();
                s.setScene(new Scene(editor, 1100, 700));
                s.show();
                return s;
              });
      try {
        GridModulesLiveTest.await(() -> editor.lookup("#recipe-ingredient-ingredient-1") != null);
        GridModulesLiveTest.fx(
            () -> {
              assertTrue(
                  ((CheckBox) editor.lookup("#recipe-ingredient-ingredient-1")).isSelected());
              assertTrue(
                  ((CheckBox) editor.lookup("#recipe-ingredient-ingredient-3")).isDisabled());
              ((TextField) editor.lookup("#recipe-search")).setText("Mozzarella");
              ((Button) editor.lookup("#recipe-select-visible")).fire();
              return null;
            });
        GridModulesLiveTest.fx(
            () -> {
              ((TextField) editor.lookup("#recipe-search")).clear();
              assertTrue(
                  ((CheckBox) editor.lookup("#recipe-ingredient-ingredient-1")).isSelected());
              assertTrue(
                  ((CheckBox) editor.lookup("#recipe-ingredient-ingredient-2")).isSelected());
              ((TextField) editor.lookup("#recipe-search")).setText("Ham");
              ((Button) editor.lookup("#recipe-clear-visible")).fire();
              ((TextField) editor.lookup("#recipe-search")).clear();
              assertFalse(
                  ((CheckBox) editor.lookup("#recipe-ingredient-ingredient-1")).isSelected());
              assertTrue(
                  ((CheckBox) editor.lookup("#recipe-ingredient-ingredient-2")).isSelected());
              ((Button) editor.lookup("#recipe-save")).fire();
              return null;
            });
        GridModulesLiveTest.await(
            () -> ((Label) editor.lookup("#recipe-feedback")).getText().contains("retained"));
        UUID command = gateway.saves.getFirst().commandId();
        GridModulesLiveTest.fx(
            () -> {
              assertTrue(
                  ((CheckBox) editor.lookup("#recipe-ingredient-ingredient-2")).isSelected());
              ((Button) editor.lookup("#recipe-save")).fire();
              return null;
            });
        GridModulesLiveTest.await(() -> gateway.saves.size() == 2 && !editor.isDisabled());
        assertEquals(command, gateway.saves.get(1).commandId());
        assertEquals(List.of("ingredient:2"), gateway.saves.get(1).selected());
      } finally {
        GridModulesLiveTest.fx(
            () -> {
              editor.close();
              stage.close();
              return null;
            });
      }
    }
  }
}
