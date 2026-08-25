# Annexe spike AR-P1 — Recherche comparée Flutterwave vs Paystack (2026-07-25)

> Rapport de recherche brut. Sert de base factuelle à l'ADR-AR-P1. (†) = fait Paystack
> issu de pages officielles indexées (paystack.com bloque le fetch direct, 403) — à
> re-vérifier au POC. Frais indicatifs avant négociation.

## Tableau de synthèse

| Critère | Flutterwave | Paystack | Avantage |
|---|---|---|---|
| Présence | 30-35 pays, licences ~34 pays | 5 pays : NG, GH, KE, ZA (+CI bêta) | FW |
| Licences sur NOS 4 pays | NG ✔ (switching CBN), GH ✔ (EPSP BoG), ZA ✔, **KE ✖ (absent du répertoire CBK 11/2025)** | NG ✔ GH ✔ KE ✔ ZA ✔ | **Paystack** |
| Devises collecte | NGN GHS KES ZAR XOF XAF UGX TZS RWF USD EUR GBP… | NGN GHS KES ZAR (+USD NG/KE) ; pas de XOF GA | FW |
| Devises payout | GHS KES NGN TZS UGX USD ZAR + momo XOF/XAF | NGN GHS KES (banque+M-PESA) ZAR — domestique par pays | FW |
| Checkout hébergé (NFR-P22) | ✔ v4 + sandbox dédiée | ✔ authorization_url + clés test | = |
| Webhook signature | HMAC-SHA256 `flutterwave-signature` | HMAC-SHA512 `x-paystack-signature` | = |
| Webhook retry | rejeux possibles (idempotence exigée) | retry auto jusqu'à 200 OK | = |
| Idempotence API | header `X-Idempotency-Key` natif v4 | par `reference` métier unique (rejet doublon) | = (deux modèles compatibles AD-18) |
| **API taux FX (FR-P26)** | ✔ `GET/POST /transfers/rates` + real-time FX, MàJ 3-4×/j | ✖ aucune API FX publique | **FW** |
| Settlement | ~24 h local, 48 h intl, multi-devises possible | T+1 NG/GH, T+2 KE/ZA ; « manual payouts » vers balance (†) | Paystack (prévisibilité) |
| Split/sous-comptes | ✔ Split Payments | ✔ Subaccounts + Splits (†) | = |
| Frais NG local | 2 % cap ₦2 000 ; cartes intl 4,8 % ; +2 % intl processing fee APM | 1,5 %+₦100 cap ₦2 000 ; intl 3,9 %+₦100 ; transferts ₦10-50 | **Paystack** |
| Onboarding plateforme | KYB par pays, licences portées par FW (sauf KE) | KYB par pays, **entité locale immatriculée requise par pays** (4 entités) | mitigé |
| Fiabilité 07/2026 | **5 incidents actifs** (KES depuis 15/07 !, webhooks NG 22/07, momo CI depuis mai) ; ₦19 Md irréguliers 2023-24 ; layoffs 50 % KE/ZA 07/2025 | all systems operational, uptime 99,3-100 % ; amende CBN ₦250 M (produit consumer Zap, 04/2025, sans lien API marchande) ; filiale Stripe | **Paystack** |

## Points structurants
1. **Risque dirimant Flutterwave = Kenya** : pas de licence CBK (répertoire officiel 06/11/2025), effectifs KE/ZA –50 % (07/2025), incident KES ouvert depuis le 15/07/2026 — c'est la moitié du corridor 1 (KE↔ZA).
2. **Coût caché Paystack = structurel** : 4 entités locales à immatriculer ; pas de XOF (expansion UEMOA bloquée chez ce fournisseur) ; pas d'API de taux → FR-P26 à servir autrement (taux constaté au settlement + source indicative tierce).
3. Payouts : les deux asynchrones à statuts avec webhooks (transfer.success/failed/reversed chez Paystack ; OTP désactivable via Transfer Control API †). Mapping propre sur AD-15/AD-28 (réservation → approbation → exécution → confirmation).
4. Challengers : Onafriq (payout momo ~40 pays, XOF — candidat 2e adaptateur payout-only) ; Peach Payments / DPO (KE↔ZA) ; Cellulant. Écartés : Stripe/Adyen (pas de collecte locale hors ZA) ; PAPSS (infra interbancaire, critère banque AR-P2).

## Proposition issue de la recherche
POC sandbox sur LES DEUX (effort marginal faible, le port PaymentGateway isole tout) avec les épreuves AC2/AC3. Pré-décision : **Paystack adaptateur primaire** sur les 4 pays de lancement (licences, fiabilité, T+1/T+2, idempotence par reference alignée AD-18) ; **Flutterwave (ou Onafriq) adaptateur secondaire ciblé** (taux de référence FR-P26, corridors XOF futurs), avec re-test de la licence CBK Flutterwave à chaque jalon. À trancher avant l'ADR finale : comment servir FR-P26 sans API FX Paystack ; faisabilité/délai des immatriculations locales (→ AR-P5/Epic 11).

### Sources principales
developer.flutterwave.com (webhooks, mobile-money, transfer-rates, real-time FX, split) · status.flutterwave.com (25/07/2026 : 5 incidents actifs) · flutterwave.com pricing NG + intl fee · paystack.com/docs (webhooks, transfers, transfer-control, channels †) · support.paystack.com (pricing, settlement, manual payouts, compliance par pays †) · paystack.com/blog (transfers KE) · status.paystack.com · répertoire PSP CBK 06/11/2025 (PDF officiel) · TechCrunch 29/07/2022 (CBK vs FW) · TechCabal (₦19 Md 02/2024 ; layoffs 02/07/2025 ; amende Zap 30/04/2025) · Techpoint (EPSP Ghana 08/2024).
