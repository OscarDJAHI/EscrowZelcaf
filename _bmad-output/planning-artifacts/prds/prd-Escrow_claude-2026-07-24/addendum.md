# Addendum — PRD Plateforme d'Escrow B2B ZLECAf (production)

Ce document rassemble les contenus de profondeur qui n'ont pas leur place dans le PRD mais nourrissent l'architecture, l'UX et le plan de lancement.

## 1. Historique de décision : le wallet (écarté puis réintégré le 2026-07-24)

Le template de référence (EscrowLab) repose sur un wallet rechargeable + ledger + retraits approuvés. **Décision initiale (matin)** : modèle « paiement direct par transaction », sans wallet, pour éviter le statut de détention de fonds de tiers. **Revirement (après-midi, décision utilisateur)** : le wallet est réintégré au MVP — solde par entreprise, dépôts, ledger, retraits approuvés — adossé au compte cantonné du partenaire régulé. Justification produit : alignement avec le modèle complet des maquettes de référence, UX de refinancement plus fluide (un vendeur peut réemployer ses produits de libération pour acheter à son tour sans cycle retrait→dépôt), et mutualisation des dépôts pour un acheteur récurrent `[reconstitué par le PM — la décision utilisateur n'a pas été motivée explicitement]`.

Les contraintes réglementaires identifiées lors de la première décision **restent valides et deviennent des exigences du plan de lancement** (PRD §8, question ouverte §9.6) :
- **Nigeria** : détention de fonds clients = licence MMO (capital ₦2 Mds ; la licence PSSP ne suffit pas) ou partenariat banque/MMO.
- **Kenya** : autorisation E-Money Issuer (CBK), fonds clients en trust bancaire, diversification au-delà de KES 100 M.
- **UEMOA** : agrément EME avec cantonnement continu (BCEAO Instr. 008-05-2015 ; Instr. 001-01-2024 établissements de paiement).
- **Afrique du Sud** : TPPP (détention « durée limitée », réforme SARB 2026 en cours).

Voie recommandée au lancement : **adossement à un établissement licencié** (les soldes juridiquement portés par le partenaire, la plateforme en agent technique) plutôt qu'agrément propre.

Garde-fous back-office conservés malgré le retour du wallet : pas d'ajustement manuel de solde en saisie libre (écritures compensatoires auditées uniquement), pas d'impersonation — deux patterns du template incompatibles avec l'audit d'un opérateur financier.

## 2. Comparables & pricing (recherche 2026-07)

- **Truzo (ZA/UK)** — le plus proche : FSP 51539, TPPP adossé FirstRand, 1er escrow digital « Africa-focused » approuvé FCA (2023). Frais : ZAR min R49, 0,90 % (R5k–100k) → 0,40 % (R1–5 M) ; USD/EUR min 10, ~3,1 % → 0,8 % ; carte/EFT instantané +2,5 % ; FX 0,85 %. Modèle wallet/compte séquestre multi-devises.
- **Vesicash (NG)** — escrow + Merchant of Record, API/SDK ; 2,5 % domestique, 5 % hors Afrique ; milestones ; payouts 50+ pays.
- **TradeSafe (ZA)** — domestique, adossé banque. **Pandascrow, PayScrow, Trustcrow, EscrowLock (NG)** — grappe locale, maturité non auditée.
- **Escrow.com** — ne supporte pas le Nigeria ; menace faible sur l'intra-africain.
- Référence pricing FR-P24 : dégressif par tranche façon Truzo, min fixe + %.
- Limite de la recherche : effectuée depuis un index US, sources francophones sous-représentées — à compléter avant l'extension UEMOA.

## 3. PAPSS (état 2026-07)

