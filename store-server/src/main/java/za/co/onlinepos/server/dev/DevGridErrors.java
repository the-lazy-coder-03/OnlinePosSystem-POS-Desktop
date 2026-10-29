package za.co.onlinepos.server.dev;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import za.co.onlinepos.contracts.DevData;
import za.co.onlinepos.contracts.ItemCustomization;

@RestControllerAdvice(assignableTypes = DevGridEndpoints.class)
@Profile("dev")
public class DevGridErrors {
  @ExceptionHandler(za.co.onlinepos.server.local.ProductDraftFailure.class)
  ResponseEntity<java.util.Map<String, String>> product(
      za.co.onlinepos.server.local.ProductDraftFailure failure) {
    return ResponseEntity.badRequest()
        .body(
            java.util.Map.of(
                "code",
                "PRODUCT_VALIDATION",
                "section",
                failure.section(),
                "field",
                failure.field(),
                "message",
                failure.getMessage()));
  }

  @ExceptionHandler(DevCategoryService.Failure.class)
  ResponseEntity<DevData.Error> failure(DevCategoryService.Failure failure) {
    return ResponseEntity.status(failure.status())
        .body(new DevData.Error(failure.code(), failure.field(), failure.getMessage()));
  }

  @ExceptionHandler(ItemCustomization.Stale.class)
  ResponseEntity<DevData.Error> stale() {
    return ResponseEntity.status(409)
        .body(new DevData.Error("REVISION_CONFLICT", "", "Catalogue changed; reload and review"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<DevData.Error> invalid() {
    return ResponseEntity.badRequest()
        .body(new DevData.Error("VALIDATION", "", "Invalid fields or selections; review the form"));
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  ResponseEntity<DevData.Error> database() {
    return ResponseEntity.status(503)
        .body(
            new DevData.Error("DATABASE_UNAVAILABLE", "", "Database operation was not confirmed"));
  }
}
