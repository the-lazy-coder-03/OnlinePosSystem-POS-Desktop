# Stage 3/4 Windows 11 client validation

The user supplied SSH access to their disposable Windows PC and participated at its desktop on
25 October 2026. The PC initially had no OnlinePOS program/data directories or app-owned services.
Its OS is Windows 11 Pro x64, build 26200. Private host addresses, account names, administrator input,
terminal keys and environment files are deliberately excluded from this report.

## Package and installation boundary

The initial unsigned development installer came from successful run 40184668321, tested commit c812696.
Its SHA-256 matched the artifact's SHA256SUMS file both locally and after transfer:
`2d1e64830bdec1c7ab63e584962db718f937201e6f0fcbe4e46dfa870220a1e6`.
The Windows PC uses a Kenridge test Master and source-seed catalogue fixtures, not current cloud customer,
staff, order or payment data. No production cloud or the user's separate source database was changed.
Sales, cloud sync and automatic promotion stayed disabled throughout.

The user began a first-run Master configuration in JavaFX. Its existing identity was retained; it was
not reset or replaced when the validation harness's separately generated credential was refused. The
user-supplied installation administrator input was subsequently sent through masked stdin and stored
only in Windows machine-DPAPI-protected temporary material. No password entered process arguments,
source files, Git commits or diagnostic output. Staff bootstrap used an independent random test password.

Two harness issues were corrected without changing application security:

- Windows PowerShell redirection initially promoted a JDK native-access warning to a terminating error.
  Native stdout/stderr were then captured with child process handles instead of PowerShell redirection.
- A setup request created over elevated SSH had Administrators as its owner, unlike a normal desktop
  user's request. Its owner was corrected to the actual setup user's SID before repair. Repair then
  granted that user read access to its enrolled terminal material, while privileged database/CA secrets
  remained protected. This mimics the ordinary JavaFX request ownership rather than broadening ACLs.

## Executed client checks

| Check | Result |
| --- | --- |
| Installer checksum / fresh application install | Passed |
| Actual JavaFX/CDI smoke in the non-elevated interactive desktop session, with external JAVA_HOME and Java PATH removed from the test process | Passed; bundled runtime used |
| User-visible installer wizard cancellation | User confirmed the wizard opened and was cancelled |
| UAC consent and denial dialogs | Not observed by the user; not accepted as passed |
| Master provisioning and repeat protected repair | Passed; local Flyway migrations and same installation/cluster/terminal identity |
| Local staff bootstrap and installed enrolled-terminal TLS login/logout | Passed |
| Complete catalogue import/readback, 241 effective price baselines and identical-command replay | Passed |
| Missing staff session / wrong branch | Rejected |
| Encrypted machine-bound backup | Passed; success left no plaintext dump |
| Backend service restart and full staff/catalogue readback | Passed |
| Dedicated service accounts and automatic startup | Passed for PostgreSQL and backend |
| Private-profile-only application firewall rules | Passed |
| PostgreSQL network binding | Loopback only; no database listener exposed to LAN |
| Separate non-admin account effective NTFS access | Six protected reads and five directory writes denied |
| Non-elevated setup user's identity and enrolled terminal reads after protected repair | Passed; CA/database material was not granted to that user |
| Trusted HTTPS from a separate Mac across the LAN | Passed |
| Manual-IP pairing, branch/cluster identity, wrong CA, wrong branch and consumed-invitation replay | Passed |
| Terminal revocation enforced on the next LAN request | Passed |
| Automatic discovery and trusted endpoint identity | Initially failed for generated DNS alias; passed after the fix below |
| Actual Windows reboot, automatic service recovery, staff TLS login and full catalogue persistence | Passed |
| Windows 10 client and separate physical Windows Slave UI | Not tested; no second Windows client available in this session |

The independent account ACL check used an authenticated non-administrator Windows logon token and native
thread impersonation for actual file-open/create attempts. It was not just ACL text inspection. Every
expected denial was UnauthorizedAccessException; no probe could read secret contents or leave a writable
marker. A batch/process launch under that new account was unavailable in the SSH session, so GUI login
under the independent account is not claimed. The temporary account, its task and its probe directory
were removed after collecting the aggregate result.

The user explicitly approved the reboot. OS LastBootUpTime changed; a prepared startup task observed both
services Running without starting them manually. The same installation/cluster/terminal IDs survived,
and a new terminal-bound staff login plus full source-hash/catalogue readback passed. Trusted HTTPS from
the separate Mac also worked after boot. These are observed recovery checks, not a power-loss RPO/RTO SLA.

## Discovery defect and correction

The Windows service's JmDNS advertisement supplied an address-derived `.local` alias that was not in
its HTTPS certificate SANs. The old discovery adapter emitted only that alias, causing a hostname
verification failure even though the actual advertised IPv4 address was a valid SAN. Manual-IP access
was correctly authenticated and did not need relaxed verification.

