# Stage 2 completion and evidence

Completed: 25 October 2026 (Africa/Johannesburg); CI evidence collected 24 October UTC. Status: implementation and available automated validation complete.
Unsigned development release only; Windows 10/11 client validation and production signing remain release gates.

## Delivered architecture and files

JavaFX remains the desktop application. Weld SE 6.0.4.Final owns its injected composition root and
presenter/services, with explicit scopes, constructor injection and shutdown/startup-failure cleanup.
Spring Boot remains a separate Windows service using Spring DI. Domain/contracts contain neither container.
Ports in pos-installation isolate Windows provisioning, persistence, secrets, pairing, discovery and diagnostics.

| Area | Actual implementation |
| --- | --- |
| CDI and JavaFX | pos-desktop/src/main/java/za/co/onlinepos/desktop/DesktopContainer.java, DesktopRoot.java, PosApplication.java, SetupWizard.java; setup/DesktopComponents.java and SetupPresenter.java |
| Installation and security | pos-installation/src/main/java/za/co/onlinepos/installation/: versioned atomic installation state, administrator verifier/lockout, DPAPI storage, certificates, registry, trusted HTTPS and discovery adapters |
| Installed backend | store-server/src/main/java/za/co/onlinepos/server/InstalledConfiguration.java, InstalledTls.java, InstalledEndpoints.java, TerminalAuthenticationFilter.java, FoundationSecurity.java and HealthController.java |
| Packaging and setup | windows-installer/OnlinePOS.iss, dependencies.json and scripts/Build-Package.ps1, Dependencies.ps1, Provision.ps1, Manage.ps1, Uninstall.ps1 and Test-Package.ps1 |
| Boundaries and validation | integration-tests/src/test/java/za/co/onlinepos/integration/ModuleArchitectureIT.java; desktop and installation tests; .github/workflows/ci.yml |

[Complete changed-file inventory](stage2-changed-files.txt) compares all Stage 2 changes against Stage 1
commit bf2d8c4b2948cf92d2490488b47cb3381ddeaa7c. Initial snapshots were merged through PRs #1/#2;
[PR #3](https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/pull/3) completes native provisioning.

## Verified evidence

The successful [CI run 40136104988](https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40136104988)
validated code commit ac1b11fc362e9ef474115ea03c3102a2311efb34 on Linux and Windows Server 2025.

| Check | Result |
| --- | --- |
| Local Maven install, formatting and Checkstyle | Passed; 28 JUnit tests passed, 1 Windows-only DPAPI test skipped on macOS |
| Linux CI | Passed; 28 tests passed, DPAPI test skipped |
| Windows CI | Passed; 29 tests, zero failures/errors/skips |
| Actual JavaFX, development and installed package | Passed; DESKTOP_SMOKE_OK and DESKTOP_CDI_CLOSED |
| CDI failure cases and boundaries | Missing/ambiguous injection rejected; framework/package boundaries passed |
| Identity, administrator and trust tests | Identity preservation, five-character enrollment, lockout, adapter substitution, DPAPI, invitation expiry/replay, wrong branch, TLS hostname failure and revocation passed |
| Slave package | No backend/PG service; packaged JavaFX launches; uninstall preserves a data marker |
| Backup components | Backend disabled, STANDBY_UNSEEDED, no initialized database or PostgreSQL service |
| Master | Dedicated PostgreSQL initialized; separate owner/runtime DB roles, dedicated virtual Windows service accounts, HTTPS backend, terminal registration and trusted identity self-test passed |
| Restart and repair | Both services restarted; authenticated self-test passed; repair and configured Master uninstall/reinstall preserved database, keys and installation/cluster/terminal UUIDs |
| Grants and firewall | DB roles lack superuser/createdb/createrole; zero public business tables; Private-profile HTTPS rule; permissive Everyone grant removed; elevated payload cache allows only administrator/System writes |
| Source and Git history | Gitleaks passed; successful native CI console log also scanned with no leaks found |

Private artifacts from the validated run:
- [Unsigned installer, SHA256SUMS, release manifest and packaged JavaFX logs](https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40136104988/artifacts/12577265463).
- [Windows JUnit/static-analysis evidence](https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40136104988/artifacts/12577045693).
- [Linux JUnit/static-analysis evidence](https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40136104988/artifacts/12576589296).

The final report commit changes documentation only; executable validation applies to the source SHA above.

## Corrections established through real Windows runs

Explicit JDK module enumeration supports Temurin runtime linking without jmods, excluding jlink/jpackage
from the generated runtime. A retained launcher process handle avoids a Windows PowerShell false failure
when reading ExitCode. WinSW now receives its required description. Native archive extraction installs
PostgreSQL runtime components and license notices, omitting pgAdmin; extraction fell from almost nine
minutes to a few seconds. A completion marker prevents partial extraction being treated as ready.

Windows initdb drops administrator-group privileges. Setup grants only the invoking user's SID temporary
access to the bootstrap file and new database directory, removes the file and temporary database grants,
and transfers ownership before starting PostgreSQL. Services use distinct NT SERVICE virtual accounts.
Protected directories use explicit ACLs; pre-planted writable credentials/configuration are rejected.
Elevated dependency downloads use an administrator-owned machine cache, protecting verified payloads
from ordinary user modification before execution. PR and main builds have separate concurrency groups
and feature-branch pushes no longer create duplicate Windows jobs.
Uninstall removes marked app-owned runtime binaries and preserves ProgramData and retained database files.

The user explicitly selected a five-character installation-administrator minimum during this stage.
JavaFX, service validation, enrollment, tests and the staged plan now enforce that value. PBKDF2 and
persisted retry limits remain enabled; this credential does not grant future staff access.

## Remaining release gates and limits

- Production signing is not configured; Production builds fail without a signing identity. Artifacts are unsigned tests.
- Windows 10/11 x64 client VMs still require fresh install without a developer JDK, UAC, reboot,
  private/public firewall profile, real LAN discovery/manual fallback, separate-user ACL and interruption checks.
  Hosted Windows Server evidence is not Windows 10/11 client acceptance.
- Same-account UAC consent is supported. Different-account elevation must fail safely if the request is inaccessible.
- Data-bearing Master demotion remains refused pending a verified handover; an unseeded Backup cannot promote.
- No sales, business migrations, staff accounts, emergency H2 persistence, printing, cloud sync or replication.
  All health responses retain acceptingSales=false, cloudSyncEnabled=false and automaticPromotionEnabled=false.
- No live store was installed or deployed and the operational cloud repository was unchanged.

## Rollback and stage gate

Close JavaFX and use the application uninstaller. It stops/removes app-owned services and rules before
removing binaries, cancelling when safe service shutdown fails. ProgramData, identities, keys, databases
and future terminal queues remain. Reinstall matching components and run protected repair; never delete
populated data or initialize over it. See [Windows operations](../development/stage2-windows.md).

Review Stage 2 and its unsigned artifacts. Stage 3, production signing/release, live installation and
branch activation require separate explicit approval. None has started.