~19 pays, 160+ banques début 2026 ; BEAC/CEMAC a rejoint le 2026-07-09 ; partenariat Pesalink (KE) 2026-02-26. Intégration fintech : **participant indirect** via accord de sponsoring avec un participant direct (banque) qui porte règlement et liquidité. Non vérifié : statut UEMOA/BCEAO, volumes réels, délais/coûts d'onboarding. → Post-MVP (PRD §10.2) ; le choix du partenaire bancaire de cantonnement devrait privilégier une banque déjà participante PAPSS.

## 4. PSP candidats (spike AR-P1)

| PSP | Couverture | Notes |
|---|---|---|
| Flutterwave | 30-34 pays, 150 devises | collect cartes/virements/mobile money, payouts + settlement local |
| Paystack (Stripe) | licencié NG, GH, KE, ZA, CI | collect cartes/M-Pesa/MoMo/Wave/Orange Money/EFT, transferts |
| MTN MoMo API, M-Pesa Daraja 3.0, Orange Money | rails directs | pertinents si l'agrégateur ne couvre pas un corridor |

Aucun PSP identifié n'offre de « hold »/blocage natif → l'immobilisation est portée par le compte cantonné partenaire, les PSP restent des rails collect/disburse. Frais par corridor non collectés — à chiffrer au spike.

## 5. Correspondance maquettes EscrowLab → périmètre PRD

| Maquettes | Contenu | Sort dans le PRD |
|---|---|---|
| 01–04 | Site vitrine, blog, contact | Réduit : landing + légal (FR-P41) ; blog/CMS post-MVP |
| 06–10, 22–24 | Login/SSO, OTP, profil, 2FA | Repris sans SSO (FR-P27/P28) |
| 11, 13–15, 20 | Dashboard wallet, dépôts, ledger utilisateur | Repris (FR-P25/P42, dépôts FR-P1/P23) |
| 16–19 | Liste/wizard/détail escrow, frais B/S/50-50 | Repris (UJ-2, FR-P24/P29) ; milestones → post-MVP |
| 12, 45, 50 | KYC + form-builder | Formulaire KYB fixe (FR-P30) ; form-builder écarté |
| 05, 21, 39–40 | Tickets support | Repris (FR-P38) |
| 25–28 | Login admin + dashboard KPI | Partiel : vue synthétique des files (FR-P36) via l'auth ADMIN existante ; portail de login dédié et analytics (browser/OS/pays) écartés |
| 29–31 | Users admin, ±solde, impersonation, mass notif | Partiel : FR-P35 ; ±solde/impersonation/masse écartés |
| 32–34 | Catégories, escrows admin, litige + chat | Repris (FR-P36, FR-P11, FR-P34) |
| 35–38 | Dépôts/retraits admin | Repris : dépôt manuel (FR-P23) et approbation des retraits (FR-P43) — UJ-3 |
| 41 | Ledger admin | Interne (grand livre FR-P3), pas d'UI dédiée MVP |
| 42–49, 51–56 | Config système, passerelles, langues, SEO, maintenance | Réduit : FR-P37 ; le reste écarté ou porté par l'infra |

## 6. Notes réglementaires

- Rétention ZLECAf ≥ 5 ans (exportateur/importateur/autorités) — source Annexe 2 + guide OMD ; fonde FR-P33.
- CoO : émission par autorité compétente, papier OU électronique selon législation nationale, validité 12 mois, tolérance aux erreurs formelles (art. 33) — ne pas sur-valider automatiquement.
- Vérification d'origine : jusqu'à 6 mois, mainlevée sous caution — nourrit la question ouverte §9.7 du PRD (statut « en douane »).
- AU Data Policy Framework : cadre à « domestiquer » par État — les exigences opposables restent nationales (ex. localisation données financières Nigeria).
- Les annexes ZLECAf du dossier sont un **draft** ; la version consolidée + AfCFTA RoO Manual (2022) font foi.

## 7. Rattachement des items différés

Les 3 items différés de la Story 1.1 (remédiation ADMIN historique, outillage admin, course concurrente à l'inscription) sont couverts par FR-P35/P37 et l'Epic 1 existant.
