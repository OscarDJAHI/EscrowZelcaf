# PRD Quality Review — Dépôt de preuves de transaction & de litige (Evidence Upload)

## Overall verdict

This is a genuinely good feature PRD for a learning POC: it has a real thesis (evidence is the pivot of escrow trust, so a dispute cannot be opened "empty"), personas that each drive a distinct user journey, functional requirements that are mostly testable with concrete bounds (10 MB, JPG/PNG/PDF, HMAC-SHA256, chronological listing, terminal-state lockout), and unusually honest scope discipline (explicit out-of-scope list, accepted-risk NFR, alternatives-rejected log in the addendum). What's at risk is one real state-machine contradiction — evidence is only allowed from `SHIPPED` (FR-1) yet the base platform allows `OPEN_DISPUTE` from `FUNDS_LOCKED`, which FR-6 would then make impossible to satisfy — plus the absence of a Glossary and a few soft acceptance criteria around the hardest FR (offline atomic replay). None of these are launch-blockers at POC stakes, but the `FUNDS_LOCKED` dispute gap will bite downstream story creation if left unstated.

## Decision-readiness — strong

Decisions are stated as decisions, not smuggled in as "considerations." The offline-open-dispute problem is confronted head-on in the addendum (§4: "un endpoint composite « ouvrir + joindre » est probablement le plus sûr pour garantir l'atomicité"), and the HMAC-vs-interactive-carrier choice is made explicitly with the loser named (addendum §7: "Livreur en acteur interactif complet — écarté pour le POC"). The Open Questions in §8 are actually open — "Faut-il notifier le partenaire livreur du résultat…?" and "Le commentaire obligatoire… a-t-il une longueur minimale?" have no smuggled answer. NFR-6 does the honest thing and books the no-antivirus decision as an *explicitly accepted* risk rather than pretending it away. Trade-offs are surfaced, not smoothed to neutral.

### Findings
- **low** Open-vs-decided boundary on the composite endpoint (addendum §4) — The atomicity solution is described as "probablement le plus sûr" and "à trancher en architecture," which is appropriate, but FR-15's optimistic-`DISPUTED` guarantee depends on it. *Fix:* add a one-line `[NOTE FOR PM]`/`[NOTE FOR ARCH]` at FR-15 pointing to addendum §4 so the dependency is not lost when the FR is extracted alone.

## Substance over theater — strong

No furniture here. The four actors (§3) are the real cast of an escrow dispute — buyer, seller, arbiter, carrier — and each one drives a named journey (UJ-1..UJ-4); the carrier is correctly framed as a *machine* contributor, not a fifth human persona padding the count. NFRs carry product-specific thresholds rather than boilerplate: NFR-2 names server-side MIME + size validation, filename sanitization, and non-guessable access; NFR-5 names timestamp anti-replay. The success metrics validate the thesis ("100 % des litiges ouverts comportent ≥ 1 preuve," reduced mean resolution time) rather than counting activity, and counter-metrics are present (§7: upload failure rate, file-size drift, withdrawal rate as a UX-confusion signal). This is earned content.

### Findings
_None._

## Strategic coherence — strong

The PRD bets on a single, clearly stated thesis (§1: "la preuve est le pivot de la confiance") and every feature serves it: evidence deposit (A), mandatory evidence to open a dispute (B/FR-6), contradictory visibility so both sides and the arbiter see everything (C/FR-9), and immutability/audit so the record can be trusted (D). Prioritization follows the thesis — the mandatory-evidence rule is the spine, not "what's easy first." Success and counter-metrics tie back to the thesis. It reads as a coherent problem-solving MVP, not a backlog with headings.

### Findings
_None._

## Done-ness clarity — adequate

Most FRs carry at least one testable consequence: FR-3 (type allow-list), FR-4 (10 MB), FR-5 (HMAC-SHA256), FR-8 (lockout at `RELEASED`/`REFUNDED`), FR-10 (chronological, with named metadata fields), FR-12 (logical withdrawal, no physical delete). The addendum sharpens these with a concrete schema and endpoint list. The weak spots are the hardest requirement and a few adjectives. FR-15 (offline open-dispute + atomic queue + optimistic `DISPUTED`) has no stated behavior for the failure path — what the user sees if the server *rejects* the file on replay (oversize, bad MIME, or state no longer `SHIPPED`) after the optimistic `DISPUTED` was already shown. FR-3's "message explicite" and NFR-4's "payload maîtrisé" / "compression = optionnel" are adjectives without bounds. This is the dimension downstream stories lean on hardest, so these are worth tightening even at POC rigor.

### Findings
- **medium** FR-15 has no reconciliation/failure semantics — Optimistic `DISPUTED` is shown before sync, but the PRD never says what happens when the queued open+evidence is rejected on reconnect (file too big, MIME invalid, or transaction already moved past `SHIPPED`). *Fix:* add an acceptance clause: on replay rejection, roll the optimistic state back to the true server state and surface a retriable error; specify whether the queued binary is retained for retry.
- **low** Adjective bounds (FR-3, NFR-4) — "message explicite," "payload maîtrisé," "compression optionnel" lack testable bounds. *Fix:* FR-3 → require the rejection message to name the offending type and the allowed set; NFR-4 → either drop the compression clause or state a soft target (e.g. images downscaled to ≤ N px on the client when available).

## Scope honesty — strong

