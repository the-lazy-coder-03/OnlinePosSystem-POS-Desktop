## Additional Phase: Simplify Master and Slave LAN Connection

### Objective

Simplify how Slave computers connect to the Master computer.

**All POS computers within a branch will communicate over the same local area network (LAN). Remote computers outside the store network must not be able to connect or register as Slave terminals.**

The linking process should be almost entirely automatic and easy for nontechnical staff to complete.

### 1. Automatic Master discovery

When installing a Slave computer:

1. Detect the computer's active local network.
2. Automatically search the LAN for available OnlinePOS Master computers.
3. Display discovered Masters with their computer name, branch and local IP address.
4. Allow the user to select the correct Master.
5. Automatically establish a secure connection.
6. Complete the existing authorized terminal pairing process.
7. Save the connection information.
8. Open the existing POS grid when setup succeeds.

Use an appropriate local discovery mechanism such as mDNS/DNS-SD.

If automatic discovery fails, provide a simple manual local IP address option.

Do not require staff to enter API endpoints, certificate fingerprints, database connection strings, server ports or technical configuration details.

### 2. Simplified pairing approval

Keep pairing secure, but make it much easier to use.

Recommended workflow:

**On the Slave computer:**
- Select "Connect to Master".
- Automatically discover the Master.
- Select the Master from the list.
- Display "Waiting for Master approval".

**On the Master computer:**
- Show a notification that a new terminal wants to connect.
- Display its computer name and network information.
- Allow an authorized owner or manager to approve or reject the connection using their existing authentication.

**After approval:**
- Securely register the Slave.
- Exchange and store the necessary trusted connection credentials.
- Automatically open the Slave's POS grid.

Use the existing secure terminal enrolment architecture wherever possible.

### 3. LAN-only restrictions

The Master and Slave must communicate using private LAN connectivity.

Requirements:

- Do not expose the POS terminal pairing or local POS services to the public internet.
- Do not require port forwarding, public DNS names or public IP addresses.
- Do not require internet access for normal local Master/Slave communication.
- Limit incoming terminal connections to approved local network interfaces and configured store subnets.
- Configure Windows Firewall for the required local-network traffic only.
- Reject connections from unauthorized external networks.
- Continue using authenticated, encrypted Master/Slave communication.
- Do not assume that being on the LAN automatically makes a computer trusted.
- Require Master approval for new terminal registrations.
- Preserve registered terminal identities and revoke access when a terminal is removed.

Ensure the application does not accidentally expose Master services through other network interfaces, VPNs or IPv6.

Local-network restrictions and firewall rules must work together. Do not rely on private IP addresses alone as a security boundary.

### 4. Automatic reconnection

After successful pairing, the Slave must remember its Master.

On subsequent launches:

1. Load the stored, trusted Master identity.
2. Attempt to reconnect automatically.
3. Use local discovery to find the same trusted Master if its IP address has changed.
4. Verify that it is the previously approved Master.
5. Reconnect without asking the user to repeat the setup process.

A Master IP address change must not require reinstalling either computer.

If the Master is temporarily offline, show an appropriate connection status rather than returning to setup.

Preserve the project's existing emergency/offline functionality without expanding its capabilities.

### 5. Simple network management screen

Under the existing Administration module, provide a straightforward terminal management screen.

The Master administrator should be able to:

- View all registered Slave terminals.
- See whether each terminal is online or offline.
- See the terminal name and local IP address.
- Approve new pairing requests.
- Revoke a terminal's access.
- Rename a registered terminal.
- View its last connection time.

Only appropriately authorized users may approve or remove terminals.

### 6. Keep branches isolated

Kenridge and Uitzicht must remain separate local POS installations.

Each branch has its own Master identity.

A Slave must connect only to the Master belonging to its configured branch.

Do not allow accidental cross-branch registration or synchronization.

### 7. Expected user experience

```text
SLAVE COMPUTER
      |
      v
Install OnlinePOS
      |
      v
Choose Branch
      |
      v
Choose Slave
      |
      v
Search LAN Automatically
      |
      v
Available Master Found
      |
      v
Select Master
      |
      v
Master Approves Connection
      |
      v
Secure Pairing Completed
      |
      v
POS Module Grid
```

### 8. Tests and acceptance criteria

Verify the following:

- A Slave automatically discovers the Master on the same LAN when discovery is supported.
- Manual local IP connection works as a fallback.
- No cloud account or internet connection is required for local pairing.
- Unauthorized computers cannot join simply by being connected to the LAN.
- External/WAN connections are blocked by the configured network restrictions.
- Pairing requires authorization on the Master.
- A registered Slave reconnects automatically after Windows restarts.
- A registered Slave reconnects if the Master's DHCP IP address changes.
- Failed discovery does not delete existing pairing credentials.
- Slave setup opens the existing POS grid after successful pairing.
- The Master continues to function normally when all Slaves are disconnected.

### Important Codex instructions

**Simplicity is the priority, but not at the expense of security.**

Do not implement a cloud-based pairing service.

Do not require public domain names, port forwarding, a VPN or internet access for local pairing.

Do not replace the existing secure pairing implementation unnecessarily. Simplify the interface and automate technical steps behind the scenes.

Do not weaken certificate validation, device identity verification, Master approval or terminal authorization.

The final experience should be:

**Install OnlinePOS → Choose Slave → Select Master → Approve on Master → POS Grid.**

## Approved implementation override — 26 October 2026

The following user-approved decisions supersede the invitation, joining-code and per-terminal approval requirements above:

- Slave setup is **Choose branch → Choose Slave → Enter Master LAN IP → Connect → POS grid**. HTTPS port 8443 is internal configuration.
- New devices may enrol at any time from the explicitly configured store IPv4 adapter/subnet. No joining passcode, cloud login or individual Master approval is required.
- Initial connection uses trust on first use at the IP entered. It cannot independently authenticate the first Master or stop other devices on the allowed LAN from enrolling. Subsequent connections validate the pinned CA, branch/cluster, hostname and registered client certificate.
- Discovery is used internally to relocate the same trusted Master. It cannot replace an existing trust pin.
- Existing installation-administrator authentication remains for protected Master administration. Administration terminal rename/revoke uses the owner or existing `rbac.manage` permission.
- Exact adapter/subnet binding, Private-profile firewall restrictions, server-side socket-address checks and IPv4-only listeners replace broad all-interface defaults.
- No changes to cloud operation, business migrations, sales readiness, offline sale capabilities or automatic failover are authorized by this addition.

Implementation and verification details: `docs/development/lan-setup.md`.
