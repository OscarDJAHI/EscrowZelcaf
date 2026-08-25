# ADR AR-P1 — Choix du PSP agrégateur (Flutterwave vs Paystack)

- **Statut : DRAFT — PRÉ-DÉCISION, EN ATTENTE POC SANDBOX** (matrice AC1 complète ; AC2/AC3 exigent des comptes sandbox — action Oscard ; l'ADR se finalise sur les preuves du POC)
- **Date : 2026-07-25** · **Story : 4.2** · **Spine : port `PaymentGateway`, AD-14, AD-17, AD-18, AD-28 ; NFR-P22**
- **Base factuelle :** `planning-artifacts/spikes/annexe-AR-P1-recherche-psp-2026-07-25.md` (matrice complète sourcée)

## Pré-décision

**Paystack en adaptateur primaire** pour les 4 pays de lancement (Nigéria, Ghana, Kenya, Afrique du Sud), **Flutterwave en adaptateur secondaire ciblé** (source de taux FR-P26 et corridors XOF futurs), derrière le port unique `PaymentGateway`.

**Motifs (issus de la matrice AC1) :**
1. **Licences exactement sur nos 4 pays** côté Paystack ; Flutterwave n'apparaît toujours pas au répertoire officiel CBK du Kenya (06/11/2025) — moitié du corridor 1, risque dirimant.
2. **Fiabilité opérationnelle** : status Paystack sain (uptime 99,3-100 %) vs 5 incidents Flutterwave actifs au 25/07/2026 dont KES (depuis le 15/07) et webhooks NG ; layoffs Flutterwave de 50 % au KE/ZA (07/2025).
3. **Alignement technique** : idempotence par `reference` métier = exactement AD-18 ; webhooks HMAC-SHA512 signés avec retry jusqu'à 200 (AD-17) ; settlement T+1/T+2 prévisible (alimente le poste M2 de l'ADR-AR-P2).

**Trous assumés de Paystack et leurs mitigations :**
- **Pas d'API de taux FX (FR-P26)** → le taux appliqué est constaté au settlement (rapprochement AD-16) et le « taux indicatif ≈ » d'AD-14 est servi par une source tierce — candidat : l'API `transfers/rates` de Flutterwave en lecture seule (adaptateur secondaire), sinon flux de taux public. **Point à valider au POC.**
- **Pas de XOF en GA** → l'expansion UEMOA (phase 2) passera par l'adaptateur secondaire (Flutterwave ou Onafriq payout-only) — aucun impact MVP.
- **Entité locale requise par pays** (4 immatriculations) → coût/délai remonté à AR-P5/Epic 11 et au séquencement produit ; le MVP peut démarrer corridor par corridor au rythme des immatriculations.

## Plan du POC sandbox (AC2/AC3 — À EXÉCUTER, prérequis : comptes sandbox créés par Oscard)

| Épreuve | Attendu |
|---|---|
| Encaissement via checkout hébergé Paystack (`authorization_url`) | aboutit sans qu'aucune donnée carte ne touche nos écrans/serveurs (NFR-P22) |
| Webhook `charge.success` | signature HMAC-SHA512 vérifiée ; **rejeu du même webhook détecté** (idempotence par référence) |
| Payout sandbox (banque + M-PESA test) | abouti en devise locale, référence traçable, statuts async `transfer.success/failed` reçus |
| Taux FX | constater l'absence d'API Paystack ; valider la lecture `transfers/rates` Flutterwave sandbox comme source indicative (FR-P26) |
| Clés | sandbox/production séparées, externalisées (NFR-P1 — jamais dans le code ni le compose) |

Le code du POC vivra dans `spikes/poc-psp/` (hors code de production, conforme AC4).

## Finalisation

L'ADR passe en **ACCEPTÉE** quand : les 5 épreuves du POC sont vertes + la grille tarifaire GH/KE/ZA réelle est relevée + le modèle « collecte pour compte de tiers » est confirmé acceptable par le compliance Paystack (à poser explicitement à l'onboarding). Re-tester la licence CBK Flutterwave à chaque jalon avant tout élargissement de son rôle.
