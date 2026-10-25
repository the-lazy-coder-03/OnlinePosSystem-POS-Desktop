# Stage 3/4 Windows validation

The user authorized continued Windows validation on 25 October 2026 and confirmed a disposable Windows
PC/VM is available. Connection details are pending. Automated validation passed on an isolated
GitHub-hosted Windows runner; manual client UAC/reboot/LAN acceptance is separate.

Validation branch: `codex/windows-stage4-validation`. It incorporates current main (including the existing
GlassFish packaging support), the Stage 3 follow-up fixes and Stage 4 catalogue import. Changes were
pushed to this branch to allow Windows execution; no main merge or release deployment was performed.

Successful run: https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40184668321
Tested commit: `c81269641b9e686c7b363373576fea41e7059bdf`.
Status: completed successfully on 25 October 2026, approximately 12:34 SAST.
Windows runner: Windows Server 2025, image `windows-2025-vs2026` version `20261021.278.1`,
Temurin JDK 25.0.4+101.0. This is not a Windows 10/11 client acceptance claim.

The earlier run 40184494465 was cancelled after a local cross-check found that the new smoke client's
snapshot fingerprint needed canonical source-key row ordering. That test-client issue was fixed and
its computed hash compared successfully with the server contract before the successful full rerun.

Checks include real PostgreSQL migrations/restore, staff credential input, authentication and branch
isolation, both source-seed imports, JavaFX/CDI launch, unsigned package creation, Master/Slave/unseeded
Backup boundaries, service virtual accounts, firewall, ACLs, repair/uninstall/reinstall, enrolled-terminal
TLS staff APIs and encrypted backup. Installed-package validation additionally imports all Kenridge seed
records, verifies source hash and all 241 baseline records, retries the same command, rejects foreign
branch/unauthenticated import, and verifies full catalogue persistence after backend service restart.

Source secret scan passed before push. The Windows TLS test client compiles locally. No current local
catalogue snapshots or local environment files are uploaded: CI uses committed synthetic/source seed
fixtures only. The user's local database is not accessed by Windows CI.

Manual client checklist and rollback: `../development/stage4-windows-acceptance.md`.

## Executed results

| Check | Result |
| --- | --- |
| Windows full build, format, Checkstyle and architecture checks | Passed |
| Windows automated tests, including real PostgreSQL and Windows-only tests | 45 passed; zero skipped/failures/errors |
| Linux full build and automated tests | 43 passed; two Windows-only tests skipped |
| Source/history secret scans | Passed |
| Actual Windows JavaFX/CDI startup and packaged runtime | Passed |
| Slave install boundaries / inactive unseeded Backup | Passed |
| Master service installation, repair, grants, virtual accounts and private firewall | Passed |
| Protected installation-admin input and staff bootstrap; installed TLS login/logout and branch rejection | Passed |
| Installed catalogue import, full source/hash readback, 241 baselines, replay, foreign-branch and unauthenticated rejection | Passed |
| Full catalogue readback after installed backend service restart | Passed |
| Protected encrypted backup with no successful plaintext remainder | Passed |
| Master uninstall/reinstall preserving identities, database and keys | Passed |
| Windows 10/11 interactive UAC, reboot, independent standard-user access and separate-terminal LAN | Pending PC/VM access |

Downloaded Windows and Linux JUnit XML artifacts were independently totaled; the counts above come from
those reports, not just the overall workflow conclusion. Installed checks emitted these success markers:
`PACKAGED_JAVAFX_CDI_OK`, `WINDOWS_BACKUP_INACTIVE_UNSEEDED_OK`,
`WINDOWS_STAGE3_MIGRATIONS_RUNTIME_GRANTS_OK`, `WINDOWS_STAGE3_STAFF_TLS_API_OK`,
`WINDOWS_STAGE4_CATALOGUE_TLS_IMPORT_REPLAY_OK`, `WINDOWS_STAGE4_CATALOGUE_RESTART_PERSISTENCE_OK`,
`WINDOWS_MASTER_INSTALL_REPAIR_GRANTS_FIREWALL_OK`,
`WINDOWS_MASTER_UNINSTALL_REINSTALL_PRESERVED_DATA_OK` and `LOCAL_BACKUP_OK SHA256`.

## Artifacts

- [Unsigned development installer, checksum file and installed service logs](https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40184668321/artifacts/12595687526)
- [Windows test reports](https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40184668321/artifacts/12595298118)
- [Linux test reports](https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40184668321/artifacts/12596180448)

Artifacts require repository access and are subject to GitHub retention. Installer filename is
`OnlinePOS-0.3.0-Development.exe`. Verify it using the bundled `SHA256SUMS.txt` before client testing.
Only the runner's disposable installation was changed. No live Master, production cloud or user's local
source database was modified by this validation. The validation branch has not been merged to main.

Rollback and manual client procedures remain in the linked checklist. Windows Server CI establishes the
automated package/service results above; it does not close the pending client-specific release gates.
