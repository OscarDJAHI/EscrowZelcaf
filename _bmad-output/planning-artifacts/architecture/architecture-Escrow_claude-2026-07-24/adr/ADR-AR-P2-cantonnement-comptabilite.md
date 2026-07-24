# ADR AR-P2 — Modèle de cantonnement & plan de comptes (validation contre la réalité partenaire)

- **Statut : DRAFT — EN ATTENTE PARTENAIRE BANCAIRE** (les schémas d'écritures et l'analyse d'options sont finaux ; les champs marqués ⏳ se remplissent à l'entretien partenaire — dépendance critique n°1, PRD §8)
- **Date : 2026-07-25** · **Story : 4.1** · **Spine : AD-13..16, AD-18, AD-28 (invariants FIXÉS — cette ADR valide, elle ne re-conçoit pas)**
- **Base factuelle :** `planning-artifacts/spikes/annexe-AR-P2-recherche-cantonnement-2026-07-25.md`

## 1. Voie de cantonnement retenue (validée par la recherche)

**Option 1 — adossement à une banque commerciale licenciée** (compte(s) cantonné(s) « au nom de tiers » dans les livres de la banque ; la plateforme n'est jamais détentrice des fonds). Conforme à la voie réglementaire recommandée du PRD.

- **Short-list partenaires (critère PAPSS satisfait)** : Access Bank, Ecobank, KCB, Standard Bank, UBA — les 5 groupes signataires des MOU PAPSS incluant la participation des fintechs via les banques. Corridors couverts : KCB/Standard (KE↔ZA), Access/Ecobank/UBA (NG↔GH).
- Écartées : agrément EP UMOA (100 M FCFA, hors corridors de lancement, délais) — cible phase 2 ; licences pays par pays (₦4 Mds immobilisés rien qu'au Nigéria) — cible long terme ; BaaS nigérian (mono-pays, contrepartie double) — option d'appoint NG à réévaluer ; balances PSP (créance ordinaire, pas un cantonnement — sert uniquement le poste transit).
- ⚠️ Vigilance UMOA (phase 2) : la qualification « agent de services de paiement » de la banque (Instruction BCEAO 001-01-2024, art. 38/40) peut être requise — à qualifier juridiquement avec le partenaire.

## 2. Plan de comptes (détaille AD-16 — inchangé au spine)

| Compte | Nature | Granularité |
|---|---|---|
| `CASH_MIRROR` | Actif — miroir du/des compte(s) cantonné(s) banque | par compte bancaire (⏳ un ou plusieurs, par devise/corridor) |
| `PSP_TRANSIT` | Actif — encaissé chez le PSP, pas encore settlé en banque | par PSP × devise |
| `SUSPENSE_UNMATCHED` | Passif — fonds reçus en banque non encore attribués (virements manuels en attente d'approbation AD-28) | global |
| `WALLET:{company}` | Passif — dette envers l'entreprise | par entreprise |
| `ESCROW:{tx}` | Passif — fonds séquestrés | par transaction |
| `RESERVED:{company}` | Passif — retraits réservés en cours (AD-15) | par entreprise |
| `PLATFORM_FEES` | Produit — frais plateforme acquis | global |

**Équation de rapprochement (AD-16, forme comptable)** :
`CASH_MIRROR + PSP_TRANSIT = Σ WALLET + Σ ESCROW + Σ RESERVED + SUSPENSE_UNMATCHED`
(actifs = passifs clients ; `PLATFORM_FEES` sort de l'équation dès son virement de sweep hors cantonnement — ⏳ modalité de sweep à convenir avec le partenaire).

## 3. Schémas d'écritures par mouvement (équilibrés, idempotents par `movement_id` — AD-18)

Chaque mouvement = une écriture atomique multi-lignes (Σ débits = Σ crédits), rejouable sans effet via le store d'idempotence (clé = `movement_id` métier : référence PSP, id de relevé bancaire, id de commande interne).

| # | Mouvement (glossaire PRD) | Débit | Crédit |
|---|---|---|---|
| M1 | Dépôt PSP constaté (webhook encaissement vérifié) | `PSP_TRANSIT` | `WALLET:c` |
| M2 | Settlement PSP → banque (relevé/API settlement) | `CASH_MIRROR` | `PSP_TRANSIT` |
| M3 | Virement manuel reçu (ligne de relevé non attribuée) | `CASH_MIRROR` | `SUSPENSE_UNMATCHED` |
| M4 | Dépôt manuel **approuvé** (AD-28) | `SUSPENSE_UNMATCHED` | `WALLET:c` |
| M5 | Dépôt manuel **rejeté** → virement retour émis | `SUSPENSE_UNMATCHED` | `CASH_MIRROR` |
| M6 | Financement d'une transaction (+ frais acheteur) | `WALLET:buyer` (montant+frais) | `ESCROW:tx` (montant) ; `PLATFORM_FEES` (frais) |
| M7 | Libération (délivrance validée) (− frais vendeur) | `ESCROW:tx` (total) | `WALLET:seller` (total−frais) ; `PLATFORM_FEES` (frais) |
| M8 | Remboursement (litige/expiration) | `ESCROW:tx` | `WALLET:buyer` (répartition des frais selon configuration 7.3 — paramètre du schéma, pas nouveau schéma) |
| M9 | Demande de retrait → réservation (AD-15) | `WALLET:c` | `RESERVED:c` |
| M10 | Payout confirmé (webhook transfer.success) | `RESERVED:c` | `CASH_MIRROR` |
| M11 | **Échec de payout** (transfer.failed/reversed) → restitution | `RESERVED:c` | `WALLET:c` |

Cas d'échec couverts : M5 (rejet dépôt manuel), M11 (échec payout), M8 (remboursement). Chaque ligne est signée par le writer unique `LedgerService` (AD-13) ; les soldes sont dérivés, jamais stockés.

## 4. Rapprochement (job `scheduler/`, AD-16)

- **Fréquence : quotidien fin de jour ouvrable** sur tous les corridors au MVP + déclenchable à la demande. *Écart de spine constaté et remonté : AD-16 évoque un cantonnement « continu » UEMOA — la réalité réglementaire (Instruction BCEAO 001-01-2024, art. 48.4) est un rapprochement quotidien EOD ; le paramètre par corridor du spine absorbe cet écart sans modification d'invariant (mise à jour memlog spine à faire, conflit mineur).* 
- **Deux flux d'entrée** : relevé bancaire (⏳ format : MT940 / CSV / API corporate — à obtenir du partenaire) pour `CASH_MIRROR`, et API PSP (Paystack `balance/ledger` + Settlements ; Flutterwave Statements) pour `PSP_TRANSIT`.
- Alerte **bloquante avant mise en service Epic 4** en cas d'inéquation (invariant spine).

## 5. Questions ouvertes pour l'entretien partenaire (⏳ closent cette ADR)

1. Compte omnibus unique ou **comptes virtuels par client** ? (granularité du rapprochement ligne à ligne)
2. Format et fréquence des relevés (MT940/CSV/API) ; heure de cut-off EOD par fuseau.
3. Compte cantonné **USD** possible (pivot AD-14) ou miroirs par devise locale avec conversion au taux de clôture ?
4. Conditions juridiques « fonds détenus au nom de tiers » par juridiction (KE, ZA, NG, GH) ; exigence éventuelle de plusieurs comptes de cantonnement.
5. Modalité de sweep des frais plateforme hors cantonnement.
6. Participation PAPSS effective sur nos corridors et conditions fintech.
