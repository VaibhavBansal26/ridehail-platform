# Disputes, failed payouts and other provider problems

What the platform does when the payment provider reports a problem with money, and what a person still has to do.

## The principle

Disputes and failures are **recorded, reflected in the ledger where the money really moved, and surfaced to admins**. Nothing is
repaired by guesswork: where the right answer needs a human decision, the system raises an incident and keeps the books honest in
the meantime.

## Chargebacks (customer disputes a charge)

Provider events `charge.dispute.created / .updated / .closed` all carry the dispute's full current state, so the handler works from the
**state**, not the event name, and is safe against duplicates and out-of-order delivery.

| Provider status | Our state | Money |
|---|---|---|
| `warning_needs_response`, `warning_under_review` | `INQUIRY` | none has moved. Recorded, deadline shown. |
| `needs_response`, `under_review` | `OPEN` | **the provider has taken the money back.** Ledger and rider earnings are adjusted (below). |
| `won` | `WON` | money returned; the freeze is lifted. Nothing changes for anyone. |
| `lost` | `LOST` | money gone; the loss is booked (policy below). |
| `warning_closed`, `prevented` | `CLOSED` | inquiry ended; no ledger effect. |
| anything unknown | `INQUIRY` | never guess about money: no ledger effect, logged at ERROR. |

### What the ledger does

For a 9.99 fare (7.99 to the rider, 2.00 commission) disputed in full:

| Step | Entries | Meaning |
|---|---|---|
| Open | Dr `DISPUTES_IN_PROGRESS` 9.99 / Cr `CASH` 9.99 | the provider pulled it from our balance; we may get it back |
| | Dr `RIDER_PAYABLE` 7.99 / Cr `RIDER_FUNDS_HELD` 7.99 | **the rider's share is frozen**: still theirs, not payable |
| Fee | Dr `DISPUTE_FEES` f / Cr `CASH` f | the provider's dispute fee (reversed if the provider refunds it on a win) |
| Won | Dr `CASH` 9.99 / Cr `DISPUTES_IN_PROGRESS` 9.99; Dr `RIDER_FUNDS_HELD` / Cr `RIDER_PAYABLE` | everything restored |
| Lost, platform pays *(default)* | Dr `DISPUTE_LOSSES` 9.99 / Cr `DISPUTES_IN_PROGRESS`; freeze released to the rider | the platform takes the whole loss; the rider keeps their 7.99 |
| Lost, rider pays | Dr `RIDER_FUNDS_HELD` 7.99 / Cr `DISPUTES_IN_PROGRESS`; Dr `DISPUTE_LOSSES` 2.00 / Cr `DISPUTES_IN_PROGRESS` | the rider's share is forfeited; the platform absorbs only its commission |

Details that matter:

- **Partial disputes:** the rider's frozen share is pro rata (a 5.00 dispute on a 9.99 fare freezes 4.00 of the rider's 7.99).
- **Rider already withdrew the money:** freezing takes their payable balance **negative** (a debt) with an equal amount held. A win
  cancels it out. A loss under the *rider pays* policy leaves the debt, which is netted against their future earnings
  (payouts only ever come from a positive balance), so the platform recovers it without chasing anyone.
- **Payment already refunded:** the rider's earnings were reversed by the refund, so nothing of theirs is frozen; the second
  withdrawal by the provider is still booked, and a loss is the platform's.
- **Which policy:** `ridehail.disputes.loss-policy` = `platform` (default) or `rider`. The default is the platform's because
  the rider provided the service. Choose `rider` only if your rider terms allow clawing back.
- **Serialization:** freezing locks the rider's ledger account first, so a withdrawal being requested at that moment sees the
  frozen balance, never a stale one.

### Side effects of an open chargeback

- The payment becomes `DISPUTED` (`CHARGED_BACK` if lost, back to `CAPTURED` if won). **A disputed payment cannot be refunded**,
  which would lose the money twice.
- The disputing customer **cannot book new rides while a chargeback is `OPEN`**. Inquiries do not pause anyone.
- The rider sees the frozen amount as `onHold` in `GET /api/riders/me/earnings` (no customer detail), and gets a `FUNDS_ON_HOLD`
  push, then `HOLD_ENDED` when it is decided. The app refetches earnings on either.

