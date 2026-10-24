# Stage 1 Windows validation

Run on a Windows x64 workstation or VM with JDK 25 and a graphical desktop. Stage 1 needs no admin
rights, PostgreSQL or production credentials. GitHub Actions repeats build and launch on Windows.
Configured CI is not evidence of a completed Windows run until that job actually succeeds.

1. Open the root pom.xml in IntelliJ as a Maven project; select JDK 25 for project and Maven runner.
2. Run `.\mvnw.cmd -B -ntp clean install`. All modules, formatting, Checkstyle, unit and integration tests
   must pass. Preserve surefire/failsafe reports and Java/Maven versions.
3. Run `.\mvnw.cmd -pl pos-desktop javafx:run '-Ddesktop.args=--smoke-test'`. A real JavaFX window must
   open, reject incomplete setup, review an Uitzicht Slave, print DESKTOP_SMOKE_OK and exit successfully.
4. Run `.\scripts\run-desktop.cmd`. Verify branch selection, both Master/Slave choices, trimmed terminal
   names, blank-name errors, review/back navigation, visible keyboard focus and minimum window size.
   There must be no Backup promotion, install action or sales button. Close and reopen: no saved role
   should be claimed, because Stage 1 is a transient preview.
5. Start the separate backend executable with `java -jar store-server/target/store-server-0.1.0-SNAPSHOT-exec.jar`.
   Read `/api/local/health` on 127.0.0.1:18090. Sales/cloud/automatic promotion must be false.
6. GET/POST `/api/local/orders` and `/actuator/env` must not be available. Confirm the service listens on
   loopback only. Stop it with Ctrl+C; no Windows service or database should remain.
7. Run Gitleaks 8.30.1 source scanning and, after commits exist, history scanning with `--redact`.

Installer signatures, UAC, service reboot, printer drivers, PostgreSQL, replication and power-loss tests
do not apply to this foundation and are unexecuted. They are later stage acceptance requirements.

## Rollback

Stop the preview and backend. Archive this independent directory if it is no longer needed. No cloud
files, schemas, firewall rules, Windows services or production data were changed. Maven's local cache
may contain POS build artifacts; leaving it in place has no effect on the website. Do not run any cloud
deployment or production rollback script to undo Stage 1.
