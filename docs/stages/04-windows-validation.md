# Stage 3/4 Windows validation

The user authorized continued Windows validation on 25 October 2026 and confirmed a disposable Windows
PC/VM is available. Connection details are pending. Automated validation is executing on an isolated
GitHub-hosted Windows runner; manual client UAC/reboot/LAN acceptance is separate.

Validation branch: `codex/windows-stage4-validation`. It incorporates current main (including the existing
GlassFish packaging support), the Stage 3 follow-up fixes and Stage 4 catalogue import. Changes were
pushed to this branch to allow Windows execution; no main merge or release deployment was performed.

Initial run: https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40184494465
Status: in progress; final result and artifact links will be recorded after completion.

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
