# Stage 1 version baseline

| Component | Pin / use |
| --- | --- |
| JDK | 25, enforced range [25,26); CI uses Temurin's maintained JDK 25 patch |
| Maven | 3.9.16 via Apache wrapper 3.3.2; distribution SHA-256 in wrapper properties |
| JavaFX | 25.0.2, native artifacts selected for the build host by OpenJFX |
| Spring Boot | 3.5.16 dependency BOM and executable plugin |
| Jackson | 2.21.5 BOM |
| PostgreSQL | Target server 16.15; no server downloaded or installed in Stage 1 |
| PostgreSQL JDBC | 42.7.12 in dependency management; attached when persistence is implemented |
| H2 | 2.3.232 (Boot baseline), runtime dependency in pos-emergency; no DB opened |
| Flyway | 11.7.2 (Boot baseline), managed only; no migrations or startup runner yet |
| JUnit | 5.12.2 |
| Surefire / Failsafe | 3.5.6 |
| ArchUnit | 1.5.1 (supports JDK 25 bytecode) |
| Spotless / Google Java Format | 2.44.3 / 1.28.0 |
| Checkstyle / Maven plugin | 10.26.1 / 3.6.0 |
| Gitleaks | 8.30.1, verified Linux binary SHA-256 pinned in CI |

Maven compiler/resources/packaging/install and other lifecycle plugin versions are pinned in the parent
POM. The BOM pins transitive dependency versions; no dynamic LATEST or version ranges are used for
libraries. The JDK major is deliberately fixed while CI takes maintained vendor patch updates.
Record the exact JDK/vendor and Windows payload hashes when release packaging is implemented.

Generate resolved dependency evidence with `./mvnw dependency:tree`; native JavaFX classifiers differ
between Windows x64 and macOS ARM. The JavaFX preview is non-modular on the application classpath;
the OpenJFX plugin manages native JavaFX modules. A standalone runtime/installer is Stage 2 work.

The POSIX wrapper prefers shasum over sha256sum because the host's sha256sum does not support stdin
checksum validation. Both paths still require the exact pinned SHA-256; verification is never skipped.

Do not regenerate the wrapper without preserving its checksum and documented tool selection.
No cloud runtime upgrade is authorized by choosing this local baseline.

## Stage 2 pins

Weld SE 6.0.4.Final supplies Jakarta CDI 4.1-compatible APIs; the root dependency management pins the
resolved API versions. Installation adapters use Bouncy Castle 1.83, JNA platform 5.18.1 and JmDNS 3.6.3.
Windows payloads (Inno Setup 6.7.3, WinSW 2.12.0, PostgreSQL 16.15 and VC runtime) have immutable source
URLs and verified SHA-256 hashes in windows-installer/dependencies.json. Each generated package records
its exact JDK release and payload hashes. Maven Central supplies the POM-pinned Java dependencies;
release-manifest.json hashes the actual bundled jars and JavaFX natives.
Temurin 25 supports linking directly from the runtime image without a jmods directory (JEP 493), so
Build-Package enumerates explicit JDK module names rather than relying on ALL-MODULE-PATH.
