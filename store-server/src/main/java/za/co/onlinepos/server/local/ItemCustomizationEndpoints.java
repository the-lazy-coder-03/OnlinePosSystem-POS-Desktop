package za.co.onlinepos.server.local;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.onlinepos.contracts.ItemCustomization;
import za.co.onlinepos.contracts.LocalCommands;

@RestController
@Profile({"installed", "local-store"})
@RequestMapping("/api/local/catalogue/items")
public class ItemCustomizationEndpoints {
  private final ItemCustomizationService service;
  private final LocalOperations operations;

  public ItemCustomizationEndpoints(ItemCustomizationService service, LocalOperations operations) {
    this.service = service;
    this.operations = operations;
  }

  private String token(HttpServletRequest r) {
    return r.getHeader("Authorization").substring(7);
  }

  private String terminal(HttpServletRequest r) {
    return (String) r.getAttribute("pos.terminal");
  }

  @GetMapping
  public Object state(HttpServletRequest r) {
    return service.state(token(r), terminal(r));
  }

  @PutMapping("/rules")
  public Object rules(HttpServletRequest r, @RequestBody ItemCustomization.Edit edit) {
    return service.save(token(r), terminal(r), edit);
  }

  @PostMapping("/quote")
  public Object quote(HttpServletRequest r, @RequestBody ItemCustomization.QuoteRequest quote) {
    return service.quote(token(r), terminal(r), quote);
  }

  @PostMapping("/drafts")
  public Object draft(HttpServletRequest r, @RequestBody LocalCommands.CreateDraft draft) {
    return service.createDraft(token(r), terminal(r), draft, operations);
  }
}
