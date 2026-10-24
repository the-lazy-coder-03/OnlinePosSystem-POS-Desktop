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
[Windows operations](docs/development/stage2-windows.md). Paste its JSON into JavaFX. Optional manual
HTTPS hostname/IP fallback retains the invitation's certificate and branch trust checks.

**This release cannot accept sales.** Staff accounts, business tables, emergency queues, printing,
cloud sync and standby replication remain later stages. Backup components remain unseeded/inactive;
automatic promotion is disabled.

See [Stage 2 evidence](docs/stages/02-windows-setup.md), [CDI boundaries](docs/architecture/stage2-cdi.md),
[Windows operations and rollback](docs/development/stage2-windows.md), and [roadmap](docs/roadmap.md).

