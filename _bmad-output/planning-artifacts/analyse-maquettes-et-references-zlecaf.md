# Analyse des maquettes (Docs/Maquettes) et des références réglementaires (Docs/PDF)

**Date : 2026-07-24 — Auteur : John (PM) — Statut : base de travail pour la mise à jour du PRD**

Objectif : prendre connaissance des 56 maquettes et des 3 PDF de référence ajoutés au projet, en vue de faire évoluer le POC vers une application complète **en conservant la stack actuelle** (Spring Boot + Vue 3 PWA + PostgreSQL).

---

## 1. Nature des maquettes — à savoir avant tout

Les 56 maquettes proviennent d'un **template commercial « EscrowLab V3.0 »** (ViserLab, monolithe Laravel 11 / ViserAdmin). Elles servent de **référence fonctionnelle et UX**, pas de spécification à copier :

- Données factices (Bangladesh, USD unique, PSP occidentaux Stripe/Skrill/2Checkout) ;
- Aucune trace des spécificités du projet : personas transporteur/partenaire HMAC, contexte ZLECAf, offline-first PWA ;
- La transposition se fera en **console d'admin + front consommant des API** (pas en copie du monolithe) ;
- Point de vigilance : la maquette 14 fait saisir la carte bancaire en direct → en production, passer par du hébergé/tokenisé (PCI-DSS).

Signal positif : le template intègre nativement **Flutterwave, PayStack, Mobile Money** et le virement bancaire avec approbation manuelle — cohérent avec le contexte panafricain.

## 2. Cartographie des 56 écrans par parcours

### A. Site vitrine public (01–04)
Landing marketing avec simulateur d'escrow, à propos, blog (CMS), contact (reCAPTCHA). Implique : CMS léger, catégories, newsletter, i18n.

### B. Onboarding & compte (06–10, 22–24)
Inscription (SSO Google/Facebook/LinkedIn, consentement légal 4 documents), vérification email OTP 6 chiffres, récupération de compte, complétion de profil (pays + indicatif mobile), changement de mot de passe, profil, **2FA TOTP** (QR code + activation OTP).

### C. Wallet & flux financiers utilisateur (11, 13–15, 20)
Dashboard avec solde, KPI escrow par statut (Not Accepted / Running / Completed / **Disputed** / Canceled) et alerte KYC bloquante ; dépôt multi-passerelles (passerelle × devise, limites min/max, frais fixes+%, conversion) ; historique dépôts ; **ledger de transactions avec solde après opération**.

