# Stage 2 implementation and evidence
Date: 24 October 2026. Status: implementation under Windows CI validation; not a production release.

## Delivered source
- JavaFX CDI/Weld 6.0.4.Final, constructor-injected presenter/services, managed startup/shutdown and worker/discovery disposal.
- Shared plain-Java pos-installation module: ports/adapters, versioned atomic installation state,
  administrator verifier/lockout, DPAPI secrets, cluster certificates, pinned HTTPS pairing and registry revocation.
- Resumable elevated Windows setup helper, Master infrastructure, Slave pairing and inactive Backup components.
- HTTPS installed-service profile, authenticated identity/diagnostics, mDNS candidates and manual fallback.
- Checksum-locked dependencies, bundled JavaFX/runtime packaging, unsigned Inno test installer,
  WinSW/backend and PostgreSQL infrastructure, repair/uninstall rules and Windows package tests.
- Private GitHub repository and Stage 2 feature branch. The cloud repository was not changed.

## Evidence
Stage 1 baseline run 40116729206 passed on GitHub Linux/Windows, including JavaFX launch.
Local Stage 2 builds, formatting, static analysis, constructor-substitution, administrator, registry,
real HTTPS hostname/revocation, CDI resolution and architecture tests pass.
Local JavaFX prints DESKTOP_SMOKE_OK and DESKTOP_CDI_CLOSED.

Initial Stage 2 Windows run 40117983533 passed Maven tests and actual JavaFX launch, then exposed a
jlink packaging argument issue. That issue was corrected; latest Windows package validation is pending.
Final CI/artifact evidence will be recorded before handing this stage back.

## Explicit limitations/gates
Unsigned development artifacts only; no production signing identity, installation or cloud deployment.
Windows 10/11 client VM UAC/reboot/LAN validation remains separate from hosted Server CI.
Business APIs, staff accounts, schema migrations, orders, payment, H2 emergency operation, printing,
cloud sync and replication remain absent. All health responses report sales unavailable.
Data-bearing Master demotion is blocked rather than pretending a safe handover exists.

Rollback and management are documented in ../development/stage2-windows.md.
Stage 3 requires new approval.

