# Stage 2 composition and installation contracts
Desktop dependency injection uses Weld SE 6.0.4.Final. DesktopContainer is the only CDI lookup/bootstrap
boundary. JavaFX owns Application/Stage; init starts the container without constructing controls,
start resolves the dependent DesktopRoot and creates controls on the FX thread, stop destroys the
root/dependent presenter and closes the container. Application-scoped producer disposers close discovery
listeners and shut down the worker executor. UI operations dispatch completion back to the FX thread.

SetupPresenter receives InstallationService, MasterDiscovery, DiagnosticsService, InstallationRepository
and ExecutorService through constructor injection. DefaultInstallationService delegates to the injected
PlatformProvisioner. WindowsProvisioner is real; on unsupported platforms it fails rather than claiming
a successful installation. UI controllers contain no shell, database or certificate implementation.

The additional pos-installation module holds plain Java ports and adapters shared by the desktop and
backend. It has no Spring/CDI annotations. Spring constructs installed backend services; Weld is not
started there. Domain and shared DTO/value contracts retain no container dependencies. Architecture
checks forbid those dependencies and cycles between root module packages.

## Installed backend
`--spring.profiles.active=installed` loads protected Windows configuration and configures TLS.
Development mode stays loopback HTTP and denies everything except health. Installed mode uses a
cluster CA, hostname-validated server certificate and optional TLS client certificate presentation,
then authorizes only currently registered, non-revoked certificate fingerprints for identity/diagnostics.
Pairing uses a short-lived bearer invitation over pinned TLS; other business APIs remain denied.
No process-wide trust-all manager, hostname bypass or globally installed CA is used.

- GET /api/local/health: liveness, configured role, acceptingSales/cloudSyncEnabled/automaticPromotionEnabled false.
- GET /api/local/identity: authenticated installation/cluster/branch/role/state.
- POST /api/local/setup/pair: one invitation, branch/cluster match, RSA3072 terminal key proof; creates one registration.
- GET /api/local/diagnostics: authenticated redacted installation state.

Invitations expire after ten minutes. Their random token is stored only as a SHA-256 digest in the Master
registry. The protected invitation file contains the redeemable token; transfer it only to its intended
operator. Revocation is checked on every authenticated request, including previously established TLS
connections. Discovery is a list of untrusted candidate addresses, never election or trust authorization.
Manual addresses must still satisfy the same TLS hostname, CA and branch/cluster checks.

## Files and privileges
installation.json is public metadata, with atomic writes and immutable installation identity.
administrator.json contains a PBKDF2-HMAC-SHA256 verifier (600,000 iterations and random salt), persisted
failed-attempt/lock state. Management uses a cross-process lock. Windows provisioning separately holds
an exclusive operation lock for its full lifecycle.

WindowsSecretStore wraps credentials/PKCS12 material with machine DPAPI; ACLs are also essential because
machine DPAPI is not user authorization. Backend grants are limited to necessary certificates, pairing,
runtime and logs; owner/bootstrap database credentials and installation-administrator verifier are
administrative material. Runtime PKCS12 files remain password encrypted under the protected data root.

The CA remains per cluster. DHCP/hostname changes outside the original SANs must fail trust checks;
repairing/reissuing the server certificate is an explicit protected maintenance operation, not a reason
to disable hostname validation. Production certificate-rotation policy remains part of later operations.

No local business schema, transaction outbox, emergency database, financial ledger or replication exists
in Stage 2. Configuration/registry file transactions do not claim financial delivery guarantees.

