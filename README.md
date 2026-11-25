# Temporal Card Issuance

A reference implementation of card issuance on Temporal, using Java 25, Spring Boot 4 and the Temporal Java SDK.

**Status:** Milestone 3. Virtual cards are issued end to end, with callbacks, manual review, cancellation, reissue, and nightly reconciliation with heartbeats and schedules. Physical and agentic cards, the React UI and production hardening are still to come.

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Features, where data is stored, code flow, every file explained, edge cases, tests, and future work |
| [docs/frontend-context.md](docs/frontend-context.md) | What a frontend needs: endpoints, request and response shapes, status values, polling, and API gaps |
| [docs/design-amendments.md](docs/design-amendments.md) | Corrections to the original design, and platform versions |

## Features

- **Virtual card issuance:** validate, KYC, create card, wait for the processor's confirmation, set limits, activate, notify. Steps undo in reverse order if a later step fails.
- **Safe retries:** every external call carries an idempotency key, so a retry never creates a second card. Failures are classed as business (not retried), technical or ambiguous (retried).
- **Manual review:** requests park for an operator when KYC needs review, the confirmation is late, or a step runs out of retries. Operators approve, reject, retry or cancel.
- **Cancellation:** allowed until the card is active, refused after.
- **Reissue:** lost, stolen, damaged or expired cards. Lost and stolen cards are blocked first, then a replacement is issued as a child workflow and linked to the old card.
- **Reconciliation:** compares the processor's cards with the platform's, on demand or nightly per tenant. It heartbeats its progress, so a crashed worker's replacement carries on where it stopped.
- **Multi-tenant:** two tenants (`bank-a`, `bank-b`), isolated by workflow ID and search attributes.
- **No card numbers in history:** the API refuses anything that looks like one, and tests scan workflow histories to prove it.
- **Mocks:** KYC, card processor and notification services with failure injection, call counters and real callbacks.

