# Windows Stage 2 development installers

Build on Windows x64 with JDK 25 after the Maven reactor is installed:

```powershell
.\windows-installer\scripts\Build-Package.ps1 -Channel Development
```

The unsigned Inno installer bundles JavaFX and its runtime. Slave installs exclude the backend and
PostgreSQL service. Master provisions a dedicated cluster; managed Backup components remain inactive
and unseeded. External payload URLs and SHA-256 values are locked in dependencies.json.

See [operations and rollback](../docs/development/stage2-windows.md) and
[Stage 2 evidence](../docs/stages/02-windows-setup.md). Test-Package.ps1 is destructive to app-owned
resources and must only run on disposable Windows CI/VMs. Production signing and Windows 10/11 client
validation remain release gates. Sales, business migrations, replication and automatic promotion are absent.
