# Stage 5, increment 2: Administration — staff and access

Authorized by the user's Administration implementation request on 25 October 2026. The subsequent
request to use fields from the cloud SQL directory was explicitly withdrawn; this increment follows
the approved username/roles/account-status plan. No cloud schema or staff records are imported or edited.

## Desktop

The Administration module now opens a JavaFX sidebar panel after its existing staff PIN check. The
left sidebar has Overview, Staff, Roles & Permissions and Audit History. Selection is highlighted;
sections are hidden unless the current staff member has the corresponding permission. Toolbars wrap
at compact window widths; the staff form and list are checked at the existing 900-pixel minimum. The header shows
store/terminal, authenticated username, connection status and Home / Lock. It replaces the grid header
while Administration is open, preserving space for the forms.

- Overview shows owner-setup status and summaries only for sections the actor can access.
- Staff supports username search, paging, create/edit, enabled state and multiple role assignments.
  New accounts are PIN-only; existing password recovery remains unchanged. Accounts awaiting owner PIN
  setup cannot sign in. The owner alone sees Set PIN / Reset PIN; PINs are masked, entered twice and
  cleared immediately on submit, cancel or locking. There is no PIN reveal or delete-account operation.
- Roles & Permissions supports new roles and revision-checked permission replacement, grouped into
  module entry and administrative actions. Role names are immutable after creation. Reserved future
  permissions are not selectable; existing roles containing them remain read-only in this panel.
- Audit History is read-only, newest first, with staff/action/date filters and 50-row pages. Dates are
  shown in the terminal's local timezone. Raw historical audit details are not returned by this API.

Switching sections, accounts or refreshing prompts before discarding edited staff/role forms. Cancel
keeps the current form and sidebar selection. Home / Lock and five-minute inactivity locking clear the
forms immediately, including any open PIN dialog. Keyboard, mouse and touch activity in Administration
and its dialogs feeds the existing inactivity timer. Revoked sessions return to the grid. Requests run
on worker threads; closed panels ignore late responses. Failed saves preserve the form and reuse the
command ID when retrying an unchanged request. Conflicts request an explicit reload and never report
success. Saving a change that revokes the current session returns to the grid for another PIN entry.

## Local authority, owner and credentials

V4 adds `pos.store_owner`, explicit `password_login_enabled` and role revisions. It preserves existing
staff UUIDs, usernames, role memberships, PIN hashes/lookups, password hashes, catalogue and price data.
No owner is guessed during migration. MANAGER defaults gain `audit.view`; levels remain configurable.

Owner identity is separate from role names and cannot be granted by an HTTP role edit. The enabled
owner always has Administration entry, staff management, RBAC management and audit viewing, even if an
assigned role changes. Other staff cannot edit the owner account; nobody can disable the owner through
the panel. Ownership transfer is a protected local-console operation. Existing final-administrator
protection now also counts the permanent owner policy. Roles and permissions remain additive.

Non-owners may delegate only permissions they currently hold and cannot modify a more privileged role
or account. The owner may manage all implemented permissions. This prevents `staff.manage` alone from
being used to assign a more powerful role to oneself. Owner-only PIN authority is not an assignable
permission, including for RBAC editors.

New accounts have password login explicitly disabled and an unusable random salted password hash to
preserve the existing non-null schema. Password authentication and existing password sessions check
eligibility. Existing accounts default to password login enabled. Their password recovery behaviour is
preserved; the panel does not add password editing or enable password login on a new PIN-only account.

Admin writes use the existing branch command-stream lock, command receipts, audit and transactional
outbox conventions. Narrow fixed-search-path SECURITY DEFINER functions validate a live, enabled,
terminal-bound Administration session and permission before modifying privileged role/PIN tables. The
runtime has no write grant on owner designation or the RBAC/PIN tables. SQL guards also protect the
owner against legacy staff API writes. PIN hashes, lookup digests and keys never leave credential
storage through these endpoints; audit/outbox/receipt results contain only safe metadata. Database
mutation failures are translated to sanitized messages, without propagating PIN-constraint details.

## API

All `/api/local/admin/*` endpoints require an enrolled terminal and an Administration-scoped module
bearer token. Password sessions and tokens for other modules cannot use them.

