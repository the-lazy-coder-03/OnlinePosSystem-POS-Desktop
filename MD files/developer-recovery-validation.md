# Developer recovery validation

Implementation is on `codex/developer-recovery-pin`, draft PR #8. Neither development PC is updated
or enrolled by this change. Review and deliberate local enrolment remain pending.

## Executed locally

- JDK 25 Maven `verify`, PostgreSQL 16.15 disposable cluster and actual JavaFX tests enabled:
  **119 passed, zero failures/errors; two Windows-only tests skipped**.
- Six PostgreSQL recovery scenarios: default refusal, enrolment/reset, both-terminal session
  invalidation, persistent throttling, owner-visible disablement, runtime verifier isolation,
  wrong installation, rotation, non-owner refusal and audit-failure rollback.
- Two JavaFX tests: owner-only System Access/status controls, Master-only recovery shortcut,
  unchanged PIN-free Sales navigation.
- Existing catalogue, administration, audit, migration, TLS, CDI and architecture checks passed.
- Gitleaks directory scan: no secrets found. GitHub secret scan and Linux CI also passed.

## Windows evidence

CI run: https://github.com/the-lazy-coder-03/OnlinePosSystem-POS-Desktop/actions/runs/40338694811

The first Windows attempt failed in the pre-existing LAN TLS rediscovery test with an HTTP connect
timeout, before the recovery package checks. That job was retried; final status is recorded in the PR.
The new native package scenario uses generated fixture credentials through stdin to enrol, recover,
restore the fixture owner PIN, disable recovery and prove a further reset is refused.

Actual Windows 10/11 UAC approval/denial, masked interactive console, and installed Master/Slave
acceptance remain separate release checks. Never run the disposable CI fixture on an existing PC.
