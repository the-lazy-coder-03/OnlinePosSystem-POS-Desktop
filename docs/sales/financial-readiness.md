# Financial and operational activation gates

A saved draft is not a completed sale. The sales renderer exposes only catalogue quotation and draft operations that the backend implements. Changing a document, visibility, workflow or template cannot enable a missing capability.

| Operation | Current gate | Required authoritative service before activation |
|---|---|---|
| Cash checkout | Hidden | Atomic accepted-sale command, exact-money/tax ledger, tender/change allocation, mandatory audit and durable receipt |
| Card checkout | Hidden | Accepted-sale state machine plus real provider/terminal authorization, capture, query and idempotent reversal; uncertain results require status lookup |
| Refund | Hidden | Original accepted-sale/line references, cumulative refundable quantities/amounts, qualified approvals, actual tender reversal and immutable refund ledger |
| Discount | Hidden | Server eligibility and limits, line/subtotal allocation excluding exempt items, currency rounding, scoped manager approval and reason |
| Void/cancel accepted sale | Hidden | Authoritative transaction states, required void reason, cancellation confirmation, actor/approver audit and reversal rules |
| Hold/resume order list | Hidden | Durable held-order lifecycle and listing, expected revisions, cross-terminal ownership/conflict policy |
| Receipt/kitchen printing | Hidden | Real device/routing configuration, persistent jobs, delivery/retry state and actual acknowledgements; never infer a successful print from a queued job |
| Emergency sales/reconciliation | Hidden | Local transaction authority, encrypted/protected immutable journal, idempotent Master acceptance, conflict/quarantine/review, approved offline tender semantics and recovery tests |

## Separate implementation contract

Introduce a new domain migration for accepted sales; do not reinterpret `order_draft`, payment metadata or reporting SQL. A finalize command must carry a command UUID, store/terminal/actor identity, expected draft/catalogue revisions, stable line IDs, the authoritative quotation and tender intents. Serialize acceptance, ledger postings, approvals, audit, replication event and durable receipt in one transaction. Return an accepted-sale identity only after commit.

External card calls require an explicit pending/confirmed/failed/uncertain state machine. Persist intent before sending; query the provider on uncertain results with the original idempotency key. Never retry by inventing another payment identity. Partial tender success, reversal, duplicate delivery and crash recovery are acceptance tests, not UI success animations.

Refunds reference original immutable accepted lines and tender transactions. Enforce remaining refundable quantity/value under a lock and record actor, scoped approver and reason. Discounts use eligible subtotal only and deterministic minor-unit allocation; exempt lines receive zero allocation.

Emergency journals require event UUID, store and terminal, local sequence, parent order and line IDs, actor/approver, frozen pricing/configuration revisions, amount/tender, reason, timestamps and provider evidence. Persist before acknowledgement. Master replay deduplicates effects and produces explicit accepted/conflict/quarantined states without overwriting authoritative transactions. Card payment remains unavailable offline unless a real provider supports and confirms the required semantics.

Only add these actions to `SalesScreens.draftCapabilities` (or its future transaction replacement) after backend, permission, financial, crash/replay, audit and native-device checks pass. Layout distribution remains a separate stream from transaction replication.
