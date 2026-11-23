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
