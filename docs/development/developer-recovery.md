# Developer recovery access

Recovery is optional and disabled until deliberately enrolled on the Master. It is a separate,
installation-bound credential, not a staff account or a module login. Owners can see its status and
disable it in **Administration → System Access → Developer Recovery**. Every reset attempt and every
enrolment, rotation and disablement is mandatory security audit history.

## Enrolment and operation

On the configured Master, open an elevated PowerShell console and run:

```powershell
& 'C:\Program Files\OnlinePOS\setup\Local-Database.ps1' -Operation RecoveryEnrol
```

Enter the existing installation-administrator password and a five-digit developer PIN in the masked
prompts. No credential is supplied as a command argument, saved in a script, compiled into the app or
included in installer defaults. The requested temporary development PIN must be entered only during
deliberate enrolment; replace it before live-store use. It is not automatically provisioned by tests.
The designated, enabled owner must already exist.

Use `RecoveryRotate` to replace or re-enable the credential. Use `RecoveryDisable` to disable it from
protected local management. These operations also authenticate the installation administrator. Rotation
does not clear a current recovery cooldown. Ordinary staff/owner PINs remain six digits.

From a protected module PIN dialog on the Master, choose **Forgot owner PIN**. Windows asks for
administrative elevation; the local console then asks for the installation-administrator password,
developer PIN, new six-digit owner PIN and confirmation. The shortcut is absent on Slaves. The same
flow can be opened locally with `Local-Database.ps1 -Operation RecoverOwner`.

Recovery can change only the currently designated owner's PIN. It never reveals the previous PIN,
changes ownership, resets other staff credentials or grants developer access to business modules.
Both owner module sessions and password sessions are invalidated atomically with the PIN change and
audit event. Sign in afresh on each terminal. New PINs already assigned to another staff member are
rejected. Five failures in a five-minute window cause a persistent five-minute cooldown; administrator
password throttling also remains in force. Recovery remains unavailable when the database/audit
transaction cannot commit.

## Interfaces and storage

V8 adds an owner-only recovery verifier table; neither the verifier nor credential is readable by
`pos_runtime`. It does not depend on V7's audit identity changes. Hash input includes cluster and
installation UUIDs, with a fresh random salt and PBKDF2-HMAC-SHA256 at the existing work factor. No
recovery credential is copied to Slaves, outbox events, command receipts or logs.

Authenticated owner Administration sessions may call:

- `GET /api/local/admin/system-access/developer-recovery` — configured/enabled status and change time.
- `PUT /api/local/admin/system-access/developer-recovery/disable` — disable only; no enrol/reset option.

PostgreSQL functions independently validate the current owner, live session and terminal binding.
Enrolment, rotation and reset have **no HTTP endpoints**. Only the elevated local management tool can
perform them. Audit event types are `DEVELOPER_RECOVERY_ENROLLED`, `DEVELOPER_RECOVERY_ROTATED`,
`DEVELOPER_RECOVERY_DISABLED`, `DEVELOPER_RECOVERY_FAILED` and `OWNER_PIN_RECOVERED`.

## Rollout and rollback

Review the changes and automated Windows evidence before updating either development PC. Back up the
Master, stop its backend, apply the additive migration through the protected owner migration flow,
update the Master and Slave applications, then deliberately enrol recovery on the Master. Do not run
CI fixture scripts on an existing PC. Windows 10/11 interactive UAC, cancellation and masked-console
checks remain separate acceptance steps; CI runners cannot establish those client behaviours.

For rollback, disable recovery while the new backend is still available, then restore the prior
application version. Keep the additive table and audit events. A successfully reset owner PIN stays
valid; never restore an old database or erase audit history to undo a reset. Existing accounts,
terminal registration, catalogue data and the PIN-free Sales entry are preserved.
