# Temporal Card Issuance

A reference implementation of card issuance on Temporal, using Java 25, Spring Boot 4 and React.
Corrections to the original design are recorded in [docs/design-amendments.md](docs/design-amendments.md).

**Status:** Milestone 2 (virtual card, happy path and saga).

## Run on Windows

These scripts work from Command Prompt or PowerShell. Stop any running worker and API first, because Windows locks their jars during a rebuild.

```bat
infra\scripts\build.cmd
```

Then start one service per terminal, in this order:

```bat
infra\scripts\start-temporal.cmd
infra\scripts\start-mocks.cmd
infra\scripts\start-worker.cmd
infra\scripts\start-api.cmd
```

| Service | Address |
|---|---|
| Temporal Web UI | http://localhost:8233 |
| API | http://localhost:8080 |
| Mock services and admin | http://localhost:8091 |

The dev server and mock admin have no authentication. Use test data only and never expose them publicly.

## Run every scenario with one command

After building, run:

```bat
infra\scripts\run-scenarios.cmd
```

It starts its own Temporal, mocks, worker and API on separate ports (17233, 18091 and 18080) with a fresh database, runs every scenario, prints PASS or FAIL for each, and then stops only what it started. Your services on the normal ports are not touched. A full run takes about 2 minutes, and the exit code is 0 only if every scenario passes. Service logs are kept in `target\scenarios`.

| ID | Scenario | Proves |
|---|---|---|
| tc01 | Happy path | Steps run in order, the card is active, no card numbers in history, and search attributes work |
| tc02 | Duplicate request | The same idempotency key returns the existing request, with one card |
| tc03 | Other tenant | Another bank gets `404` |
| tc04 | Card number as input | Refused with `400` before any workflow starts |
| tc05 | Unknown product | Business rejection, no retries |
| tc06 | Limit above policy | Business rejection, no retries |
| tc07 | KYC rejected | Rejected, and no card created |
| tc08 | KYC needs review | Ends under review |
| tc09 | Processor fails twice | Retries with backoff, then succeeds with one card |
| tc10 | Response lost after success | Retry reuses the idempotency key, so still one card |
| tc11 | Activation declined | Deactivate, reset limits, then cancel, in reverse order |
| tc12 | Notification down | Card stays active, nothing compensated |
| tc13, tc14 | Bank A processor offline | Bank B issues normally, while bank A exhausts retries and is cancelled |
| tc15 | Worker crash | API still accepts the request, progress returns `503`, and the card completes after restart |

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

Read its progress:

```bash
curl --location 'http://localhost:8080/v1/card-requests/card-demo-1' \
--header 'X-Tenant-Id: bank-a'
```

In the Web UI, the workflow ID is `card-bank-a-card-demo-1`.

## Inject failures

Set a rule, then issue a card with a new `Idempotency-Key`:

```bash
curl --request PUT 'http://localhost:8091/mock-admin/failures/bank-a/processor/activateCard' \
--header 'Content-Type: application/json' \
--data '{"mode": "BUSINESS_ERROR", "times": -1, "code": "ACTIVATION_DECLINED"}'
```

| Scenario | Endpoint path | Rule body | Expected result |
|---|---|---|---|
| KYC rejected | none, use `"customerRef": "reject-1"` | none | `REJECTED`, no card created |
| Fails twice, then recovers | `bank-a/processor/createCard` | `{"mode":"SERVER_ERROR","times":2}` | `ACTIVE`, one card |
| Response lost after success | `bank-a/processor/createCard` | `{"mode":"DROP_RESPONSE","times":1}` | `ACTIVE`, one card |
| Activation declined | `bank-a/processor/activateCard` | `{"mode":"BUSINESS_ERROR","times":-1,"code":"ACTIVATION_DECLINED"}` | `REJECTED`, compensated in reverse order |
| Notification down | `bank-a/notification/send` | `{"mode":"OFFLINE","times":-1}` | `ACTIVE`, nothing compensated |

`times` is the number of calls affected, and `-1` means every call.

Inspect or clear the mocks:

```bash
curl 'http://localhost:8091/mock-admin/cards/bank-a'
curl 'http://localhost:8091/mock-admin/counters/bank-a'
curl --request POST 'http://localhost:8091/mock-admin/reset'
```