### Disputes we cannot account for

A dispute for a charge we don't recognize, or for a payment that never settled, is **recorded with `matched: false` and a note,
but no ledger entries are made** (there is nothing we can safely move). It still shows as open with its deadline, and in the
attention summary. It needs a person.

## Failed payouts and other provider problems ("incidents")

| Incident | Provider event | What we do automatically | Who acts |
|---|---|---|---|
| `CONNECT_PAYOUT_FAILED` | `payout.failed` on a rider's connected account | record it; **pause that rider's withdrawals**; tell the rider (`BANK_PAYOUT_FAILED` push, `payoutBlockedReason` in earnings) | the **rider** fixes their bank details; it resolves itself when the provider reports the bank account changed (`account.external_account.*`). An admin can also resolve it. |
| `PLATFORM_PAYOUT_FAILED` | `payout.failed`, no connected account | record it. No rider is paused. | an **admin**: the platform's own bank rejected a payout. |
| `TRANSFER_REVERSED` | `transfer.reversed` | record it, linked to our payout and rider | an **admin**: our books say the rider was paid but the money came back. Decide whether to re-pay. We do not touch the ledger. |
| `REFUND_FAILED` | `refund.failed` | record it, linked to our payment | an **admin**: our books say the customer was refunded but the money did not leave. |

Why a failed *bank* payout does not change the ledger: our transfer to the rider's connected account succeeded, so the money left
the platform and is still the rider's, sitting in their Stripe balance. Only paying their bank failed.

Incidents are idempotent per provider object (a redelivered event cannot raise one twice) and stay `OPEN` until resolved.

## For admins

| | |
|---|---|
| `GET /api/admin/finance/attention` | one glance: open chargebacks (count, amount, overdue, earliest deadline), inquiries, open incidents by type, payments/payouts needing attention, payouts awaiting funds, rider money on hold. `needsAction` is the alert flag. |
| `GET /api/admin/disputes?status=` / `{id}` | disputes with `evidenceDueBy`, `evidenceOverdue`, rider amount held, fee booked, `matched`/`ledgerNote`, and `providerUrl` (the Stripe Dashboard page to respond on) |
| `GET /api/admin/disputes/{id}/evidence` | the facts we will send (customer name, service description and date, activity log, compiled from the trip), the saved explanation, whether it can be submitted and if not why, and what was sent once submitted |
| `POST /api/admin/disputes/{id}/evidence` `{"explanation": "..."}` | save the draft explanation (max 5000 characters). Audited. |
| `POST /api/admin/disputes/{id}/evidence/submit` | send the facts and explanation to Stripe, once. Refused after the deadline, for a decided dispute, without an explanation, or if already submitted. Audited. |
| `GET /api/admin/incidents?status=&type=` | incidents |
| `POST /api/admin/incidents/{id}/resolve` `{"note": "..."}` | record that a person dealt with it. Moves no money. |

### Runbook: a chargeback arrives

1. `needsAction` turns true; the dispute shows `OPEN` with `evidenceDueBy`.
2. In the admin panel open *Disputes* > **Answer**: check the facts compiled from the trip, write the explanation, save, then
   **Submit** **before the deadline** (otherwise it is lost by default). Stripe accepts one response, so submitting is final.
   Receipts, screenshots and other files are not attached from here: for those, use `providerUrl` (the Stripe Dashboard) instead.
3. Nothing else to do: when Stripe decides, the ledger settles itself and the rider's hold ends.
4. If you decide not to contest, there is no need to do anything either: it will end `LOST` and be booked per the loss policy.

## Not covered (deliberately)

- **Attaching files** (receipts, screenshots, messages) as evidence. Text evidence is submitted from the panel; files go through the Stripe Dashboard. The text path has been exercised against the simulated gateway only, not a live Stripe account.
- **Repairing the books after a reversed transfer or failed refund**: needs a business decision, so it is surfaced instead.
- **Fee refunds that arrive without a dispute event** are only picked up the next time a dispute event arrives for that dispute.
- **Multiple disputes on one payment** (the provider allows one per charge) and disputes in a currency other than the platform's.
