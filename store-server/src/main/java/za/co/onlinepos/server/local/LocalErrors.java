package za.co.onlinepos.server.local;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Profile({"installed", "local-store"})
public class LocalErrors {
  @ExceptionHandler(LocalFailure.class)
  ResponseEntity<Map<String, String>> failure(LocalFailure failure) {
    return ResponseEntity.status(failure.status()).body(Map.of("error", failure.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
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
}
