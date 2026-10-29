package za.co.onlinepos.server.local;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Profile({"installed", "local-store"})
public class LocalErrors {
  @ExceptionHandler(za.co.onlinepos.server.local.ProductDraftFailure.class)
  ResponseEntity<Map<String, String>> product(
      za.co.onlinepos.server.local.ProductDraftFailure failure) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "code",
                "PRODUCT_VALIDATION",
                "section",
                failure.section(),
                "field",
                failure.field(),
                "message",
                failure.getMessage()));
  }

  @ExceptionHandler(LocalFailure.class)
  ResponseEntity<Map<String, String>> failure(LocalFailure failure) {
    return ResponseEntity.status(failure.status()).body(Map.of("error", failure.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    java.time.DateTimeException.class,
    ArithmeticException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<Map<String, String>> invalid(Exception exception) {
    return ResponseEntity.badRequest().body(Map.of("error", "Invalid request"));
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ResponseEntity<Map<String, String>> conflict(Exception exception) {
    return ResponseEntity.status(409).body(Map.of("error", "Identity or data conflict"));
  }

  @ExceptionHandler({
    org.springframework.transaction.TransactionSystemException.class,
    org.springframework.dao.DataAccessException.class
  })
  ResponseEntity<Map<String, String>> database(RuntimeException exception) {
    Throwable cause = exception;
    while (cause != null) {
      if (cause instanceof java.sql.SQLException sql) {
        String state = sql.getSQLState();
        if ("42501".equals(state))
          return ResponseEntity.status(403).body(Map.of("error", "Permission required"));
        if ("28000".equals(state))
          return ResponseEntity.status(401).body(Map.of("error", "Session expired"));
        if (state != null && state.startsWith("23"))
          return ResponseEntity.status(409)
              .body(Map.of("error", "Account or revision conflict. Reload before retrying."));
      }
      cause = cause.getCause();
    }
    return ResponseEntity.status(503).body(Map.of("error", "Store database unavailable"));
  }
}