| Method and path | Required authority | Behaviour |
| --- | --- | --- |
| GET `/overview` | Administration entry | Current context, owner configuration and allowed summaries |
| GET `/staff?search=&page=0` | `staff.manage` or owner | Safe account summaries, roles, status, PIN-configured flag and revisions |
| GET `/staff/options` | `staff.manage` or owner | Roles the actor is allowed to assign |
| PUT `/staff` | `staff.manage` or owner | Create/edit account and replace roles; protect owner and privilege boundaries |
| GET `/roles` | `rbac.manage` or owner | Role definitions, permissions, edit eligibility and revisions |
| PUT `/roles` | `rbac.manage` or owner | Create role or replace implemented permissions |
| PUT `/staff/{uuid}/pin` | Designated owner | Assign/reset a six-digit PIN with staff revision check |
| GET `/audit?page=0&actor=&action=&from=&to=` | `audit.view` or owner | Safe immutable audit columns; optional ISO-instant boundaries |

Mutation contracts are shared in `Administration`: command UUID plus expected revision. New staff and
roles use `expectedRevision: -1`; existing records use their current nonnegative revision. This makes
creation distinct from editing a record at revision zero. PIN changes require a current staff revision.
Retries with the same command ID and content return the receipt; reused IDs with different content
are refused. Authentication failures return 401, permission failures 403, invalid inputs 400, data or
revision conflicts 409 and database outages 503. Unique PIN conflicts do not identify their holder.

The legacy protected console still supports staff/PIN/role provisioning; it now increments role
revisions and supports owner designation. It remains an elevated recovery path rather than an RBAC
role that another staff administrator can acquire.

## Owner setup and recovery

On the Master, use elevated PowerShell and the installed protected management script. Existing
installation-administrator authentication is required; credentials are entered through masked console
input, never command arguments. Choose an existing enabled staff account explicitly:

```powershell
& 'C:\Program Files\OnlinePOS\setup\Local-Database.ps1' -Operation SetPin -Username manager
& 'C:\Program Files\OnlinePOS\setup\Local-Database.ps1' -Operation SetOwner -Username manager
```

SetOwner designates the first owner or transfers ownership to another enabled account. It revokes old
and new owner sessions; the new owner must have a configured PIN before using the panel. The same
SetPin console operation recovers a forgotten owner PIN. Keep the installation-administrator credential
and protected PIN lookup key in the existing protected recovery process. Ordinary staff administrators
cannot invoke this path using only their POS PIN or role permissions. No owner-transfer HTTP endpoint
or owner-selection control is exposed in JavaFX.

## Validation and boundaries

Local JDK 25 / disposable PostgreSQL 16.15 validation passes the full reactor: **67 tests, zero failures
or errors, two Windows-only skips**. This includes 26 PostgreSQL local-authority scenarios, V2 and V3
upgrade checks, four Administration GUI scenarios and the existing home/setup GUI tests. New scenarios
cover owner designation/transfer, PIN-only credentials, password compatibility, command replay,
creation and edit conflicts, privilege delegation, direct SQL/API owner bypass attempts, session
revocation, owner self-PIN reset, audit permissions/filters/paging and atomic rollback on duplicate PINs.

GUI checks cover hidden tabs, unsaved-change cancel/discard, role/staff forms, offline save retry with
one command identity, PIN clearing, Home / Lock and actual Administration entry/idle locking through
the home module. Rendered Overview, Staff, Roles and Audit screens were inspected. Preview images are
in `pos-desktop/target/admin-*.png` and are generated only by GUI tests with synthetic data.

The Windows package test now designates a synthetic owner through the protected console and exercises
installed TLS administration reads, role/staff creation, idempotent replay, owner-only PIN assignment,
audit viewing, unauthorized section/PIN denial and persistence after service restart. Exact CI evidence
is recorded below after execution. The existing Windows 11 PC is not modified by this increment.

Sales, catalogue editing, customers, cash management, printers, backups UI, store settings and the shared
sales-screen builder remain separate increments. Sales/cloud sync/failover stay disabled. Existing
Windows 10, separate Windows Slave, interactive UAC and production cutover gates remain unchanged.
Rollback is fix-forward for application errors; do not start an older server against a database with
owner/PIN-only protections. Restore only a verified isolated matching backup with its protected keys.
