# OnlinePosSystem POS Desktop

This is the **JavaFX desktop application** and its separate local Spring Boot backend.
It is an independent Git repository. It has no cloud remote, credentials or production connection.

Stage 1 supplies a runnable setup preview and backend liveness process. It cannot install a Master,
accept sales, take payments, print receipts, open an emergency database or synchronise to the website.
Those capabilities remain behind the approved stage gates in [the roadmap](../docs/roadmap.md).

## Open in IntelliJ IDEA

Open this directory's root `pom.xml` as a Maven project. Set Project SDK and Maven Runner JRE to **JDK 25**,
then reload all Maven projects. The JavaFX application is in `pos-desktop`; run
`za.co.onlinepos.desktop.Launcher` with the module's runtime dependencies, or use the launch scripts below.
JavaFX is downloaded by Maven; do not manually add a JavaFX SDK or attach the cloud project's sources.

## Build and test

Windows PowerShell:

```powershell
.\mvnw.cmd -B -ntp clean install
.\mvnw.cmd -pl pos-desktop javafx:run
```

macOS / Linux (a graphical session is needed to launch JavaFX):

```sh
./mvnw -B -ntp clean install
./mvnw -pl pos-desktop javafx:run
```

The wrapper downloads and checksum-verifies Maven 3.9.16. `install` runs unit tests, HTTP integration
tests, architecture checks, formatting and static analysis, then installs module artifacts locally.
No PostgreSQL service, production account, Docker or internet connection to a store is needed.
Initial Maven dependency downloads require internet access.

For a single command that builds the desktop dependencies and opens JavaFX, use
`scripts\run-desktop.cmd` on Windows or `./scripts/run-desktop.sh` on macOS/Linux.

## Run the separate local backend

After the root build:

```sh
java -jar store-server/target/store-server-0.1.0-SNAPSHOT-exec.jar
```

Read `http://127.0.0.1:18090/api/local/health`. The response has protocol version 1, process status `UP`,
role `UNCONFIGURED` and `acceptingSales`, `cloudSyncEnabled`, `automaticPromotionEnabled` all `false`.
HTTP 200 means the process is alive, not that sales can be accepted. All other routes are denied.
This development process binds only to loopback. LAN TLS, pairing and role setup belong to Stage 2;
staff authentication and PostgreSQL belong to Stage 3. Never expose this preview as a store server.

## Modules

| Module | Boundary |
| --- | --- |
| `pos-domain` | Framework-free exact decimal money; later business policies |
| `pos-contracts` | Branch seed IDs, immutable order/command UUIDs, health wire contract |
| `pos-desktop` | JavaFX controls, CSS and transient first-run setup preview |
| `store-server` | Separate Spring Boot executable; read-only health endpoint |
| `pos-emergency` | H2 dependency and persistence boundary only; no queue implementation yet |
| `pos-printing` | Reserved terminal printing boundary |
| `windows-installer` | Packaging specification; no installer or provisioning scripts yet |
| `integration-tests` | Architecture and real HTTP compatibility checks |

## Developer checks

```sh
./mvnw spotless:apply
./mvnw clean verify
gitleaks dir . --redact --no-banner
```

Use Gitleaks 8.30.1; CI verifies its downloaded binary checksum and scans both source and history.
Formatting uses Google Java Format; Checkstyle and ArchUnit check code and module boundaries.
CI builds on Linux and Windows, and launches the JavaFX wizard smoke scenario on Windows.
There is no deployment workflow and no remote has been created or pushed.

See [contracts](../docs/architecture/contracts.md), [decisions](../docs/architecture/decisions.md),
[version baseline](../docs/development/versions.md), [Windows validation](../docs/development/windows-validation.md)
and [Stage 1 report](../docs/stages/01-foundation.md).
