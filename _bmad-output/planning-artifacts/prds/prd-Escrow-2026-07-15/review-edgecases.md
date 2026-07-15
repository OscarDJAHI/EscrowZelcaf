# Edge-Case Review — Dépôt de preuves (Evidence Upload)

> Method: EDGE-CASE HUNTER. Walked every state, transition, boundary and branch of the feature PRD (`prd.md`) and its addendum (`addendum.md`) against the platform state machine (`Docs/backend_schema_escrow.md`).
> Scope: only **genuine unhandled edge cases** — not style. Severities: CRITICAL / HIGH / MEDIUM / LOW.
> Context: LEARNING POC, B2B ZLECAf escrow. Some findings are intentionally called out even where a POC might accept the risk, so the acceptance is explicit rather than silent.

---

## State-machine reference (from `backend_schema_escrow.md` §2)

| From | Event | To | Role |
|---|---|---|---|
| `NONE` | CREATE | `INITIATED` | Buyer/Seller |
| `INITIATED` | PAY_FUNDS | `FUNDS_LOCKED` | System |
| `FUNDS_LOCKED` | SHIP_GOODS | `SHIPPED` | Seller |
| **`FUNDS_LOCKED`** | **OPEN_DISPUTE** | **`DISPUTED`** | **Buyer OR Seller** |
| `SHIPPED` | DELIVERY_CONFIRMED | `RELEASED` | Buyer/System |
| `SHIPPED` | OPEN_DISPUTE | `DISPUTED` | Buyer only |
| `DISPUTED` | RESOLVE_RELEASE | `RELEASED` | Admin |
| `DISPUTED` | RESOLVE_REFUND | `REFUNDED` | Admin |

Two facts the PRD does not reconcile: (a) a dispute can be opened from **`FUNDS_LOCKED`** (before shipping) by **either party**; (b) a transaction can reach the terminal state **`RELEASED` directly from `SHIPPED`** via `DELIVERY_CONFIRMED`, never passing through `DISPUTED`.

---

## CRITICAL

### EC-1 — The `FUNDS_LOCKED → DISPUTED` path is made impossible (direct contradiction)
- **Boundary/branch:** `OPEN_DISPUTE` fired from state `FUNDS_LOCKED` (dispute opened before shipment).
- **What happens today (per PRD):** FR-1 allows attaching evidence **only from `SHIPPED`**. Addendum §3 states the deposit endpoint refuses if "transaction < `SHIPPED`". FR-6 requires **≥1 piece** to open a dispute, and Addendum §4 proposes a composite "open + attach" endpoint. In `FUNDS_LOCKED` the state is *below* `SHIPPED`, so the attach half is refused → the composite call fails → **`OPEN_DISPUTE` from `FUNDS_LOCKED` can never succeed.** A legitimate platform transition (a "blocage de sécurité en cas d'anomalie détectée en amont", per the schema) is silently broken. It is also unclear who may open it: the schema allows the **seller** here, which the feature PRD never contemplates.
- **What should happen:** Either (a) allow evidence deposit from `FUNDS_LOCKED` as well as `SHIPPED` when the purpose is dispute-opening, or (b) explicitly declare the `FUNDS_LOCKED → DISPUTED` transition out of scope for this POC and document that pre-shipment disputes are not supported yet. The current wording does neither and leaves an impossible-to-satisfy path.
- **Suggested requirement:** *FR — Evidence deposit is permitted in states `{FUNDS_LOCKED, SHIPPED, DISPUTED}`. `OPEN_DISPUTE` is accepted from either `FUNDS_LOCKED` or `SHIPPED`, and in both cases the ≥1-piece rule (FR-6) is enforced within the same unit of work. If pre-shipment disputes are excluded for the POC, state so explicitly and reject `OPEN_DISPUTE` from `FUNDS_LOCKED` with a clear message.*

---

## HIGH

