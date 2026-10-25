# OnlinePOS JavaFX desktop
This independent repository contains the JavaFX terminal, Jakarta CDI/Weld desktop composition,
a separate Spring Boot service, and Windows setup tooling. The operational cloud repository is untouched.

## Development
Use JDK 25:
```sh
./mvnw -B -ntp clean install
./mvnw -pl pos-desktop javafx:run
```
Windows equivalents use `mvnw.cmd`. A graphical session is required for JavaFX.
The non-Windows application can review setup and exercise CDI, but cannot provision Windows components.
No fake platform adapter is selected outside tests.

## Windows test package
CI builds a private unsigned development installer. Install Master components on the intended Master,
or Slave components on other terminals. The package bundles Java/JavaFX; installed terminals need no JDK.
Open JavaFX, select the branch and role, enter an installation-administrator password of at least
12 characters and authorize UAC. Initial dependency installation requires internet.

For Slave setup, generate an invitation on the Master using the protected management instructions in
[Windows operations](../docs/development/stage2-windows.md). Paste its JSON into JavaFX. Optional manual
HTTPS hostname/IP fallback retains the invitation's certificate and branch trust checks.

**This release cannot accept sales.** Local staff authentication, database foundations and protected catalogue import are implemented.
Emergency queues, printing, cloud sync and standby replication remain later stages. Backup components remain unseeded/inactive;
automatic promotion is disabled.

See [Stage 2 evidence](../docs/stages/02-windows-setup.md), [CDI boundaries](../docs/architecture/stage2-cdi.md),
[Windows operations and rollback](../docs/development/stage2-windows.md), and [roadmap](../docs/roadmap.md).


Stage 4: [catalogue import](../docs/development/stage4-import.md) and [evidence/gates](../docs/stages/04-catalogue-bootstrap.md).
Source-seed parity tests do not establish current production menu parity or authorize live cutover.

Both branch imports were also tested against an isolated copy of the user-provided local catalogue.
All source records and 478 price baselines matched; Windows and live cutover acceptance remain separate.

[Windows 11 validation](../docs/stages/04-windows11-client-validation.md) covers real reboot/recovery,
permissions, LAN discovery/TLS and preserved data after reinstall. The corrected CI run passed 48 Windows
tests; interactive UAC, Windows 10 and a separate Windows Slave still need acceptance.

Stage 5 increment 1 adds the [module home grid and PIN/RBAC access](../docs/stages/05-module-home.md).
Configured terminals open the grid; accepted sales remain unavailable.

Stage 5 increment 2 implements [Administration staff and access](../docs/stages/05-administration.md):
a sidebar for Overview, Staff, Roles & Permissions and Audit History, with a separately designated owner
for PIN management. Owner setup uses the protected console; new staff accounts use PIN-only access.

Catalogue Management now supports local item, ingredient, recipe, size, price and option editing with explicit RBAC. See [Stage 5 catalogue management](../docs/stages/05-catalogue-management.md).
