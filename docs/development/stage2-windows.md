# Stage 2 Windows operations and acceptance

## Package/build
Windows x64, JDK 25:
```powershell
.\mvnw.cmd -B -ntp clean install
.\windows-installer\scripts\Build-Package.ps1 -Channel Development
.\windows-installer\scripts\Test-Package.ps1
```
Test-Package is for disposable elevated CI/VMs only: it installs/uninstalls app-owned services and packages.
Never run it on a live store. Production package mode requires ONLINEPOS_SIGNING_THUMBPRINT and Windows
SDK signtool; no signing identity is supplied and no production release is approved.

dependencies.json pins the Inno 6.7.3, WinSW 2.12.0, PostgreSQL 16.15 and Microsoft VC runtime payloads
by immutable source URL and SHA-256. Mismatches block execution. The package includes a JDK/runtime
inventory, application-file manifest and installer SHA256SUMS. PostgreSQL/VC dependencies download
during initial Master or Backup component setup; Java runtime and backend are bundled.

## Master
Choose Master components in the installer, then configure Master in JavaFX. Windows elevation applies
to the helper, not the ordinary JavaFX process. Same-account UAC consent is the supported Stage 2 path;
over-the-shoulder elevation under a different administrator must fail safely if the request cannot be read.
Use at least 12 characters for the installation administrator; it is separate from future staff login.

Default HTTPS port is 8443, PostgreSQL 55432. Before provisioning, an administrator may create
C:\ProgramData\OnlinePOS\network.json with httpsPort/postgresPort (distinct integers 1024–65535) and optional storeSubnet,
for example {"httpsPort":8443,"postgresPort":55432,"storeSubnet":"192.168.1.0/24"}.
The subnet must be an RFC1918 IPv4 CIDR; public or overly broad ranges are rejected.
Changing a PostgreSQL port after initialization requires a separately planned maintenance change;
do not edit only one side of an existing deployment.
Firewall access is limited to the Private profile and LocalSubnet. Set storeSubnet to the selected store
network during setup; do not open it on Public profiles or expose PostgreSQL.

PostgreSQL is a dedicated cluster, loopback-only, with SCRAM, bootstrap/owner/runtime identities,
a pos_store database and no business tables. Backend and database services use LocalService with
service-specific filesystem permissions. Service restart is automatic; no staff session is required.

## Slave pairing and management
Run management in an elevated Windows PowerShell console on the Master:
```powershell
& 'C:\Program Files\OnlinePOS\setup\Manage.ps1' -Action Invite -Endpoint 'https://MASTER-HOST:8443' -OutputPath 'C:\ProgramData\OnlinePOS\invitations\terminal.json'
```
Enter the installation-administrator password at the console prompt. Transfer the protected invitation
to its intended operator within ten minutes. Paste its JSON into Slave JavaFX. A manual HTTPS address
may replace the address only; it cannot change trust fingerprints or branch identity.
Do not put invitation contents into issue comments, build logs or shared screenshots.

Revoke a terminal with Manage.ps1 -Action Revoke -TerminalId <terminal UUID>. No unauthenticated LAN
management endpoint exists. For advanced Backup components, re-run the installer selecting Master
components, then Manage.ps1 -Action Backup on an already configured Slave. Administrator authentication
and elevation are mandatory. The backend is disabled; no standby database is seeded or promoted.

A fresh Slave installs no backend or PostgreSQL. Master demotion and any transition involving existing
PostgreSQL data are deliberately refused pending a verified ownership/data handover. This restriction
must not be bypassed by editing installation.json. No automatic failover is implemented. A configured Slave may deliberately create a new independent
Master with Manage.ps1 -Action Master after installing Master components; this creates a new cluster
identity and must never be used to silently replace a disconnected branch Master.

## Recovery and rollback
Interrupted provisioning leaves CONFIGURING state and a redacted provisioning.log. Re-run the same setup
with the same branch/role and administrator credential; installed identities and database directory must
be preserved. Never delete populated data to repair installation. Certificate bundles commit their CA key last, so interrupted first creation can be retried. If a pairing
response is lost before the terminal saves its certificate, revoke that terminal UUID on the Master,
issue a new invitation, and retry the same installation. A revoked registration may be replaced only
with a fresh invitation; used tokens remain rejected. Do not disable TLS or create a second Master.

Uninstall stops/removes app-owned services and firewall rules before removing binaries; failure to stop
services cancels uninstall. ProgramData is retained, including database, keys and unresolved future queues.
Reinstall with matching components to repair. No cloud deployment/rollback scripts are involved.

## Client acceptance still required
Run on fresh Windows 10/11 x64 VMs with no developer JDK: Master/Slave install, same-account UAC, private
and public network profiles, real LAN multicast/manual fallback, restart/reboot, wrong branch/hostname,
expired invitation, revocation, interruption/repair, port conflict and data-preserving uninstall.
Validate credential ACLs from an ordinary separate Windows user, not only an elevated CI identity.
Windows Server GitHub runners do not establish Windows 10/11 client acceptance.

