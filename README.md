# Temporal Card Issuance

A reference implementation of card issuance on Temporal, using Java 21, Spring Boot and React.
Corrections to the original design are recorded in [docs/design-amendments.md](docs/design-amendments.md).

**Status:** Milestone 1 (foundation).

## Run Milestone 1 on Windows

Load the local toolchain in each terminal:

```powershell
. .\infra\scripts\env.ps1
```

Build and run all tests:

```powershell
mvn verify
```

Then use three terminals:

```powershell
# 1. Temporal dev server. Web UI at http://localhost:8233
temporal server start-dev --db-filename .tools\temporal-dev.db

# 2. Worker
java -jar worker-app\target\worker-app-0.1.0-SNAPSHOT-exec.jar

# 3. API
java -jar api-app\target\api-app-0.1.0-SNAPSHOT-exec.jar
```

Start the sample workflow:

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/v1/hello -ContentType application/json -Body '{"requestId":"demo-1","name":"Ada"}'
```

In the Web UI, open `hello-demo-1` and check for workflow started, activity and completed events.

The dev server has no authentication or TLS. Use test data only and never expose it publicly.
