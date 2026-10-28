# LAN setup and reconnection

Slave setup asks for branch, terminal name, Slave role, and the Master's private IPv4 address. It uses HTTPS port 8443 without exposing API addresses, JSON invitations or certificates in the UI. The Windows helper still requires UAC to protect machine configuration, but Slave enrolment asks for no installation password, joining code or Master approval. Master installation retains its existing administrator credential. Its setup screen and home grid display the Master LAN IP for staff to enter on Slaves.

## Trust and ownership

New registrations are always enabled on the configured LAN. First use trusts the CA presented by the server at the IP entered, after checking its chain, validity, hostname, active Master role and selected branch. This is not independent authentication of the first Master: a hostile machine at that IP can become the first trusted Master. Any device admitted to the configured LAN can enrol. This deliberately supersedes the original approval requirement; LAN membership does not grant a staff PIN/session or business permissions.

The terminal creates its UUID and RSA key locally. DPAPI stores its private key and first-use trust before registration. A two-minute, single-use server challenge binds the branch, cluster, terminal UUID, name and public key. The proof is signed by that key. Registrations and challenge consumption commit atomically in the versioned file registry. A new proof for the same UUID/key returns the existing certificate after a lost response; replay of a consumed challenge fails. Revocation and UUID/key conflicts require protected maintenance and cannot be undone by open enrolment. Existing invitation tooling remains available for compatibility.

## Network configuration

`lan-policy.json` records the approved Windows adapter GUID/name, private IPv4 address and prefix. Setup automatically selects a single eligible active physical Private-profile adapter or shows a network-name selector. The elevated helper independently rechecks that selection. `network.json` retains ports but receives the exact subnet; legacy `LocalSubnet` is never used to provision new firewall rules.

Installed Tomcat has separate listeners on the approved IPv4 address and 127.0.0.1, not wildcard or IPv6 listeners. Windows Firewall admits only the selected interface/subnet on the Private profile; HTTPS is also restricted to that local IP. The terminal filter checks actual remote/local socket addresses before bootstrap or authenticated endpoints. Forwarded headers do not grant access. PostgreSQL access remains unchanged.

`OnlinePOS-NetworkRefresh` runs as SYSTEM at startup and every minute. It preserves the approved adapter/subnet and CA; after DHCP changes it renews the server IP SAN, updates firewall rules and restarts the backend. A missing/non-private adapter stops the backend and removes its inbound rules. A subnet change requires protected setup/repair. Network refresh uses the same provisioning/management locks. It cannot promote a Backup Master. Uninstall removes the task and application rules but preserves data and identities.

## Reconnection and management

The desktop checks authenticated identity approximately every ten seconds when connected, with bounded retry intervals up to thirty seconds after failure. It tries the remembered address, then mDNS candidates on the approved private adapter/subnet. Every candidate must match the existing CA, branch and cluster and pass normal hostname validation. Address changes are cached per desktop client; a restart can rediscover the same identity. Failed discovery never deletes credentials or routes an installed terminal back to setup.

Administration → Terminals lists registered Slaves, observed IPv4 addresses, last contact, online/offline and revoked status. Online means authenticated contact within thirty seconds. The screen refreshes every ten seconds and provides rename/revoke for the owner or `rbac.manage` delegates; the backend independently checks the Administration session and permission. There is no approval queue. Master terminal revocation is refused. Sales continues to open without a PIN; no new sales/emergency/failover capabilities are introduced.

## API additions

- `GET /api/local/setup/bootstrap`: public CA and Master installation identity, allowed LAN only.
- `POST /api/local/setup/challenge`: terminal identity/public key → challenge nonce and expiry.
- `POST /api/local/setup/enrol`: bound request plus signed challenge → registered client certificate.
- `GET /api/local/terminal/heartbeat`: authenticated terminal contact; identity checks also record contact.
- `GET /api/local/admin/terminals`: authorized terminal inventory.
- `PUT /api/local/admin/terminals/{uuid}`: rename (`name`, `revoke=false`) or revoke (`revoke=true`).

Bootstrap/enrolment responses are not cached. Requests are size-limited and rate-limited per source; pending challenges are bounded. Private keys, invitation tokens and staff credentials are not returned. Existing business migrations are unchanged. Installation/request records accept legacy shapes; missing registry version/challenge fields are upgraded on the next write while preserving certificates and revocations.

## Verification and rollback

Server leaf certificates use a distinct subject from the CA so PKCS12 preserves the complete chain. A real Spring Boot/Tomcat regression test verifies this in addition to the lightweight HTTPS fixtures.

Automated tests cover first-use HTTPS with normal IP hostname checks, wrong branch, changed CA, lost-response retry, challenge replay/expiry/tampering, key conflicts, concurrent redemption, persisted revocation, old registry loading, actual socket interface boundaries, permission checks, JavaFX setup-to-grid navigation and PIN-free Sales. The Windows package smoke client exercises enrolment against the installed service's actual LAN address, interrupted local credential storage, restart and revocation. Existing database, catalogue, audit and architecture checks remain required.

Windows 10/11 physical/VM testing is still a release gate for UAC, profile transitions, DHCP changes, real multicast and two-machine LAN operation; hosted Windows CI is not a substitute. Production signing remains required for production distribution.

Before rollout, preserve ProgramData including secrets, installation, registry, network policy and database backups. Stop the app-owned refresh task and backend before reverting binaries. Keep the exact adapter/subnet firewall policy; do not restore wildcard exposure. Do not delete new terminal certificates or overwrite business data. Restoring an older registry file after new registrations would lose those registrations and requires explicit reconciliation. No cloud change or live-store deployment is part of this work.

Test evidence will be recorded with the delivered pull request and CI artifacts.
