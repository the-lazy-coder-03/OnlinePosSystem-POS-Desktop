package za.co.onlinepos.server.catalogue;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.CatalogueManagement;

@RestController
@Profile({"installed", "local-store"})
@RequestMapping("/api/local/catalogue")
public class CatalogueManagementEndpoints {
  private final CatalogueManagementService service;

  public CatalogueManagementEndpoints(CatalogueManagementService service) {
    this.service = service;
  }

  @GetMapping("/recipes/price/{price}")
  public JsonNode recipeForPrice(
      HttpServletRequest request,
      @org.springframework.web.bind.annotation.PathVariable("price") java.util.UUID price,
      @org.springframework.web.bind.annotation.RequestParam(name = "family", required = false)
          String family) {
    return service.recipeForPrice(
        request.getHeader("Authorization").substring(7),
        (String) request.getAttribute("pos.terminal"),
        price,
        family);
  }

  @GetMapping("/recipes/{kind}/{id}")
  public JsonNode recipe(
      HttpServletRequest request,
      @org.springframework.web.bind.annotation.PathVariable("kind") String kind,
      @org.springframework.web.bind.annotation.PathVariable("id") long id,
      @org.springframework.web.bind.annotation.RequestParam(name = "family", required = false)
          String family) {
    return za.co.onlinepos.server.recipes.RecipePlanner.view(
        state(request), new za.co.onlinepos.contracts.Recipes.Product(kind, id), family);
  }

  @PutMapping("/recipes")
  public JsonNode saveRecipe(
      HttpServletRequest request, @RequestBody za.co.onlinepos.contracts.Recipes.Save save) {
    return edit(
        request,
        new CatalogueManagement.Edit(
            save.commandId(),
            save.expectedRevision(),
            java.util.List.of(),
            save.confirmSharedRecipe(),
            save));
  }

  @GetMapping("/products/{kind}/{id}")
  public JsonNode product(
      HttpServletRequest request,
      @org.springframework.web.bind.annotation.PathVariable("kind") String kind,
      @org.springframework.web.bind.annotation.PathVariable("id") long id) {
    return service.product(
        request.getHeader("Authorization").substring(7),
        (String) request.getAttribute("pos.terminal"),
        kind,
        id);
  }

  @PutMapping("/products")
  public JsonNode saveProduct(
      HttpServletRequest request, @RequestBody za.co.onlinepos.contracts.ProductEdits.Save save) {
    return service.saveProduct(
        request.getHeader("Authorization").substring(7),
        (String) request.getAttribute("pos.terminal"),
        save);
  }

  @PutMapping("/recipes/shared")
  public JsonNode shared(
      HttpServletRequest request, @RequestBody za.co.onlinepos.contracts.Recipes.Save save) {
    return edit(
        request,
        new CatalogueManagement.Edit(
            save.commandId(),
            save.expectedRevision(),
            java.util.List.of(),
            true,
            save,
            null,
            true));
  }

  @GetMapping("/state")
  public JsonNode state(HttpServletRequest request) {
    return service.state(
        request.getHeader("Authorization").substring(7),
        (String) request.getAttribute("pos.terminal"));
  }

  @PutMapping("/records")
  public JsonNode edit(HttpServletRequest request, @RequestBody CatalogueManagement.Edit edit) {
    return service.edit(
        request.getHeader("Authorization").substring(7),
        (String) request.getAttribute("pos.terminal"),
        edit);
  }
}
