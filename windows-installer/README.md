# Windows packaging boundary — Stage 1

This Maven POM module reserves Windows packaging ownership. It does not produce an installer yet.

Stage 2 will build on Windows x64 using jpackage application images and a signed bootstrap installer.
Bundle the Java runtime and released backend; verify every externally acquired payload against the
approved release manifest. Runtime executables belong under Program Files and protected machine data
under ProgramData. An ordinary Slave receives neither PostgreSQL nor the backend service.

Master provisions a dedicated PostgreSQL cluster and least-privilege backend service. Backup Master
requires a protected workflow and starts as a standby. No two-node automatic promotion is allowed.
Pairing, LAN TLS and firewall rules must be implemented together before exposing the backend to a LAN.

No PostgreSQL installer URL/hash is fabricated here: there is no installer payload in Stage 1.
Acquire, verify and lock those platform payloads only in the separately approved installer stage.
Uninstall and role changes must preserve databases, keys and unresolved terminal queues.
