---
baseline_commit: aucune (spike — le code POC restera hors production, répertoire spikes/poc-psp/)
---
# Story 4.2: Spike AR-P1 — Choix du PSP agrégateur (Flutterwave vs Paystack) + POC sandbox

Status: in-progress

## Livrables

- **ADR (pré-décision)** : `planning-artifacts/architecture/architecture-Escrow_claude-2026-07-24/adr/ADR-AR-P1-choix-psp.md` — **matrice AC1 COMPLÈTE** (annexe sourcée) ; pré-décision : **Paystack primaire** (licences sur les 4 pays dont Kenya, fiabilité, idempotence par reference = AD-18) + **Flutterwave secondaire ciblé** (taux FR-P26, XOF futur) ; trous Paystack mitigés (FX via source tierce, entités locales → AR-P5).
- **Annexe de recherche** : `planning-artifacts/spikes/annexe-AR-P1-recherche-psp-2026-07-25.md`.

## Bloquant restant (AC2-AC3-AC4)

Le POC sandbox exige des comptes de test — **action Oscard : créer un compte sandbox Paystack (paystack.com, gratuit) et idéalement Flutterwave**, puis me donner les clés de test (variables d'env, jamais dans le repo — NFR-P1). Plan d'épreuves détaillé dans l'ADR (§POC) : checkout hébergé, webhook signé + rejeu détecté, payout async, constat FX.

## Dev Agent Record

claude-fable-5, 2026-07-25 — recherche agent web + ADR pré-décision. Vigilance : re-tester la licence CBK de Flutterwave à chaque jalon.