### EC-2 — Deposit lock is event-based (FR-8) but a transaction can become terminal without those events
- **Boundary/branch:** `SHIPPED → RELEASED` via `DELIVERY_CONFIRMED` (no dispute ever opened).
- **What happens today:** FR-8 locks deposits "après `RESOLVE_RELEASE` ou `RESOLVE_REFUND`". A transaction that reaches `RELEASED` through `DELIVERY_CONFIRMED` never fires either event. Read literally, FR-8 does **not** lock it — so a user (FR-1 allows deposit "dès `SHIPPED`", and `RELEASED` was reached from `SHIPPED`) or the carrier could keep attaching evidence to an already-released, funds-transferred transaction. Addendum §4 fixes this by locking on `state ∈ {RELEASED, REFUNDED}`, but the PRD's own FR-8 contradicts it.
- **What should happen:** The lock must be **state-based**, not event-based.
- **Suggested requirement:** *FR-8 (revised) — Any deposit or withdrawal is rejected whenever `state ∈ {RELEASED, REFUNDED}`, regardless of which event produced that state.*

### EC-3 — Carrier can attach evidence to a transaction that is not theirs
- **Boundary/branch:** `POST /api/v1/partner/escrow/{id}/evidence` with a valid HMAC signature but an `{id}` unrelated to the signing company.
- **What happens today:** FR-5 authenticates the carrier by HMAC-SHA256 reusing the webhook secret. But `escrow_transactions` has **no carrier/logistics-company link** (see schema §1), and the HMAC secret lives per `webhook_subscriptions.company_id`. Nothing ties the signing company to the target transaction. Any company holding a valid webhook secret can push evidence onto **any** transaction id it can guess/enumerate, forging "neutral" carrier proof. The addendum stores `partner_company_id` but never validates it against the transaction.
- **What should happen:** The partner deposit must verify that the signing company is the carrier actually associated with that transaction.
- **Suggested requirement:** *FR — The platform must record which logistics company is associated with a transaction (e.g. a `carrier_company_id`), and the partner deposit endpoint must reject any upload whose authenticated `company_id` does not match it (HTTP 403). Until such an association exists, carrier deposits are out of scope.*

### EC-4 — Withdrawing the last active piece leaves an "empty" dispute
- **Boundary/branch:** A `DISPUTED` transaction whose only justifying evidence is withdrawn (FR-12) after opening.
- **What happens today:** FR-6 guarantees ≥1 piece **at opening only**. FR-12 lets any deposited piece be marked `WITHDRAWN` with no floor. So a party can open a dispute with one photo, then withdraw it, producing a `DISPUTED` transaction with **zero `ACTIVE` evidence** — the exact "litige à vide" FR-6 was written to prevent. Success metric "100% of disputes have ≥1 proof" becomes false post-hoc.
- **What should happen:** Preserve the invariant across the whole dispute lifetime, or make it explicitly opening-only and accept empties.
- **Suggested requirement:** *FR — While `state = DISPUTED`, a withdrawal that would drop the count of `ACTIVE` pieces to zero is rejected. The invariant "a dispute always retains ≥1 active piece" holds until resolution.*

### EC-5 — A party can withdraw the *other* party's incriminating evidence
- **Boundary/branch:** `POST .../evidence/{eid}/withdraw` where the caller is not the depositor.
- **What happens today:** FR-12 describes withdrawal as "déposée par erreur" but states **no authorization rule** on who may withdraw. `withdrawn_by_user_id` is just a nullable FK. Nothing stops the buyer from withdrawing the seller's counter-proof (or vice versa), or an admin from withdrawing a party's evidence — evidence tampering that defeats the "visibilité contradictoire" purpose. Carrier-deposited evidence has no `uploaded_by_user_id`, so its withdrawal ownership is undefined too.
- **What should happen:** Withdrawal must be restricted to the depositor (and possibly the arbiter with an audited justification); no cross-party withdrawal.
- **Suggested requirement:** *FR — Only the original depositor may withdraw a piece. Carrier-deposited pieces cannot be withdrawn by any party. An arbiter may withdraw a piece only with a mandatory reason, recorded in `audit_logs`.*

