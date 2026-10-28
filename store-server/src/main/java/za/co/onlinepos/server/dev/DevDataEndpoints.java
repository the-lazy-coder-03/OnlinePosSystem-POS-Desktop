package za.co.onlinepos.server.dev;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.DevData;

@RestController
@Profile("dev")
@RequestMapping("/api/local/dev/data-manager")
public class DevDataEndpoints {
  private final DevCategoryService service;
  private final DevDatabase.Identity identity;
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;

  public DevDataEndpoints(
      DevCategoryService service,
      DevDatabase.Identity identity,
      org.springframework.jdbc.core.JdbcTemplate jdbc) {
    this.service = service;
    this.identity = identity;
    this.jdbc = jdbc;
  }

  @GetMapping("/status")
  public DevData.Status status() {
    jdbc.queryForObject("SELECT 1", Integer.class);
    return new DevData.Status(
        "onlinepos-data-manager",
        "dev",
        "H2",
        "CONNECTED",
        identity.instanceId(),
        java.util.List.of("menu-categories"));
  }

  @GetMapping("/categories")
  public DevData.Page list(
      @RequestParam(name = "search", defaultValue = "") String search,
      @RequestParam(name = "state", defaultValue = "active") String state,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size) {
    return service.list(search, state, page, size);
  }

  @PostMapping("/categories")
  public DevData.Category create(@RequestBody DevData.Save command) {
    return service.save(null, command);
  }

  @PutMapping("/categories/{id}")
  public DevData.Category update(@PathVariable("id") int id, @RequestBody DevData.Save command) {
    return service.save(id, command);
  }

  @ExceptionHandler(DevCategoryService.Failure.class)
  public ResponseEntity<DevData.Error> failure(DevCategoryService.Failure failure) {
    return ResponseEntity.status(failure.status())
        .body(new DevData.Error(failure.code(), failure.field(), failure.getMessage()));
  }

  @ExceptionHandler({org.springframework.dao.DataAccessException.class})
  public ResponseEntity<DevData.Error> databaseFailure() {
    return ResponseEntity.status(503)
        .body(
            new DevData.Error(
                "DATABASE_UNAVAILABLE",
                "",
                "Database operation failed; changes were not confirmed"));
  }
}
