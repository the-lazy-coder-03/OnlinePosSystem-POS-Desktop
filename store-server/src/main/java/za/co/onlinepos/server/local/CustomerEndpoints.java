package za.co.onlinepos.server.local;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.Customers;

@RestController
@Profile({"installed", "local-store"})
@RequestMapping("/api/local/customers")
public class CustomerEndpoints {
  private final CustomerService service;

  public CustomerEndpoints(CustomerService service) {
    this.service = service;
  }

  private String token(HttpServletRequest r) {
    return r.getHeader("Authorization").substring(7);
  }

  private String terminal(HttpServletRequest r) {
    return (String) r.getAttribute("pos.terminal");
  }

  @GetMapping("/context")
  public Object context(HttpServletRequest r) {
    return service.context(token(r), terminal(r));
  }

  @GetMapping
  public Object list(
      HttpServletRequest r,
      @RequestParam(name = "query", defaultValue = "") String query,
      @RequestParam(name = "archived", defaultValue = "false") boolean archived,
      @RequestParam(name = "cursor", defaultValue = "") String cursor) {
    return service.search(token(r), terminal(r), query, archived, cursor);
  }

  @GetMapping("/{id}")
  public Object detail(HttpServletRequest r, @PathVariable("id") UUID id) {
    return service.detail(token(r), terminal(r), id);
  }

  @PutMapping("/{id}")
  public Object save(
      HttpServletRequest r, @PathVariable("id") UUID id, @RequestBody Customers.Edit edit) {
    if (!id.equals(edit.customerId())) throw new LocalFailure(400, "Customer identity mismatch");
    return service.save(token(r), terminal(r), edit);
  }

  @PutMapping("/{id}/archive")
  public Object archive(
      HttpServletRequest r, @PathVariable("id") UUID id, @RequestBody Customers.Status edit) {
    return service.status(token(r), terminal(r), id, edit, false);
  }

  @PutMapping("/{id}/ban")
  public Object ban(
      HttpServletRequest r, @PathVariable("id") UUID id, @RequestBody Customers.Status edit) {
    return service.status(token(r), terminal(r), id, edit, true);
  }

  @GetMapping("/{id}/history")
  public Object history(
      HttpServletRequest r,
      @PathVariable("id") UUID id,
      @RequestParam(name = "page", defaultValue = "0") int page) {
    return service.history(token(r), terminal(r), id, page);
  }

  @GetMapping("/lookup")
  public Object lookup(HttpServletRequest r, @RequestParam("phone") String phone) {
    return service.lookup(token(r), terminal(r), phone);
  }

  @GetMapping("/sync")
  public Object sync(HttpServletRequest r) {
    return service.syncStatus(token(r), terminal(r));
  }

  @GetMapping("/conflicts")
  public Object conflicts(
      HttpServletRequest r, @RequestParam(name = "page", defaultValue = "0") int page) {
    return service.conflicts(token(r), terminal(r), page);
  }

  @PutMapping("/conflicts/{id}")
  public Object resolve(
      HttpServletRequest r, @PathVariable("id") UUID id, @RequestBody Customers.Resolution edit) {
    return service.resolve(token(r), terminal(r), id, edit);
  }
}