### EC-6 — Offline reconnect when the transaction has already been resolved by someone else
- **Boundary/branch:** FR-15 optimistic `OPEN_DISPUTE` + file queued offline; on reconnect the transaction is already `RELEASED`/`REFUNDED` (arbiter resolved it, or buyer confirmed delivery).
- **What happens today:** FR-15 shows `DISPUTED` optimistically and replays atomically on reconnect. If the server state is already terminal, the composite replay must fail (per EC-2 lock), but the PRD defines **no conflict resolution, no rollback of the optimistic UI, and no fate for the queued 10 MB binary**. The user is left believing a dispute is open when it is not, and their evidence is silently dropped.
- **What should happen:** Deterministic conflict handling on replay failure.
- **Suggested requirement:** *FR — On offline replay, if the server rejects the queued `OPEN_DISPUTE` because the transaction is no longer disputable, the client must (a) reconcile to the true server state, (b) surface an explicit "dispute could not be opened — transaction already resolved" message, and (c) retain the queued files locally so the user can re-view/re-submit rather than lose them.*

---

## MEDIUM

### EC-7 — Two parties (or one party twice) open a dispute offline simultaneously
- **Boundary/branch:** Buyer and seller each queue `OPEN_DISPUTE` offline (legal from `FUNDS_LOCKED` for both), or the buyer double-submits; both replay on reconnect.
- **What happens today:** First replay → `DISPUTED`. Second replay: `OPEN_DISPUTE` from `DISPUTED` is not a valid transition → rejected, and per the atomic-bundle design the **evidence bundled with the losing open is discarded**. No dedup, no "the second party's pieces should still attach to the now-open dispute."
- **Suggested requirement:** *FR — Concurrent/duplicate `OPEN_DISPUTE` submissions are de-duplicated: the first opens the dispute; subsequent ones are downgraded to plain evidence attachments (FR-7) rather than rejected, preserving the second submitter's pieces.*

### EC-8 — Online race: resolution commits while an upload is in flight
- **Boundary/branch:** Arbiter fires `RESOLVE_REFUND` at the same instant the seller uploads counter-evidence online.
- **What happens today:** If the deposit validates state (read `DISPUTED`), then the resolution commits, then the file write commits, the piece lands on a now-terminal transaction, bypassing the FR-8/EC-2 lock. NFR-3 mentions ACID but not the read-check-write ordering on `state`.
- **Suggested requirement:** *NFR — The deposit must re-check `state` under a row lock on `escrow_transactions` within the same DB transaction as the insert, so a concurrent resolution cannot be straddled.*

### EC-9 — Zero-byte / empty file accepted as valid evidence
- **Boundary/branch:** A 0-byte upload (or a whitespace-only "image").
- **What happens today:** FR-4 sets a maximum (10 Mo) but **no minimum**. `size_bytes = 0` satisfies `≤ 10 485 760` and could be a valid-enough JPEG/PDF header sniff. An empty file can therefore satisfy FR-6's count-of-1 while carrying no evidentiary value.
- **Suggested requirement:** *FR — Reject files with `size_bytes = 0` (and, ideally, below a small sane floor). A dispute-opening piece must be a non-empty, decodable file.*

### EC-10 — "10 Mo" boundary is ambiguous and inclusivity is unspecified
- **Boundary/branch:** File of exactly the limit, and limit + 1 byte.
- **What happens today:** FR-4 says "10 Mo"; the addendum says `≤ 10 485 760` (10×1024×1024 = 10 MiB). "10 Mo" could mean 10,000,000 bytes. The two documents disagree by ~485 KB, and FR-4 doesn't state whether exactly-the-limit passes. A file at 10,000,001–10,485,760 bytes is accepted or rejected depending on which doc the dev follows.
- **Suggested requirement:** *FR-4 (revised) — Maximum size is exactly 10,485,760 bytes (10 MiB), inclusive; 10,485,761 bytes is rejected. Client and server use the identical constant.*

