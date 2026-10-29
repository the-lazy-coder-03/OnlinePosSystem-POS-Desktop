# Guided terminal setup

Terminal installation has three steps: choose Main computer or Additional till; name the store or choose its main computer; confirm and finish. Technical explanations are available through Help. Optional business and terminal names live under More options.

A fresh main computer uses a local numeric branch ID of 1. This ID is scoped to its own database, not a global store directory. Installation, cluster, business and store UUIDs distinguish independent stores. A store code is generated once and retained with the initial profile. Additional tills inherit the selected main computer's branch and cluster identity, including arbitrary positive branch IDs. No Kenridge/Uitzicht allowlist is used.

`SetupRequest` and `Installation` accept an optional initial `StoreProfile`; older JSON without it still loads. Elevated preparation saves the profile with the installation identity before database creation. Initial database migration writes names and code in the same transaction as store identity and stream initialization. Repeated migration never overwrites an existing profile or changes UUIDs. Existing database columns are reused; no new migration is required.

The existing `/api/local/setup/bootstrap` wire format is unchanged. New clients optionally read `/api/local/setup/store` over the same validated HTTPS connection for current display names. This read-only endpoint shares the setup LAN, TLS and request-rate restrictions. Older servers without it remain connectable with a numeric store label. Discovery is only a hint: the wizard verifies the server certificate, active role, branch and cluster before displaying a choice; elevated provisioning rechecks the selected cluster and CA before saving first-use trust. Saved trust cannot be replaced.

Each store has its own main computer/database. This work does not add a cloud directory, store switching, account authentication changes or automatic role reassignment. Existing installations keep their identities, roles and initial names; ordinary setup refuses reassignment. Later name changes remain in Store Management.

For visual review, run `./mvnw -pl pos-desktop javafx:run -Ddesktop.mainClass=za.co.onlinepos.desktop.SetupPreview` (use `mvnw.cmd` on Windows). This preview never invokes provisioning or writes installation state. Its network choices are real on Windows and illustrative on other platforms.