The full list, with where each feature lives in the code, is in [architecture section 2](docs/architecture.md#2-features-in-the-current-code).

## Setup

### Requirements

| Tool | Version | Needed for |
|---|---|---|
| Windows | 10 or 11, with PowerShell 5.1 or later | The scripts in `infra\scripts` |
| JDK | 25 (Eclipse Temurin) | Building and running |
| Maven | 3.10.2 | Building |
| Temporal CLI | 1.10.1 or later | The local Temporal server and the `temporal` commands |
| Postman | Desktop app | Running the API collection (optional) |
| Node.js | 18 or later | Only to regenerate the Postman collection (optional) |

The first build needs internet access to download Maven dependencies.

### Install the toolchain into `.tools`

The scripts look for the JDK, Maven and Temporal CLI in a `.tools` folder at the repo root, which git ignores. Nothing is installed system-wide. From the repo root, in PowerShell:

```powershell
New-Item -ItemType Directory -Force .tools\jdk, .tools\maven, .tools\temporal | Out-Null

# JDK 25
Invoke-WebRequest "https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile "$env:TEMP\jdk25.zip"
Expand-Archive "$env:TEMP\jdk25.zip" .tools\jdk

# Maven 3.10.2
Invoke-WebRequest "https://archive.apache.org/dist/maven/maven-3/3.10.2/binaries/apache-maven-3.10.2-bin.zip" -OutFile "$env:TEMP\maven.zip"
Expand-Archive "$env:TEMP\maven.zip" .tools\maven

# Temporal CLI
Invoke-WebRequest "https://temporal.download/cli/archive/latest?platform=windows&arch=amd64" -OutFile "$env:TEMP\temporal.zip"
Expand-Archive "$env:TEMP\temporal.zip" .tools\temporal
```

You can also download them in a browser and unzip them by hand. The result must look like this:

```
.tools\
  jdk\jdk-25.0.5.1+2\bin\java.exe        (any JDK 25 folder name works)
  maven\apache-maven-3.10.2\bin\mvn.cmd
  temporal\temporal.exe
```

Check it:

```bat
.tools\temporal\temporal.exe --version
```

To use `java`, `mvn` and `temporal` directly in a PowerShell window, run `. .\infra\scripts\env.ps1` from the repo root. The scripts below don't need this.

### Build

```bat
infra\scripts\build.cmd
```

This compiles every module and runs the 69 unit tests. It takes 1 to 2 minutes, and ends with `BUILD SUCCESS`.

## Run

Run these from the repo root, in Command Prompt or PowerShell.

| Command | What it does |
|---|---|
| `infra\scripts\restart-all.cmd` | Stops everything, rebuilds with tests, and starts everything again. Use this after any code change, or to start from scratch. |
| `infra\scripts\start-all.cmd` | Starts Temporal, waits for it, then starts mocks, worker and API, each in its own window |
| `infra\scripts\stop-all.cmd` | Stops all four services and closes their windows. Add `-DryRun` to see what it would stop. |
| `infra\scripts\build.cmd` | Builds and runs the unit tests. Stop the services first, because Windows locks their jars. |

`start-all` finishes with:

```
All services are up. Each runs in its own window.
  Temporal Web UI   http://localhost:8233
  API               http://localhost:8080
  Mocks and admin   http://localhost:8091
```

| Service | Address |
|---|---|
| Temporal server | `localhost:7233` |
| Temporal Web UI | http://localhost:8233 |
| API | http://localhost:8080 |
| Mock services and admin | http://localhost:8091 |
| Worker | No port. Check its window for errors. |

To start services one per terminal instead, run these in order. `stop-all` also stops services started this way.

```bat
infra\scripts\start-temporal.cmd
infra\scripts\start-mocks.cmd
infra\scripts\start-worker.cmd
infra\scripts\start-api.cmd
```

### Check it works

Issue one card, then read it back after a second or two:

```bat
curl -X POST http://localhost:8080/v1/card-requests -H "Content-Type: application/json" -H "X-Tenant-Id: bank-a" -H "Idempotency-Key: check-1" -d "{\"cardType\":\"VIRTUAL\",\"productCode\":\"VIRTUAL_STANDARD\",\"customerRef\":\"customer-1\",\"accountRef\":\"account-1\",\"spendLimitMinor\":50000}"

curl http://localhost:8080/v1/card-requests/check-1 -H "X-Tenant-Id: bank-a"
```

The second call should show `"status":"ACTIVE"` and `"finished":true`. Each `Idempotency-Key` creates one request, so use a new key (`check-2`, …) to issue another card.

### Where the data goes

Temporal keeps every workflow in the SQLite file `.tools\temporal-dev.db`, which survives restarts. Closed workflows are deleted after 24 hours. The mock processor keeps its cards in memory, so they're lost when the mocks restart. To start with no data, run `stop-all`, delete `.tools\temporal-dev.db`, and run `start-all`. Details are in [architecture section 4](docs/architecture.md#4-where-data-is-stored).

### Troubleshooting

| Symptom | Fix |
|---|---|
| `start-all` says some services are already running | Run `restart-all`, or `stop-all` first |
| `start-all` says a jar hasn't been built | Run `build.cmd` |
| `build.cmd` fails with "file in use" | A service is still running. Run `stop-all` first. |
| A request stays at `REQUESTED` | The worker isn't running or failed to start. Check its window. |
| Progress returns `503 PROGRESS_UNAVAILABLE` | No worker is answering. Start the worker, and the request carries on. |
| `'temporal' is not recognized` | Use `.tools\temporal\temporal.exe`, or run `. .\infra\scripts\env.ps1` first |

## Check workflows in the Temporal portal

### Web UI

Open http://localhost:8233. The namespace is `default`.

| To see | Do this |
|---|---|
| All workflows | **Workflows** page |
| One card request | Search for its workflow ID, such as `card-bank-a-check-1`, and open it |
| Its steps, inputs and results | The **History** tab. Expand an event to see its input or result. |
| The current progress | **Queries** tab, choose `progress` |
| Search attributes | The workflow's summary at the top |
| Parked requests | Search `CardStatus = "UNDER_REVIEW"` |
| One tenant's requests | Search `TenantId = "bank-a"` |
| Schedules | **Schedules** page: `recon-bank-a` and `recon-bank-b` |
| Whether the worker is connected | Open a workflow, then its task queue `issuance-dev`. The worker appears as a poller. |

Workflow IDs are `card-{tenant}-{key}` for card requests, `reissue-{tenant}-{key}` for reissues (the replacement card is `card-{tenant}-{key}-new`), and `reconcile-{tenant}-{key}` for reconciliation runs.

### Temporal CLI

Run from the repo root. In Command Prompt, set a short name first:

```bat
set T=.tools\temporal\temporal.exe
```

In PowerShell, use `$T = ".tools\temporal\temporal.exe"` and write `& $T` instead of `%T%`.

```bat
rem Is the worker connected? It should be listed as a poller.
%T% task-queue describe --task-queue issuance-dev

rem List workflows, newest first
%T% workflow list
%T% workflow list --query "TenantId = 'bank-a'"
%T% workflow list --query "WorkflowType = 'VirtualCardWorkflow' AND ExecutionStatus = 'Running'"
%T% workflow list --query "CardStatus = 'UNDER_REVIEW'"
%T% workflow count --query "TenantId = 'bank-a' AND CardStatus = 'ACTIVE'"

rem One workflow: status, search attributes, pending activities
%T% workflow describe --workflow-id card-bank-a-check-1

rem Its full event history, readable or as JSON
%T% workflow show --workflow-id card-bank-a-check-1
%T% workflow show --workflow-id card-bank-a-check-1 --output json > history.json

rem Its current progress, from the workflow's query
%T% workflow query --workflow-id card-bank-a-check-1 --type progress

rem Reconciliation schedules
%T% schedule list
%T% schedule describe --schedule-id recon-bank-a

rem Server settings: registered search attributes, retention
%T% operator search-attribute list
%T% operator namespace describe default
```

## Test with Postman

The collection runs every Milestone 1, 2 and 3 scenario against the running services. Every request has pass or fail checks. Requests that wait for a workflow repeat once a second until it is ready.

1. Start the services with `infra\scripts\start-all.cmd` (or `restart-all.cmd`).
2. In Postman, choose **Import** and select [postman/card-issuance.postman_collection.json](postman/card-issuance.postman_collection.json).
3. Open the collection's **Variables** tab and check the values:

   | Variable | Default |
   |---|---|
   | `baseUrl` | `http://localhost:8080` |
   | `mockUrl` | `http://localhost:8091` |
   | `tenant` | `bank-a` |
   | `otherTenant` | `bank-b` |
   | `run`, `tries` | Set by the collection. Leave them alone. |

4. Right-click the collection and choose **Run collection**.
5. In the Runner, **deselect folder `9 Manual`**. Leave the other folders selected and in order, keep the delay at 0, and leave iterations at 1.
6. Choose **Run Temporal Card Issuance**. A full run takes about 2 minutes.
7. Every test should pass. Click a failed request to see its response and which check failed.

| Folder | Requests | Contents |
|---|---|---|
| 0 Setup | 1 | Resets the mocks and starts a new run ID, so the collection can be run again without key clashes |
| 1 Milestone 1 | 1 | Sample workflow |
| 2 Milestone 2 | 33 | Issuance, duplicates, other tenant, rejections, retries, lost response, compensation, notification failure |
| 3 Milestone 3 | 60 | Late and missing callbacks, operator decisions, retries running out, cancellation, reissue, reconciliation, schedule |
| 9 Manual | 13 | Worker crash and heartbeat resume |

**Sending requests one at a time:** send `0 Setup` first. Request keys include the run ID it creates, and without it the keys all start with `-`.

**Folder 9 Manual** needs you to stop and restart the worker, so send its requests one at a time with **Send**. Each request's description says what to do.

You can watch each request's workflow in the Web UI while the collection runs. They appear as `card-bank-a-pm...`.

To change the collection, edit [postman/generate-collection.mjs](postman/generate-collection.mjs) and run `node postman/generate-collection.mjs`.

## Run every scenario with one command

After building, run:

```bat
infra\scripts\run-scenarios.cmd
```

It starts its own Temporal, mocks, worker and API on separate ports (17233, 18091 and 18080) with a fresh database, runs every scenario, prints PASS or FAIL for each, and then stops only what it started. Your services on the normal ports are not touched. A full run takes about 5 minutes, and the exit code is 0 only if every scenario passes. Service logs are kept in `target\scenarios`.

| ID | Scenario | Proves |
|---|---|---|
| tc01 | Happy path | Steps in order, callback signal received, card active, no card numbers in history, search attributes work |
| tc02 | Duplicate request | The same idempotency key returns the existing request, with one card |
| tc03 | Other tenant | Another bank gets `404` |
| tc04 | Card number as input | Refused with `400` before any workflow starts |
| tc05, tc06 | Unknown product, limit too high | Business rejection, no retries |
| tc07 | KYC rejected | Rejected, and no card created |
| tc08 | KYC needs review | Parks for review, and the reviewer's rejection ends it |
| tc09 | Processor fails twice | Retries with backoff, then succeeds with one card |
| tc10 | Response lost after success | Retry reuses the idempotency key, so still one card |
| tc11 | Activation declined | Deactivate, reset limits, then cancel, in reverse order |
| tc12 | Notification down | Card stays active, nothing compensated |
| tc13, tc14 | Retries run out | Bank A parks for an operator while bank B issues normally, then the operator's retry completes it |
| tc16 | Operator cancels a parked request | Compensation runs and the request ends cancelled |
| tc15 | Worker crash | API still accepts the request, progress returns `503`, and the card completes after restart |
| tc17 | Late callback | The timer parks the request, and the late signal clears it with no operator |
| tc18 | Callback never arrives | Parks, refuses an action that doesn't fit the review, then the operator approves |
| tc19 | Duplicate callback | Two signals arrive, one is used |
| tc20 | Decision on a finished request | Refused with `409` |
| tc21 | Cancel while waiting for the callback | Accepted, and the created card is cancelled |
| tc22 | Cancel after activation | Refused with `409 CANNOT_CANCEL` |
| tc23 | Reissue a stolen card | Old card blocked first, replacement issued as a child workflow, old and new linked |
| tc24 | Reissue an unknown card | Rejected with `CARD_NOT_FOUND`, nothing issued |
| tc25 | Reconciliation | Finds a processor card the platform never issued |
| tc26 | Worker dies mid-reconciliation | Heartbeat progress lets the retry resume instead of starting over |
| tc27 | Nightly schedule overlap | A second trigger is skipped while a run is still going |
| tc28 | Card number in a cancel reason | Refused with `400` before it can reach history |
| tc29 | Reused reconciliation key | Returns the finished run instead of starting another |
| tc30 | Deleted schedule | Recreated when triggered |
| tc31 | Callback while the API is down | The mock retries delivery, and the card still completes |

## Issue a virtual card

```bash
curl --location 'http://localhost:8080/v1/card-requests' \
--header 'Content-Type: application/json' \
--header 'X-Tenant-Id: bank-a' \
--header 'Idempotency-Key: card-demo-1' \
--data '{
  "cardType": "VIRTUAL",
  "productCode": "VIRTUAL_STANDARD",
  "customerRef": "customer-1",
  "accountRef": "account-1",
  "spendLimitMinor": 50000
}'
```

Products are `VIRTUAL_STANDARD` and `VIRTUAL_PREMIUM`, and `spendLimitMinor` must be between 1 and 1,000,000. References may contain letters, digits, `_` and `-`, up to 64 characters.

Read its progress. `pendingReview` shows when it is waiting for an operator, and `finished` shows when it is done:

```bash
curl --location 'http://localhost:8080/v1/card-requests/card-demo-1' \
--header 'X-Tenant-Id: bank-a'
```

In the Web UI, the workflow ID is `card-bank-a-card-demo-1`.

## Operator actions

| Action | Request | Body |
|---|---|---|
| Decide a parked request | `POST /v1/card-requests/{id}/approvals` (optional `Idempotency-Key`) | `{"action":"APPROVE","reviewer":"ops-1"}` |
| Cancel before activation | `POST /v1/card-requests/{id}/cancel` (optional `Idempotency-Key`) | `{"reason":"customer-request"}` |
| Reissue a card | `POST /v1/cards/{cardId}/reissue` with an `Idempotency-Key` | `{"reason":"STOLEN","productCode":"VIRTUAL_STANDARD","customerRef":"customer-1","accountRef":"account-1","spendLimitMinor":50000}` |
| Reissue progress | `GET /v1/reissues/{id}` | none |
| Run reconciliation now | `POST /v1/reconciliations` with an `Idempotency-Key` | `{"pageSize":50}` |
| Reconciliation result | `GET /v1/reconciliations/{id}` | none |
| Trigger the nightly schedule | `POST /v1/reconciliations/schedule/trigger` | none |

Every request needs the `X-Tenant-Id` header. For approvals and cancel, an `Idempotency-Key` makes a retry safe: if the first attempt timed out but was applied, the retry returns the same result instead of `409`. The allowed review actions depend on why the request is parked:

| `pendingReview.kind` | Allowed actions |
|---|---|
| `KYC_REVIEW` | `APPROVE`, `REJECT` |
| `CONFIRMATION_TIMEOUT` | `APPROVE`, `REJECT` (a late callback also clears it) |
| `RETRIES_EXHAUSTED` | `RETRY`, `CANCEL` |

Reissue reasons are `LOST`, `STOLEN`, `DAMAGED` and `EXPIRED`. Lost and stolen cards are blocked before the replacement is issued.

Every endpoint and each reply it can give is listed in [architecture section 9.10](docs/architecture.md#910-api-app-the-rest-api).

## Inject failures

Set a failure rule, then issue a card with a new `Idempotency-Key`:

```bash
curl --request PUT 'http://localhost:8091/mock-admin/failures/bank-a/processor/activateCard' \
--header 'Content-Type: application/json' \
--data '{"mode": "BUSINESS_ERROR", "times": -1, "code": "ACTIVATION_DECLINED"}'
```

| Scenario | Endpoint path | Rule body | Expected result |
|---|---|---|---|
| KYC rejected | none, use `"customerRef": "reject-1"` | none | `REJECTED`, no card created |
| KYC needs review | none, use `"customerRef": "review-1"` | none | Parked with `KYC_REVIEW` |
| Fails twice, then recovers | `bank-a/processor/createCard` | `{"mode":"SERVER_ERROR","times":2}` | `ACTIVE`, one card |
| Response lost after success | `bank-a/processor/createCard` | `{"mode":"DROP_RESPONSE","times":1}` | `ACTIVE`, one card |
| Activation declined | `bank-a/processor/activateCard` | `{"mode":"BUSINESS_ERROR","times":-1,"code":"ACTIVATION_DECLINED"}` | `REJECTED`, compensated in reverse order |
| Notification down | `bank-a/notification/send` | `{"mode":"OFFLINE","times":-1}` | `ACTIVE`, nothing compensated |
| Processor offline | `bank-a/processor/createCard` | `{"mode":"OFFLINE","times":-1}` | Parked with `RETRIES_EXHAUSTED` after about 30 seconds |
| Slow processor | `bank-a/processor/createCard` | `{"mode":"LATENCY","times":1,"latencyMs":2000}` | `ACTIVE`, just slower |

`times` is the number of calls affected, and `-1` means every call. Other operation names are `check` (KYC), `setLimits`, `resetLimits`, `deactivateCard`, `cancelCard`, `blockCard`, `linkReplacement` and `listCards`.

Control how the processor sends confirmation callbacks with `PUT http://localhost:8091/mock-admin/callbacks/bank-a`:

| Body | Effect |
|---|---|
| `{"mode":"NORMAL"}` | Sent after 200 milliseconds (default) |
| `{"mode":"DELAY","delayMs":15000}` | Sent late, after the 10-second confirmation timer |
| `{"mode":"DUPLICATE"}` | Sent twice with the same event ID |
| `{"mode":"NEVER"}` | Never sent |

Other mock admin calls:

```bash
curl 'http://localhost:8091/mock-admin/cards/bank-a'
curl 'http://localhost:8091/mock-admin/counters/bank-a'
curl --request POST 'http://localhost:8091/mock-admin/cards/bank-a/orphan'
curl --request POST 'http://localhost:8091/mock-admin/reset'
```

## Security note

The dev server and mock admin have no authentication, and the webhook secret is a development value. Use test data only and never expose these services publicly.

## What's next

Physical cards (Milestone 4), agentic cards (5), tenant configuration (6), the complete API and React UI (7), and multi-tenant isolation (8), then production hardening. See [architecture section 13](docs/architecture.md#13-future-implementation).