### EC-11 — MIME/extension mismatch and filename-based spoofing
- **Boundary/branch:** Correct MIME but wrong extension (PDF named `.jpg`), correct extension but wrong/absent MIME (valid PDF sniffed as `application/octet-stream`), or a malicious filename.
- **What happens today:** NFR-2 validates real MIME server-side and derives the stored extension from it (good). But: (a) a legitimate file whose sniffer returns an off-list synonym (`image/jpg` vs `image/jpeg`) or `octet-stream` would be wrongly rejected — no normalization rule; (b) `original_filename` (VARCHAR 255) is stored/displayed raw — path traversal (`../`), null bytes, or HTML/script in the display name is not addressed beyond "assainissement" of the *stored* name; (c) polyglot files (valid PDF header + embedded active content) pass sniffing, and NFR-6 accepts no AV — fine, but the display/serving path must force download, not inline render.
- **Suggested requirement:** *NFR — Define the exact accepted MIME set and a normalization map; reject on real-MIME mismatch with a clear message. Sanitize AND neutralize `original_filename` for display (strip path components, escape on render). Serve binaries with `Content-Disposition: attachment` and a non-executable content type.*

### EC-12 — No duplicate / idempotency protection on deposit
- **Boundary/branch:** Same file submitted twice — user double-taps "submit", or an offline replay that partially succeeded is retried.
- **What happens today:** No idempotency key and no content hash. Retries and double-taps create duplicate `ACTIVE` rows and duplicate 10 MB files on disk. Combined with optimistic UI (FR-15) this is likely, not rare.
- **Suggested requirement:** *FR/NFR — The deposit endpoint accepts a client-supplied idempotency key (and/or stores a content hash) so replays and double-submits are coalesced instead of duplicated.*

### EC-13 — Carrier partner deposit is not clearly gated by state
- **Boundary/branch:** Carrier pushes evidence in `FUNDS_LOCKED` (before ship) or after `RELEASED`/`REFUNDED`.
- **What happens today:** The "refus si < `SHIPPED` ou litige tranché" gate (Addendum §3) is written for the **user** endpoint. The **partner** endpoint entry lists only signature/anti-replay. It is unstated whether FR-1's `SHIPPED` floor and FR-8's terminal lock apply to the machine deposit. UJ-4 assumes delivery-time (`SHIPPED`) but nothing enforces it.
- **Suggested requirement:** *FR — The partner deposit endpoint is subject to the same state gate as the user endpoint: rejected unless `state ∈ {SHIPPED, DISPUTED}` (plus `FUNDS_LOCKED` if EC-1 is resolved that way), and always rejected when `state ∈ {RELEASED, REFUNDED}`.*

### EC-14 — No hard cap on pieces → local-disk exhaustion
- **Boundary/branch:** A buggy offline retry loop, or an automated carrier, pushes hundreds of 10 MB files.
- **What happens today:** §8 ASSUMPTION: no hard cap, soft "~20". On POC local disk (NFR-1) with no rate limit and no cap, this is an availability/DoS boundary — one runaway client fills the volume for all transactions.
- **Suggested requirement:** *NFR — Enforce a hard per-transaction cap (e.g. 50 pieces) and/or a per-caller rate limit; reject beyond it with HTTP 429/409. Monitor free disk space.*

### EC-15 — Download authorization and access to withdrawn files are underspecified
- **Boundary/branch:** A user from a *different* transaction requests `GET .../evidence/{eid}/download`; or anyone downloads a `WITHDRAWN` piece.
- **What happens today:** FR-11 says "utilisateur autorisé" and NFR-2 says "pas d'URL publique devinable", but the concrete rule — *must be buyer/seller/arbiter of THIS transaction* — is never stated as a requirement, and whether a `WITHDRAWN` binary is still downloadable (for audit) is undefined. IDs are `BIGSERIAL` (enumerable), so relying on non-guessability is insufficient.
- **Suggested requirement:** *FR — Download is authorized only for the buyer, seller, or an arbiter of the specific transaction the piece belongs to; cross-transaction access returns 403/404. Withdrawn pieces remain downloadable to those same parties (marked "retirée") for audit integrity.*