### D. Cœur escrow utilisateur (16–19)
Liste « My Escrow » bidirectionnelle (je vends / j'achète), wizard de création en 2 étapes (rôle, catégorie, montant → email contrepartie, frais auto, **répartition des frais Buyer/Seller/50-50**, description), détail avec **jalons (milestones) financés/non financés**, **messagerie intégrée par transaction**, annulation.

### E. KYC (12, 45, 50)
Dossier KYC dynamique (champs paramétrables par l'admin via **form-builder** : texte, select, checkbox, fichier), consultation des justificatifs, toggle global « KYC bloque les retraits ».

### F. Support (05, 21, 39–40)
Tickets avec priorité, fil de conversation, pièces jointes (max 5 fichiers, 256 Mo, jpg/png/pdf/doc), clôture et modération côté admin.

### G. Back-office admin — opérations (25–31, 33–38, 41)
- Login admin dédié (reCAPTCHA) + récupération par code email ;
- Dashboard KPI (utilisateurs, dépôts/retraits en attente, **escrows disputés**, graphiques) ;
- Gestion utilisateurs : liste filtrée par statut de vérification, fiche 360° (impersonation « Login as User », ±solde manuel, ban, bascule des statuts de vérification), notifications de masse (email/Firebase, envoi par lots avec throttling) ;
- Supervision escrows par statut + **arbitrage des litiges** : motif, partie déclarante, jalons, chat tripartite, « Take Action » sur les fonds ;
- **Dépôts** : file d'approbation manuelle sur preuve (référence + capture de virement), Approve/Reject ;
- **Retraits** : file d'attente, coordonnées bancaires/mobile money dynamiques, Approve/Reject, conversion de devise au payout ;
- Ledger global auditable (remarks : frais escrow, paiement milestone, retrait…).

### H. Back-office admin — configuration (32, 42–56)
Hub « System Setting » no-code : général (devise, fuseau, thème), logo/favicon, **feature-flags** (inscription, KYC, vérifications email/SMS, notifications, langue, SSL forcé), catalogue ~30 passerelles de paiement, **méthodes de retrait paramétrables** (frais, limites, taux, form-builder des coordonnées), form-builder KYC, SEO, CMS pages légales, gestionnaire de langues (i18n par clés), extensions (reCAPTCHA, analytics, chat), catégories d'escrow, maintenance (cache, infos serveur/app).

## 3. Cadrage réglementaire (Docs/PDF)

Triptyque : texte juridique (annexes ZLECAf — **draft**), mode d'emploi opérationnel (guide OMD 2023, non contraignant), gouvernance des données (AU Data Policy Framework 2022/2024, à « domestiquer » par chaque État). Aucun des trois ne couvre les paiements privés, les monnaies ou le KYC bancaire (protocoles Phase II, réglementations nationales, PAPSS).

Implications produit concrètes :

1. **Certificat d'Origine ZLECAf = type d'évidence de premier rang** : émis par une autorité compétente, papier OU électronique, **validité 12 mois**, états DUPLICATE / ISSUED RETROSPECTIVELY / REPLACEMENT ; seconde voie de preuve = déclaration d'origine sur facture (envois ≤ 5 000 USD ou « Approved Exporter » avec n° d'autorisation).
2. **Rétention documentaire ≥ 5 ans** (exportateur, importateur, autorités) → aligner la politique de rétention des évidences.
3. **États d'escrow reflétant la réalité douanière** : vérification d'origine jusqu'à 6 mois, mainlevée sous caution/garantie (cas d'usage naturellement proche du séquestre), tolérance aux erreurs formelles sur les CoO (art. 33).
4. **Localisation des données** : régimes hétérogènes par pays (ex. Nigeria : localisation des données financières) ; anticiper le choix des régions d'hébergement et un régime de transfert conditionnel type RGPD/Malabo.
5. **Principes UA de protection des données** (finalité, minimisation, sécurité, notification d'incidents, portabilité) à appliquer au périmètre KYC/évidences — conforte les investissements sécurité déjà réalisés (HMAC, anti-rejeu, durcissement API).

## 4. Analyse d'écart avec l'existant

### Ce que le POC/production actuel couvre déjà
Cœur escrow + cycle de vie, upload d'évidences (backend complet + PWA offline IndexedDB), dépôt partenaire signé HMAC avec anti-rejeu, authentification JWT avec rôles (dont durcissement ADMIN — Story 1.1), harness de tests backend (167+) et frontend (21+).

### Ce que les maquettes ajoutent (majoritairement absent de la stack actuelle)
| Domaine | Contenu | Poids estimé |
|---|---|---|
| Wallet & ledger | Solde interne, transactions avec post-balance, frais | Structurant (modèle financier) |
| Paiements entrants | Multi-PSP (Mobile Money, Flutterwave/PayStack, virement manuel avec preuve) | Structurant |
| Retraits/payouts | Demandes, approbation admin, coordonnées dynamiques, conversion | Important |
| Jalons (milestones) | Financement partiel, paiement par jalon | Structurant (modèle escrow) |
| Litiges & arbitrage | Motif, chat tripartite, décision admin sur les fonds | Important (déjà partiellement au backlog) |
| Messagerie | Fil par transaction acheteur/vendeur/admin | Important |
| KYC | Form-builder dynamique, workflow de revue, blocage des retraits | Important |
| Back-office admin | Console complète (users, escrows, finance, support, config) | Très gros chantier |
| Onboarding | SSO, OTP email, 2FA TOTP, récupération | Moyen |
| Feature-flags & config | Paramétrage no-code (frais, passerelles, langues…) | Moyen (à doser : tout n'est pas nécessaire) |
| Site vitrine & CMS | Landing, blog, pages légales, SEO | Moyen (à prioriser tard) |
| Support | Ticketing avec pièces jointes | Moyen (réutilise la brique évidences) |

### Ce que les maquettes NE couvrent PAS (nos spécificités à préserver)
- Persona transporteur / partenaire HMAC et l'ingestion d'évidences signée ;
- Offline-first PWA (file IndexedDB, replay multipart) ;
- Contexte documentaire ZLECAf (CoO, documents de transport, dossier douanier) ;
- Multi-devises africaines réelles et PAPSS.

## 5. Impact sur le backlog production existant

Le backlog actuel (10 epics / 46 stories, générés le 2026-07-18) a été construit **avant** ces maquettes. Il reste valide sur son axe sécurité/durcissement (Epic 1 en cours) mais ne couvre ni le wallet/ledger, ni les milestones, ni le back-office admin, ni le KYC dynamique, ni les retraits. Une **mise à jour du PRD** puis une **re-dérivation des epics/stories** sont nécessaires ; la contrainte « technos actuelles » (Spring Boot + Vue 3 + PostgreSQL) est actée.

Questions produit ouvertes à trancher au PRD (non résolues par les maquettes) :
1. Modèle financier : wallet interne (comme le template) ou paiement direct par transaction ? (impact réglementaire fort : détenir des fonds = statut d'établissement de paiement selon les pays)
2. Périmètre PSP réel du MVP : Mobile Money/Flutterwave/PayStack vs virement manuel avec preuve (plus simple, déjà proche de notre brique évidences) ;
3. Milestones : indispensables au MVP ou v2 ?
4. Étendue du back-office : quelles cartes de configuration sont réellement nécessaires (vs sur-génie du template) ;
5. KYC : form-builder dynamique ou formulaire fixe adapté ZLECAf (entreprises, pas seulement individus ?) ;
6. Positionnement du site vitrine/CMS dans la roadmap.
