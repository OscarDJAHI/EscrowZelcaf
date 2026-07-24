# Annexe spike AR-P2 — Recherche cantonnement, miroir comptable, rapprochement (2026-07-25)

> Rapport de recherche brut (agent de recherche web, sources primaires privilégiées).
> Sert de base factuelle à l'ADR-AR-P2. Cadre : invariants AD-13..16 fixés par le spine.

## VOLET 1 — Options de cantonnement pour une fintech non licenciée

### Option 1 — Adossement direct à une banque commerciale licenciée (compte cantonné « au nom de tiers »)
- La banque ouvre des comptes cantonnés dans ses livres ; fonds détenus « for the benefit of » nos clients ; la plateforme n'est jamais détentrice, elle tient le grand livre interne (AD-13) et instruit la banque. Voie réglementaire recommandée (dépendance critique n°1).
- Statut requis : aucun agrément propre ; MAIS en UMOA, depuis le 01/09/2025 (fin transition Instruction BCEAO 001-01-2024), la qualification « agent de services de paiement » de la banque peut être requise (art. 38 : immatriculation BCEAO préalable ; art. 40 : la banque reste responsable des actes de l'agent).
- Critère PAPSS : 5 groupes bancaires ont signé des MOU incluant « la participation des fintechs à PAPSS via les banques » : Access Bank, Ecobank, KCB, Standard Bank, UBA → short-list naturelle.
- Miroir AD-16 : la meilleure option si relevés quotidiens (MT940/CSV/API) ; granularité omnibus vs comptes virtuels PAR CLIENT = LA question à trancher en entretien.
- Risques : délai de contractualisation ; API relevés pauvres hors Nigéria ; qualification agent BCEAO ; la Commission Bancaire peut exiger plusieurs comptes de cantonnement (analogie art. 48 al. 2).
- Sources : papss.com (MOU 5 banques, 2024) ; trade.gov Ghana PAPSS Update (2025) ; Instruction BCEAO 001-01-2024 (PDF officiel, lu).

### Option 2 — Agrément Établissement de Paiement UMOA (Instruction 001-01-2024)
- Capital min 100 M FCFA (services i–vi). Art. 48 lu dans le texte : fonds identifiables à tout moment ; cantonnement au plus tard fin du jour ouvrable suivant ; comptes distincts chez la banque ; **rapprochement obligatoire fin de chaque jour ouvrable** (somme comptes de paiement = compte cantonnement) ; fonds hors d'atteinte des créanciers. Placements : ≥30 % dépôts à vue, ≤25 % titres d'État ≤1 an.
- Miroir AD-16 : isomorphe à l'art. 48.4. « Continu UEMOA » du spine = en réalité quotidien EOD.
- Risques : délai d'agrément (engorgement post-09/2025) ; couvre l'UMOA seulement ; impossible d'opérer en attendant (avis 004-03-2025).

### Option 3 — Licences propres pays par pays (NG, GH, KE)
- Nigéria : PSSP (₦100 M + escrow CBN ₦100 M) n'autorise PAS la détention de fonds ; il faut MMO : ₦2 Mds + ₦2 Mds escrow, pool account chez settlement bank, assurance NDIC pass-through (Guidelines CBN 07/2021).
- Ghana (Act 987) : DEMI — 100 % du float en banque locale, séparé, retirable à vue.
- Kenya (E-Money Regs CBK 2013/NPS 2014) : fonds en trust, aucun prêt/investissement, diversification multi-banques obligatoire.
- Miroir : bon mais fragmenté (un compte par juridiction/devise) ; coûts/délais rédhibitoires au MVP (cible long terme).

### Option 4 — BaaS nigérian (Anchor, Maplerad, OnePipe, Bloc)
- Deposit accounts NUBAN réels par client final, balance par compte via API, transferts NIP → rapprochement ligne à ligne.
- Limites : quasi Nigéria/NGN uniquement ; contrepartie double (BaaS + banque) ; jeunesse des acteurs ; banque partenaire non publiée (due diligence directe).

### Option 5 — Balances PSP (Flutterwave/Paystack) comme cantonnement de fait
- Fonds en balance PSP = créance ordinaire sur le PSP, PAS un cantonnement au nom des clients — incompatible NFR-P25 en cible ; zone grise CGU (settlement, pas garde prolongée) ; risque de gel.
- Verdict : sert uniquement le poste « Σ transit PSP » de la formule AD-16.

## VOLET 2 — Flutterwave / Paystack comme miroir du poste transit
- Flutterwave v4 : multi-currency wallets (NGN/USD/EUR/GBP/KES/GHS/ZAR/XOF/XAF…), endpoints Wallets/Balances/**Statements** (relevé par API) → rapprochement transit automatisable. Payout subaccounts v3 = NGN only. Settlement T+1/T+2, webhooks signés (verif-hash).
- Paystack : PAS de wallet par client final. `GET /balance`, `GET /balance/ledger` (journal complet pay-in/pay-out), Settlement API (lots + transactions, export par subaccount), DVA (NUBAN dédié par client = attribution sans ambiguïté des encaissements), webhooks HMAC-SHA512. Fonds settlés T+1/T+2 vers la banque du marchand → la balance ne peut porter que le transit.
- Conclusion : les deux servent correctement « Σ transit PSP » ; AUCUN ne fournit de compte cantonné réglementaire pour wallets/séquestres/réservations.

## VOLET 3 — Pratiques de rapprochement quotidien automatisé
1. Plancher réglementaire UEMOA : rapprochement EOD quotidien (art. 48.4) → paramètre de fréquence AD-16 = « quotidien EOD » partout au MVP.
2. API balance temps réel : Flutterwave v4 Balances, Paystack GET /balance (sondage à la demande — bouton AD-16).
3. Journaux : Paystack balance/ledger ; Flutterwave Statements par wallet ; Settlements des deux (exports détaillés).
4. Webhooks settlement/transaction signés des deux → alimentation au fil de l'eau du poste transit, le job EOD fait la preuve.
5. Côté banque : granularité à négocier (omnibus + libellés vs comptes virtuels par client) ; NG : virtual accounts NUBAN ligne à ligne ; UEMOA : relevé quotidien MT940/CSV/portail. Point de sélection du partenaire — la doc publique ne le tranche pas.

## Écarts spine ↔ réalité (à porter à l'ADR — aucun ne casse AD-13..16)
1. AD-16 « continu UEMOA » → réalité = quotidien fin de jour ouvrable (art. 48.4).
2. Transit PSP et cantonnement = deux sources de rapprochement distinctes (API PSP vs relevé bancaire) ; formule unique, job à deux flux.
3. USD pivot (AD-14) vs cantonnement local (GHS/NGN/FCFA…) : si pas de compte cantonné USD chez le partenaire, miroir multi-devises converti au taux de clôture.

## Non couvert par sources publiques (à obtenir en entretien partenaire)
Format exact des relevés corporate des banques PAPSS candidates (Ecobank/UBA/Access/KCB/Standard) ; conditions « fonds détenus au nom de tiers » par juridiction ; banque partenaire effective des BaaS.

### Sources principales
PAPSS (MOU 5 groupes bancaires, 2024) · trade.gov Ghana PAPSS Update (2025) · Instruction BCEAO n°001-01-2024 (PDF, texte intégral) · Avis BCEAO n°004-03-2025 · CBN Mobile Money Guidelines 07/2021 · Ghana Act 987 (GhaLII) · CBK E-Money Regulations 2013 / NPS Regs 2014 · docs Flutterwave (developer.flutterwave.com, statements/balances/webhooks) · docs Paystack (balance/ledger, settlement, DVA — certaines pages 403, confirmées par index) · TechCrunch/TechCabal (BaaS Anchor).
