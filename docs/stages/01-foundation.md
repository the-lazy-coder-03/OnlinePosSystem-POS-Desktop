# Stage 1 completion report — 24 October 2026

Status: foundation implemented and verified on macOS ARM64 / OpenJDK 25. Windows acceptance remains
pending the configured Windows CI run or the documented Windows validation. No Stage 2 work started.

## Implemented changes

New independent repository: `/Users/matthewanton/IdeaProjects/OnlinePosSystem-POS-Desktop`.
Git is initialized on main without a remote or commits. No cloud repository files were modified.

- Root Maven reactor/wrapper, version-managed dependencies and eight modules: pos-domain, pos-contracts,
  pos-emergency, pos-printing, store-server, pos-desktop, windows-installer and integration-tests.
- Actual JavaFX 25.0.2 application: Launcher, PosApplication, SetupWizard, SetupSelection and setup.css.
  The preview selects Kenridge/Uitzicht and Master/Slave, validates input, reviews selection and supports
  back navigation. It persists nothing, installs nothing and accepts no orders.
- Separate executable Spring Boot backend with read-only `/api/local/health`, loopback binding and
  deny-by-default security. Health never claims sales, cloud sync or automatic promotion are available.
- Exact decimal Money type and branch/order/command/role/health contracts. UUID identity is established
  without prematurely implementing database persistence or claiming durable deduplication guarantees.
- Reserved H2 and printing modules; Windows packaging module documents its boundary without installer
  payloads. No PostgreSQL, H2 files, migrations, pairing, payment, printer or failover actions were added.
- Spotless/Google Java Format, Checkstyle, ArchUnit, Gitleaks configuration, editor/line-ending settings,
  Windows and Linux CI, JavaFX Windows launch smoke, root build and desktop launch scripts.
- Audit baseline, approved decisions, contracts, revised stage gates, version documentation, Windows
  acceptance/rollback checklist and preserved original plan under docs/reference.

## Verification executed

| Check | Result |
| --- | --- |
| Root `./mvnw -B -ntp install` | BUILD SUCCESS across all eight modules and parent |
| MoneyTest | 4 passed |
| IdentityContractTest | 4 passed |
| SetupSelectionTest | 3 passed |
| HealthSecurityTest | 2 passed |
| ModuleArchitectureIT | 2 passed, architecture rules evaluated real JDK 25 classes |
| HealthHttpIT | 1 passed against real embedded HTTP server |
| Formatting and Checkstyle | Passed, zero Checkstyle violations |
| JavaFX `-Ddesktop.args=--smoke-test` | Actual window launched and validated; DESKTOP_SMOKE_OK |
| Packaged backend executable | Started on ephemeral loopback port; health contract matched, orders returned 403; process stopped |
| Gitleaks 8.30.1 source scan, redacted | No leaks found |
| POSIX wrapper and launch script syntax | Passed |

Total automated JUnit tests: 16, with zero failures/errors/skips in the successful build.

Build fixes: renamed a security bean whose initial name collided with its configuration class;
upgraded ArchUnit from the cloud's older 1.3.0 baseline to 1.5.1 for JDK 25 bytecode; preserved strict
Maven SHA-256 verification while preferring shasum on this host's incompatible sha256sum implementation.

## Not executed / remaining acceptance

- Windows CI/PowerShell/IntelliJ validation has not been executed. No remote was created or pushed,
  so the workflow is supplied as source but has not run on GitHub. Do not mark Windows acceptance passed.
- Native Windows installers, signing, service installation, PostgreSQL/H2 persistence, LAN pairing/TLS,
  printers, cloud sync, standby replication and power-loss guarantees belong to later stages.
- The Git history scan will run in CI after commits exist. Locally, source was scanned; history is empty.

The stage exit requires a real Windows build and JavaFX launch in addition to the passing local evidence.
The local implementation is ready for review, with that platform verification explicitly outstanding.

## Rollback and next gate

Close the preview and stop the backend; archive this independent repository directory if needed.
There are no services, schemas, firewall changes or production transactions to reverse. Do not run
the operational cloud rollback/deployment scripts. Keep the original OnlinePosInstore starter intact.

After review and Windows verification, Stage 2 still requires explicit approval. Do not automatically
install PostgreSQL, expose the API to the LAN, create a remote, deploy or advance stages.

## Stage 2 evidence addendum — 24 October 2026

The approved private remote now exists at the-lazy-coder-03/OnlinePosSystem-POS-Desktop. Stage 1 was
committed separately as bf2d8c4b2948cf92d2490488b47cb3381ddeaa7c. Its GitHub CI run 40116729206 passed
Linux and Windows builds, architecture/security tests, source/history secret scans and actual JavaFX
launch. Earlier statements above describe the evidence available at the original Stage 1 handoff.
Stage 2 was subsequently authorized explicitly and is tracked separately in 02-windows-setup.md.
