---
baseline_commit: aucune (spike ADR-only, zéro code produit)
---
# Story 4.1: Spike AR-P2 — Modèle de cantonnement & comptabilité recadré wallet

Status: in-progress

## Livrables

- **ADR (draft)** : `planning-artifacts/architecture/architecture-Escrow_claude-2026-07-24/adr/ADR-AR-P2-cantonnement-comptabilite.md` — voie de cantonnement retenue (adossement banque licenciée, short-list PAPSS : Access/Ecobank/KCB/Standard/UBA), **plan de comptes détaillé + 11 schémas d'écritures M1-M11 équilibrés/idempotents (AC2 COUVERT)**, écart de spine identifié et à remonter (AD-16 « continu UEMOA » → réalité réglementaire = quotidien EOD, Instruction BCEAO 001-01-2024 art. 48.4).
- **Annexe de recherche** : `planning-artifacts/spikes/annexe-AR-P2-recherche-cantonnement-2026-07-25.md`.

## Bloquant restant (AC1)

La validation « contre la réalité du partenaire » exige l'entretien avec la banque pressentie — **email partenaire à envoyer par Oscard** (brouillon : `planning-artifacts/spikes/brouillon-email-partenaire-bancaire.md`). Les 6 questions ouvertes qui closent l'ADR sont listées en §5 de l'ADR.

## Dev Agent Record

claude-fable-5, 2026-07-25 — recherche agent web + ADR draft. AC3 (écart → conflit remonté, pas de dérogation locale) : à exécuter à la clôture (mise à jour memlog spine pour la fréquence AD-16).
