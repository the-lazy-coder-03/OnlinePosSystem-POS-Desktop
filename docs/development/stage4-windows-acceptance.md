# Stage 3/4 Windows client acceptance

This checklist complements the automated GitHub Windows Server runner. It requires a disposable Windows
10/11 x64 VM/PC with an interactive desktop; installation changes services, firewall rules and app-owned
files. Never use a live cashier terminal. Retain the VM checkpoint before beginning and use synthetic
catalogue fixtures rather than current store/customer data. No sale or cloud sync is enabled.

## Package and evidence

Use the private `windows-development-installer` artifact from the successful validation run recorded in
`docs/stages/04-windows-validation.md`. Also download both test-evidence artifacts. On Windows, GitHub CLI
users may use `gh run download <run-id> --repo the-lazy-coder-03/OnlinePosSystem-POS-Desktop --name windows-development-installer`.
Otherwise use that run's Artifacts section while signed in. Verify `Get-FileHash <installer.exe> -Algorithm
SHA256` against `SHA256SUMS.txt`. These are unsigned development packages; production signing is separate.
The installed desktop bundles Java/JavaFX and must launch without a separately installed JDK.

Record OS edition/build, package hash, run/commit, test time, result and redacted error details for every
row below. Do not include environment files, passwords, tokens, certificates/private keys or database dumps.
Retain a private copy of `%ProgramData%\OnlinePOS\logs` for diagnosis; inspect/redact before sharing.

| Check | Procedure | Required result | Result |
| --- | --- | --- | --- |
| Fresh Master / UAC | Install Master, run JavaFX setup as normal user, select branch/terminal, enter installation administrator and approve UAC | Consent is required; provision succeeds; API readiness is trusted | Pending |
| UAC cancellation | On a fresh checkpoint, cancel UAC and retry setup | Clear recoverable failure; no false installed/sales state | Pending |
| Staff bootstrap | Use protected Local-Database.ps1 BootstrapStaff in elevated console; enter installation administrator and independent staff password | One-time manager created; repeat bootstrap refused; credentials not printed | Pending |
| Catalogue API | Use enrolled certificate plus staff login; import reviewed synthetic branch fixture using Stage 4 instructions | Exact counts/hash; authenticated menu works; other branch and missing/incorrect staff rejected | Pending |
| Replay / local edits | Edit one local price using expected revision, retry original snapshot, then a different snapshot | Local edit persists; re-bootstrap cannot overwrite | Pending |
| Restart / reboot | Reboot Windows after successful bootstrap/import; start JavaFX and inspect service/API readiness | Same branch/cluster/installation and staff/catalogue data; PostgreSQL then backend recover | Pending |
| Standard-user ACL | With an independent non-admin account inspect access to protected config, database, secrets and downloads | Cannot read keys/password bundles or write binaries/cache/database | Pending |
| Slave install | Fresh second checkpoint/terminal: install Slave, pair using protected Master invitation | JavaFX/runtime only; no PostgreSQL/backend service; branch/certificate trust checked | Pending |
| LAN discovery / fallback | Separate Master/Slave on private LAN, test discovery then manual hostname/IP | Correct trusted branch Master; untrusted certificate or foreign branch rejected | Pending |
| Firewall | Check private/public profiles and listener scope | HTTPS private profile only; PostgreSQL loopback only | Pending |
| Backup | Run protected local backup command using Stage 3 instructions | Encrypted DPAPI artifact; successful backup leaves no plaintext dump; no credential log | Pending |
| Uninstall / reinstall | Uninstall Master, retain data; reinstall/repair same VM | App services removed/recreated; DB/keys/staff/catalogue and identities preserved | Pending |

The GitHub test-package script performs destructive fixture setup on its disposable runner, including
fresh installations, role changes and test-data movement. Do not run `Test-Package.ps1` on an existing
installation to perform this manual checklist. It is CI tooling, not an end-user acceptance wizard.

A real separate terminal/network is necessary for LAN tests. A single VM is sufficient for UAC, reboot,
installation and ACL checks, but does not establish multi-machine connectivity. Interactive tests remain
pending until actually observed; CI service restart is not evidence of a Windows client reboot.

## Rollback

On the disposable VM, stop new API commands and retain needed evidence. Use the normal installer uninstaller;
verify services are removed and retained database/key files remain. Revert the pre-test VM checkpoint only
when all test data is disposable. On any non-disposable installation, preserve verified backups and keys;
never delete the database or restore an older snapshot over newer commands. Nothing in this procedure
changes production cloud, enables sales/sync, or authorizes a live store rollout.