---

## LOW

### EC-16 — Seller opening a dispute from `SHIPPED` vs. deposit rights
- **Boundary/branch:** Seller uses the composite "open + attach" from `SHIPPED`.
- **What happens today:** The schema allows `SHIPPED → DISPUTED` for the **buyer only**, yet FR-1 lets the seller deposit from `SHIPPED`. If the composite endpoint bundles open+attach, a seller-initiated open from `SHIPPED` must be rejected at the *open* step while still allowing the seller's plain deposit. This interaction is not spelled out.
- **Suggested requirement:** *FR — `OPEN_DISPUTE` from `SHIPPED` is restricted to the buyer; a seller attempt is rejected while ordinary seller deposits (FR-7) remain allowed.*

### EC-17 — "Comment obligatoire" has no content rule
- **Boundary/branch:** Opening a dispute with a comment that is empty string / whitespace-only.
- **What happens today:** §8 flags min-length as OPEN. Today an empty or `"   "` comment could pass a naive not-null check, defeating the intent that the opener explain the problem.
- **Suggested requirement:** *FR — The opening comment must be non-blank after trimming, with a defined minimum (e.g. ≥10 chars) and a maximum.*

### EC-18 — Carrier upload before a dispute produces no notification
- **Boundary/branch:** Carrier pushes neutral proof in `SHIPPED` before any dispute (UJ-4).
- **What happens today:** Only `OPEN_DISPUTE` notifies parties (UJ-1). Carrier-pushed evidence may sit unseen until a dispute happens, undercutting its "preuve neutre en amont" value.
- **Suggested requirement:** *FR — A carrier deposit notifies buyer and seller that new neutral evidence is available, independent of dispute state.*

---

## Summary table

| ID | Sev | Boundary | Core gap |
|---|---|---|---|
| EC-1 | CRITICAL | OPEN_DISPUTE from FUNDS_LOCKED | Evidence-from-SHIPPED rule makes pre-ship dispute impossible |
| EC-2 | HIGH | SHIPPED→RELEASED (no dispute) | Event-based lock (FR-8) doesn't cover direct release |
| EC-3 | HIGH | Partner deposit, foreign txn | No company↔transaction authorization |
| EC-4 | HIGH | Withdraw last active piece | Dispute can become empty post-open |
| EC-5 | HIGH | Withdraw others' evidence | No withdrawal authorization = tampering |
| EC-6 | HIGH | Offline replay vs resolved txn | No conflict handling / evidence lost |
| EC-7 | MED | Concurrent offline OPEN_DISPUTE | Second party's pieces discarded |
| EC-8 | MED | Online resolve/upload race | Read-check-write on state not locked |
| EC-9 | MED | Zero-byte file | No minimum size |
| EC-10 | MED | Exactly-10MB boundary | 10 Mo vs 10 MiB, inclusivity unspecified |
| EC-11 | MED | MIME/extension/filename | Normalization + display sanitization + forced download |
| EC-12 | MED | Duplicate uploads | No idempotency / dedup |
| EC-13 | MED | Partner deposit state gate | Gate defined only for user endpoint |
| EC-14 | MED | No hard cap on pieces | Local-disk exhaustion |
| EC-15 | MED | Download authorization | Scope + withdrawn-file access undefined |
| EC-16 | LOW | Seller open from SHIPPED | Open-role vs deposit-role interaction |
| EC-17 | LOW | Empty/whitespace comment | No content rule on mandatory comment |
| EC-18 | LOW | Carrier upload pre-dispute | No notification |
