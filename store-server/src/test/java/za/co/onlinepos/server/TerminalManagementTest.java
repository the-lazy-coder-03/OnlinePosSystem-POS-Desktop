package za.co.onlinepos.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import za.co.onlinepos.contracts.InstallationRole;
import za.co.onlinepos.contracts.PosModule;
import za.co.onlinepos.installation.Installation;
import za.co.onlinepos.installation.TerminalRegistry;
import za.co.onlinepos.server.local.AdministrationService;
import za.co.onlinepos.server.local.LocalFailure;
import za.co.onlinepos.server.local.ModuleAccess;

class TerminalManagementTest {
  @Test
  void onlyOwnerOrDelegatedAdministrationMayListRenameAndRevoke() throws Exception {
    var registry = mock(TerminalRegistry.class);
    var modules = mock(ModuleAccess.class);
    var admin = mock(AdministrationService.class);
    var installation =
        new Installation(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            InstallationRole.MASTER,
            "Master",
            "INSTALLED");
    var endpoints = new TerminalManagement(registry, modules, admin, installation);
    UUID actor = UUID.randomUUID(), terminal = UUID.randomUUID();
    var request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer session");
    request.setAttribute("pos.terminal", "certificate");
    when(modules.authenticate("session", "certificate", false))
        .thenReturn(
            new ModuleAccess.Session(
                actor,
                "user",
                PosModule.ADMINISTRATION,
                Set.of("module.administration"),
                "certificate",
                1));
    assertThrows(LocalFailure.class, () -> endpoints.list(request));
    when(admin.owner(actor)).thenReturn(true);
    when(registry.terminals())
        .thenReturn(
            List.of(
                new TerminalRegistry.TerminalView(
                    terminal, "Counter", "192.168.1.2", 1, 1, true, false)));
    assertEquals(1, endpoints.list(request).size());
    endpoints.edit(terminal, new TerminalManagement.Edit("Counter 2", false), request);
    verify(registry).rename(terminal, "Counter 2");
    when(admin.owner(actor)).thenReturn(false);
    when(modules.authenticate("session", "certificate", false))
        .thenReturn(
            new ModuleAccess.Session(
                actor,
                "user",
                PosModule.ADMINISTRATION,
                Set.of("module.administration", "rbac.manage"),
                "certificate",
                1));
    endpoints.edit(terminal, new TerminalManagement.Edit(null, true), request);
    verify(registry).revoke(terminal);
    when(modules.authenticate("session", "certificate", false))
        .thenReturn(
            new ModuleAccess.Session(
                actor, "user", PosModule.SALES_ORDERS, Set.of("rbac.manage"), "certificate", 1));
    assertThrows(
        LocalFailure.class,
        () -> endpoints.edit(terminal, new TerminalManagement.Edit(null, true), request));
  }
}
