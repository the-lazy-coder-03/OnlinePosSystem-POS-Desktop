# GlassFish development setup

The Store Server is a Spring Boot web application. The normal build remains an executable
server JAR for the Windows installer. Maven also produces a `-glassfish.war` for deploying
the server to Eclipse GlassFish 7.1.1 (Jakarta EE 10). GlassFish 7.1.1 is tested with Java
25, which matches this repository's Java requirement.

## Build

From the repository root, with JDK 25 and the Maven wrapper's dependencies available:

```powershell
./mvnw.cmd -pl store-server -am -DskipTests package
```

The deployable file is `store-server/target/store-server-0.1.0-SNAPSHOT-glassfish.war`.
The existing `store-server-0.1.0-SNAPSHOT-exec.jar` remains the artifact used by the
Windows package.

## Deploy to a local GlassFish domain

Install Eclipse GlassFish 7.1.1 using its official download, start the `domain1` domain,
then deploy from an administrator PowerShell prompt (or provide the admin password when
`asadmin` requests it):

```powershell
& "$env:GLASSFISH_HOME\bin\asadmin.bat" start-domain domain1
& "$env:GLASSFISH_HOME\bin\asadmin.bat" deploy --force=true --contextroot store-server `
  "store-server/target/store-server-0.1.0-SNAPSHOT-glassfish.war"
```

The local health endpoint is `http://localhost:8080/store-server/api/local/health`.
The default GlassFish HTTP listener is for local development only. Before using an
installed Master profile, configure GlassFish's HTTPS listener and mutual TLS to match
the provisioned server and trust certificates; the Spring Boot embedded-server TLS
properties do not configure GlassFish listeners.

Undeploy with:

```powershell
& "$env:GLASSFISH_HOME\bin\asadmin.bat" undeploy store-server
```