Commit `18b29d0a0bc7a3832b209b5e9351987ce73d506f` makes MdnsDiscovery emit advertised IPv4 candidates first,
followed by a valid hostname fallback, with deduplication and malformed-authority rejection. It does not
modify certificates, trust roots, branch/cluster validation or TLS hostname verification. Three regression
tests cover the generated alias, duplicate addresses, malformed authorities and hostname-only discovery.
The focused module build, existing installation/trust tests, format and static checks passed locally.

The revised real LAN client found the Windows service, authenticated the advertised IP using the pinned
branch CA and enrolled terminal certificate, and verified the expected branch/cluster. Wrong CA, foreign
branch, replay and post-revocation checks still rejected access. The Mac test held terminal keys only in
memory and destroyed them after use. It was an explicit test client, not a production platform adapter.

Fresh CI for this correction: https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40190029128
This run passed all 48 Windows tests with zero skips/failures/errors, plus the complete packaged
installer/provisioning suite. Linux and secret scanning also passed. The corrected installer artifact is
[available here](https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40190029128/artifacts/12598285676),
with [Windows test reports](https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40190029128/artifacts/12598215939).
The corrected installer SHA-256, verified locally and again on the Windows PC, is
`0e51162e0a1bee9fccdfe3c83d94a2332f569060f7a48d2ca4e5a9df0fa4d88b`.

The corrected package was then tested on this same PC with the normal uninstaller followed by Master
reinstallation/repair. Uninstall removed both app-owned services while retaining the PostgreSQL data,
CA key, database credential bundle and identity. Reinstallation/repair preserved all IDs and the protected
CA/database keys; a new enrolled-terminal staff login and complete catalogue readback passed afterwards.
The app remained an installed test Master with automatic services and sales disabled.

Finally, the corrected installed desktop library was exercised from a non-elevated interactive Windows
token using the bundled Java runtime. Six discovery candidates were seen across local interfaces; a
candidate authenticated the installed branch/cluster with the pinned CA and enrolled certificate.
The process exited 0 and emitted WINDOWS11_INSTALLED_NON_ELEVATED_TRUSTED_DISCOVERY_OK. Thus the
real-network correction was checked both from the separate Mac and from the corrected installed Windows
client, rather than only through unit tests.

## Remaining boundary and rollback

No interactive UAC dialog was observed, despite the limited-token launch and configured UAC registry
settings. Installer/helper execution over SSH or Task Scheduler is not evidence of a human consent or
cancellation dialog. Do not mark that release gate complete from this session. Windows 10, a second real
Windows Slave, keyboard/accessibility review, production signing and live-store cutover remain separate.

The local test identity, catalogue and encrypted backup are retained for inspection. Validation-only
accounts/tasks, test invitations and temporary credential material were cleaned up as recorded below. No staff password or key is
checked into the repository.

Rollback uses the normal uninstaller, retaining ProgramData database, identities and protected keys.
A downgrade must recognize the additive V2 migration; do not edit Flyway history or restore older data
over newer commands. Do not delete a user-created identity to repeat a test. A disposable VM checkpoint
may be reverted only when the operator has confirmed all retained data is disposable.

## Final retained state and cleanup

The PC retains the corrected test Master installation, the existing user-created identity, the synthetic
Kenridge catalogue (241 effective baselines), the synthetic manager account and its encrypted backup.
Both services were Running/Automatic at the final check. Trusted LAN health still returned UP with
acceptingSales=false, cloudSyncEnabled=false and automaticPromotionEnabled=false.

The temporary non-admin account/probe directory and all ten validation-only scheduled tasks were removed.
All four Mac test terminals were revoked, leaving only the enrolled local Master terminal enabled.
Consumed/expired test invitation files were removed from both machines, as were the temporary protected
setup request and the temporary credential bundle used by the test harness. No startup verification task
remains that could rerun automatically after another reboot.

To avoid stranding the only local manager behind an unknown random test password, an encrypted handover
copy was retained at `C:\ProgramData\OnlinePOS\validation-handover\synthetic-manager.dpapi`, with a
non-secret README.json identifying the synthetic staff username `ci.manager`. It is Windows machine-DPAPI
encrypted, with inheritance disabled and explicit file/directory access restricted to SYSTEM and local
Administrators. The protected bundle stays on the Master and was not downloaded. Use a protected manager
flow to create approved staff and retire the synthetic account before any future operational use; this
session does not authorize that operational handover or sales activation.

Curated aggregate JSON evidence was copied to the Mac's temporary `onlinepos-win11-evidence` directory.
These reports and application logs are not committed; only this summary is stored in Git. No administrator
password, private key, environment file or encrypted handover bundle enters the repository.