This is the PRD's best dimension. Omissions are explicit, never left to inference: §2 has a real "Hors périmètre (POC)" list (interactive carrier role, antivirus, S3 + at-rest encryption, retention/purge, OCR), §9 promotes each to a numbered future evolution, and the addendum §7 keeps a rejected-alternatives log for decision traceability. Inferences are tagged (`[ASSUMPTION]` on the ~20-piece soft cap and optional thumbnails; `[OPEN]` on carrier result-notification and comment min-length). NFR-6 de-scopes malware scanning as an accepted, documented risk rather than silently. Open-items density is modest and entirely appropriate for the stakes.

### Findings
- **low** Soft cap left unresolved with a storage implication (§8 `[ASSUMPTION]` + addendum §5) — "pas de plafond dur… ~20" combined with IndexedDB holding files up to 10 MB each means an unbounded offline queue could exhaust browser storage. *Fix:* for the POC, convert the soft cap into an enforced ceiling on queued/offline items, or note the storage-pressure risk explicitly at NFR-4.

## Downstream usability — adequate

IDs are clean: FR-1..15, UJ-1..4, NFR-1..6 are contiguous, unique, and internal cross-references resolve (FR-6, FR-8, FR-10, FR-15, NFR-1/NFR-6 are all cited where relevant). The addendum extracts cleanly into schema/endpoints/state-machine/offline/audit buckets that map to the FRs. The gap is the absence of a **Glossary**: domain nouns are used consistently in prose (states, `uploader_type`, "retrait logique"), but a downstream UX/architecture/story pass has no single source for terms, and there is minor drift between the human actor "Livreur/transporteur" (§3) and the enum value `CARRIER_PARTNER` / rejected future role `CARRIER` (addendum). One cross-artifact trap (the `FUNDS_LOCKED` dispute) is carried in the Strategic/consistency finding below rather than here.

### Findings
- **medium** No Glossary for a chain-top PRD (feeds architecture → stories) — Terms like `SHIPPED`, `DISPUTED`, `uploader_type`, `WITHDRAWN`, "contradictoire," "retrait logique," and the actor↔enum mapping (Livreur → `CARRIER_PARTNER`) live scattered across PRD + addendum + base docs. *Fix:* add a short Glossary section binding each domain noun to one definition and to its base-schema/state-machine referent.

## Shape fit — strong

The shape matches the product. This is a multi-stakeholder B2B feature where who-sees-what and who-can-act-when is load-bearing, so UJs with named protagonists (Amina/Thabo/Koffi + the carrier machine) are exactly right, not overhead. The "quoi" in prd.md / "comment" in addendum.md split is well-judged and keeps the PRD at requirement altitude. Brownfield references are accurate against the provided docs: `escrow_transactions.state` carries the cited states, the state-machine triggers (`OPEN_DISPUTE`, `RESOLVE_RELEASE`, `RESOLVE_REFUND`) match, `audit_logs` and `webhook_subscriptions.secret_key` (HMAC-SHA256) exist, and `companies` supports `partner_company_id`. No over- or under-formalization.

### Findings
- **high** State-machine contradiction: dispute at `FUNDS_LOCKED` is unsatisfiable — FR-1 allows evidence only "dès l'état `SHIPPED`," and FR-6 makes ≥1 piece a hard precondition of `OPEN_DISPUTE`; but the base state machine (backend_schema §2) allows `FUNDS_LOCKED → OPEN_DISPUTE → DISPUTED`. A party disputing at `FUNDS_LOCKED` can attach no evidence, so the dispute becomes impossible to open — a silent behavior change to the existing platform. *Fix:* state the intended resolution explicitly — either (a) declare pre-`SHIPPED` disputes out of scope for this feature and note the base transition is untouched (evidence rule applies only to `SHIPPED`+ disputes), or (b) extend FR-1 to allow evidence from `FUNDS_LOCKED` so FR-6 is satisfiable there too.
- **medium** HMAC reuse conflates outbound and inbound signing (FR-5, NFR-5, addendum §3/§7) — `webhook_subscriptions.secret_key` exists to sign *outbound* platform→partner callbacks; the feature reuses it to authenticate an *inbound* partner→platform upload, which needs a per-partner inbound key, an anti-replay nonce/timestamp store, and a `partner_company_id` lookup path that the current schema does not spell out. *Fix:* in the addendum, note the inbound direction is new: specify where the inbound signing key and the replay-window/nonce state live, rather than "réutilise la logique de signature webhook existante."
- **low** "Existing" offline store unverified against provided docs (addendum §5) — `OfflineQueueStore` is referenced as existing, but the provided schema/PRD docs describe the PWA offline capability (platform §4.4) without naming that component. *Fix:* confirm the component exists / name it precisely, or tag it `[ASSUMPTION]`.

## Mechanical notes

- **Glossary:** absent — see Downstream finding. Domain nouns are otherwise used consistently in prose.
- **ID continuity:** FR-1..15, UJ-1..4, NFR-1..6 all contiguous, unique; no gaps or duplicates. Cross-references (FR-6/8/10/15, NFR-1/6, §9) resolve.
- **Assumptions roundtrip:** two `[ASSUMPTION]` and two `[OPEN]` tags in §8; all appear inline. No end-of-doc Assumptions Index, but at this length an inline §8 is sufficient.
- **Term drift:** actor "Livreur/transporteur" (§3) vs enum `CARRIER_PARTNER` (addendum §1) vs future role `CARRIER` (addendum §7) — bind these in a Glossary.
- **UJ protagonists:** each UJ carries a named protagonist (Amina, Thabo, Koffi, partenaire logistique) with context inline. No floating UJs.
- **Base-doc accuracy:** state names, triggers, `audit_logs`, `webhook_subscriptions.secret_key`, `companies` all verified against `Docs/backend_schema_escrow.md`.
