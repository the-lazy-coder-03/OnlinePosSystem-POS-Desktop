# Stage 5, increment 1: module home and PIN access

Authorized by the user's module-home implementation request on 25 October 2026. This increment does
not complete Stage 5 sales functionality. It does not deploy to a store, modify the cloud database,
enable sales, or implement the shared sales-screen builder.

## Desktop behaviour

Configured terminals (`INSTALLED`) open a 3 by 3 module grid without prior staff login. Unconfigured
or incomplete installations retain the setup wizard; successful setup routes into the grid. An unreadable
configuration produces a recovery message rather than allowing replacement of the identity.

| First column | Second column | Third column |
| --- | --- | --- |
| Sales & Orders | Customers | Catalogue Management |
| Cash Management | Reports | Administration |
| Empty | Empty | Empty |

Empty positions cannot receive focus or clicks. Tiles support touch/mouse activation, tab and arrow-key
navigation, and visible focus. Each module requests a masked six-digit staff PIN, including leading
zeros, using the keyboard or on-screen keypad. Cancel, invalid PIN and denied permission return to the
grid. Generic denial messages do not reveal whether a staff account exists. The home header shows
store/terminal identity and periodically checks the pinned Master connection. An unavailable Master
does not remove the grid and cannot authorize access.

The module shell displays the staff username (the current staff schema has no separate display name),
a clear unfinished-functionality notice and Home / Lock. Switching modules requires another PIN.
Local inactivity locks after five minutes; server expiry independently enforces five minutes without
activity refresh. Fifteen-second session checks detect revocation or disconnection. Polling alone does
not extend the session; recent keyboard, mouse-button or touch input permits an activity refresh.
Sessions and PINs are never saved in desktop storage. Closing locks the UI and attempts server logout;
if the server is unreachable, its remaining session expires within five minutes. Existing business data
is not edited or discarded by this shell because business workflows are not implemented yet.

## Local authority and interfaces

V3 is an additive local migration. It adds branch-scoped named roles, role permissions, multiple role
assignments per staff member, unique branch PIN lookup digests, PIN hashes, module sessions and durable
throttle counters. Existing usernames/passwords and password recovery remain available.

Entry permissions are `module.sales_orders`, `module.customers`, `module.catalogue`, `module.cash`,
`module.reports`, and `module.administration`. Action permissions are separate: `staff.manage`,
`rbac.manage`, and `sales.layouts.edit`. No sales-layout action is implemented or granted by default.
The initial MANAGER role receives all module-entry permissions plus staff/RBAC management; CASHIER
receives Sales & Orders entry only. These names are compatibility defaults, not final business levels.
Existing staff receive their compatible role; new staff created through the legacy staff API receive
that default too. Protected role assignment can replace it with multiple custom roles.

The local TLS API requires an enrolled terminal (the existing explicit loopback development profile
remains available for integration testing):

- `POST /api/local/modules/enter`: JSON `{ "pin": "<six digits>", "module": "REPORTS" }`.
  Returns an opaque token, staff ID/username, selected module, permissions and a 300-second lifetime.
- `GET /api/local/modules/me`: module bearer token; returns the current staff/module/permissions.
- `POST /api/local/modules/activity`: module bearer token; validates and refreshes idle expiry.
- `POST /api/local/modules/logout`: module bearer token; deletes the session.

Module tokens have a separate security authority. They cannot call legacy menu, staff, price or draft
APIs. Password tokens cannot enter module-session routes. All other module URLs are denied; future
business routes must explicitly require their module and action permissions. Backend identity checks,
branch identity, terminal binding, expiry, enabled state and credential version are enforced on every
module request. Role/permission/PIN/staff changes revoke affected password and module sessions through
protected database triggers. The final enabled RBAC administrator cannot be removed, including by
role or permission edits; deferred checks allow safe atomic role replacement. Owner-console operations
lock the branch command stream and write a transactional audit record without secret material.

PINs use salted PBKDF2-HMAC-SHA256 with 600,000 iterations, plus an HMAC-SHA256 lookup keyed by a separate
32-byte protected server secret. There are no generated/default staff PINs. The Windows migration tool
creates `secrets/pin-key.dpapi` once, retained on upgrades and readable by SYSTEM, Administrators and the
backend service, not ordinary desktop users. Keep this key with protected installation recovery material;
PIN lookup cannot survive an unrelated key replacement. Local development uses the `pos.pin-key`
property, supplied securely (for example through `POS_PIN_KEY`), never committed.

Five failed attempts per enrolled terminal cause a five-minute cooldown; thirty failed attempts across
the branch within five minutes block fresh terminals too. Counters survive service restart, and cooldown
expiry permits recovery. Invalid PIN shape also consumes an attempt. Valid credentials without module
permission receive a generic denial and no session.

## Protected provisioning

Run the installed `C:\Program Files\OnlinePOS\setup\Local-Database.ps1` from an elevated PowerShell.
Every operation below verifies the installation administrator; SetPin then reads the new PIN through
masked console input. Do not place secrets in command arguments. Staff must already exist; the existing
BootstrapStaff command remains the initial manager-creation path. Existing password authentication
remains available independently of PIN entry.

```powershell
& 'C:\Program Files\OnlinePOS\setup\Local-Database.ps1' -Operation SetPin -Username manager
& 'C:\Program Files\OnlinePOS\setup\Local-Database.ps1' -Operation SetRole -Role SUPERVISOR -Permissions module.sales_orders,module.reports
& 'C:\Program Files\OnlinePOS\setup\Local-Database.ps1' -Operation AssignRoles -Username cashier -Roles CASHIER,SUPERVISOR
```

SetRole replaces that role's permissions; AssignRoles replaces the staff member's role set. Both are
transactional. Duplicate PINs, unknown roles/permissions and loss of the final administrator are refused.
`--database-tool=set-pin`, `set-role`, and `assign-roles` expose the same owner-console operations in the
server JAR. Non-Windows development requires the existing explicit owner/identity environment and
`POS_PIN_KEY` for SetPin. `--password-stdin` is reserved for controlled automation; never pipe secrets to
an interactive shell history or write them to a file. No Administration UI is supplied in this increment.

## Verification and limits

Local JDK 25 / PostgreSQL 16.15 verification covers the full reactor, formatting, static analysis,
architecture boundaries and 20 local PostgreSQL scenarios plus a V2-to-V3 upgrade scenario. Five new PostgreSQL scenarios cover PIN
entry/leading zeros/logout, HTTP boundaries, duplicate PINs, multiple additive roles, denial, disablement,
role/permission revocation, expiry, durable terminal/branch throttling, legacy staff-management enforcement and final-administrator safety.
GUI tests (`POS_TEST_FX=true`) exercise the actual JavaFX grid, cancellation, PIN clearing, refusal,
success, Home / Lock, the inactivity timeout with a controlled clock, configured/incomplete/new startup
routing and transition from successful setup into the grid. Their rendered screenshot is
`pos-desktop/target/module-home.png`. Platform-specific tests remain separate on non-Windows hosts.

The Windows workflow enables GUI tests, packages both setup and home-grid smoke paths, and exercises
protected PIN provisioning plus real installed TLS module entry, session separation and logout before
and after a service restart. Exact run results are recorded below once execution completes.

Remaining work: operational module content, full sales/pricing/printing workflows, custom shared sales
layouts, final role design and staff onboarding. Earlier UAC, Windows 10, separate Windows Slave and
production-data/cutover gates still apply. Do not roll an upgraded database back to an older application
that lacks these access controls; retain the additive data and fix forward, or restore a verified
isolated pre-upgrade backup with the corresponding application and protected keys.
