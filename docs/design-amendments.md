# Design amendments

Changes to *Temporal Card Issuance: Reference Implementation (Java + React)*, dated Nov 24, 2026.
Where this file and the reference document disagree, this file wins.

## Correctness

| ID | Section | Problem | Amended rule | Applies from |
|---|---|---|---|---|
| A1 | 7 | The reused virtual flow activates an agentic card before its mandate exists, so it is briefly usable with no spend rules. | The virtual flow takes an `activationHold` option. Agentic issuance creates the card inactive, publishes the mandate, then activates. | M5 |
| A2 | 7, 9 | The point-of-no-return marker is set after bureau submission succeeds. A timed-out call may have placed the order with no marker. | Set the marker **before** calling `submitToBureau`. Cancellation is refused from then on. | M4 |
| A3 | 7, 9 | Compensation registered after success misses a step whose call timed out but took effect. | Register each compensation **before** its activity call. Compensations already tolerate a step that never completed. | M2 |
| A4 | 12 | Workflow ID reuse protection ends when retention deletes the closed run, so an old key could issue a second card. | Store idempotency keys and request hashes in a durable API-owned table that outlives retention. It holds no card status. | M7 |
| A5 | 8, 12 | A callback can arrive before the workflow records the card ID, and visibility is eventually consistent. | Callbacks carry the business request ID, and the API derives the workflow ID from tenant and request ID. Never route through a visibility lookup. | M3 |
| A6 | 5, 7 | No state describes a delivered card awaiting activation. | Add `DELIVERED`. The path becomes Shipped, Delivered, then Active, or Blocked if the activation window expires. | M4 |
| A7 | 7, 8 | Cancellation is listed as a signal, but the caller must be told when it is refused. | Cancellation is an **update** with a validator. | M3 |

## Structure

| ID | Section | Problem | Amended rule | Applies from |
|---|---|---|---|---|
| A8 | 4, 6 | Activity interfaces share a module with implementations, so workflows would inherit port dependencies. | New `activities-api` module holds interfaces and commands, and depends only on `contracts`. | M1 |
| A9 | 4, 7 | `engine-core` cannot call activities, so it cannot hold concrete steps. | `engine-core` holds the generic step model. Concrete steps live in `workflows`. | M2 |
| A10 | 17 | M3 needs a webhook endpoint and M2 needs search attributes, but both were scheduled for M7. | `api-app` exists from M1 and gains webhook endpoints in M3. Search attributes are registered in M2. M7 completes the API and adds the UI. | M1 |

## Tests and demonstrations

| ID | Section | Problem | Amended rule | Applies from |
|---|---|---|---|---|
| A11 | 6, 15 | A plain 13 to 19 digit match flags millisecond timestamps. | The data-leak scanner also requires a valid Luhn check digit, and scans payloads before encryption. | M1 |
| A12 | 11, 14 | Activities waiting in retry backoff hold no worker slot, so failing calls alone may not raise queue latency. | The noisy-tenant demo uses slow failing calls and enough load to fill tenant A's worker slots. | M8 |

## Temporary behaviour until a later milestone

| Behaviour now | Final behaviour | Replaced in |
|---|---|---|
| Products and the spend limit cap are fixed in code. Only the confirmation timeout comes from tenant settings. | All tenant configuration is passed as workflow input. | M6 |
| Retry attempt counts appear only in the Temporal Web UI. | The progress view also shows them. | M7 |
| Platform records for reconciliation come from Temporal visibility (each workflow's `CardId` and `CardStatus`). | They come from the card service, which owns card status. | When the card service exists |
| Reconciliation compares every card, not only one day's records. | The processor returns daily records for the business date. | When the processor supports it |
| `OFFLINE` mock mode returns 503 rather than refusing connections. | Unchanged. Both are classed as technical failures. | - |

Milestone 3 replaced the three Milestone 2 temporary behaviours: retries running out now parks for an operator, KYC "needs review" waits for a decision, and the processor confirmation wait is a signal raced against a timer.

## Milestone 3 scope

The original section 17 order is kept, except that two items move earlier so Milestone 3 covers every Temporal pattern without the physical card:

- **Reconciliation, schedule and heartbeats** (from M8). The heartbeating activity shows resume-after-crash, which the design otherwise demonstrates with physical card personalization in M4.
- **Reissue for virtual cards** (from M4). Physical reissue follows with the physical card.

## Implementation notes

- With `continueWithError`, the Java SDK `Saga` runs every compensation but does not report a failed one. `StepRunner` tracks compensation failures itself and ends the request `UNDER_REVIEW` with `COMPENSATION_FAILED`.
- An accepted cancel always takes effect, even if it raced a step. The workflow checks for it after each step, including after activation, and only then marks the point of no return.
- The SDK sends at most one heartbeat per 80% of the heartbeat timeout (4 of 5 seconds). A resumed reconciliation can therefore re-read up to about 4 seconds of pages, which is safe because reads have no side effects.
- Webhook routing uses the request ID carried in the callback (A5), so a callback that arrives before the workflow records the card ID is kept and applied when the wait starts.
- Operator decisions and cancellation accept an optional `Idempotency-Key`, which becomes the Temporal update ID. An update that times out with `503` may still be applied once a worker is back, so a retry with the same key returns the original result instead of `409 NO_REVIEW_PENDING`.
- Reconciliation heartbeats before and after loading platform records, as well as after each page. The first heartbeat uses the SDK's immediate send, so page progress reaches Temporal up to 4 seconds later.
- Known limitation: reissuing the same card twice with different keys issues two replacements. Preventing it needs the card service's view of the old card.

## Platform versions

Section 2 specified Java 21 and Spring Boot 3.x. The project now uses the latest stable release of each tool and direct dependency (decided Nov 24, 2026).

| Item | Design | Now |
|---|---|---|
| Java | 21 | 25 (Temurin 25.0.5.1, LTS) |
| Spring Boot | 3.x | 4.2.0 |
| Maven | not specified | 3.10.2 (Maven 4 has no final release yet) |
| Temporal SDK and starter | not specified | 1.41.1 |
| Temporal CLI | not specified | 1.10.1 |

Libraries that Spring Boot manages, such as JUnit, Logback and Jackson, follow the Boot 4.2.0 BOM rather than their own newest releases, so they stay a combination Spring has tested together.

The Temporal Spring Boot starter is built against Spring Boot 2.7 and does not state Spring Boot 4 support. Each upgrade of either must pass the live scenario suite, not only the unit tests, because the unit tests do not load Spring.
