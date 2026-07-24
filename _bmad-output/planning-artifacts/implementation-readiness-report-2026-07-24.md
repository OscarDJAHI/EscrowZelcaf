---
stepsCompleted: ['step-01-document-discovery', 'step-02-prd-analysis', 'step-03-epic-coverage-validation', 'step-04-ux-alignment', 'step-05-epic-quality-review', 'step-06-final-assessment']
documentsIncluded:
  prd: 'prds/prd-Escrow_claude-2026-07-24/prd.md (+ addendum.md)'
  architecture: 'architecture/architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md (production, final — remplace le spine feature 2026-07-15 comme référence, celui-ci restant hérité AD-1..12)'
  epics: 'epics.md'
  ux: 'ux-designs/ux-Escrow_claude-2026-07-24/DESIGN.md + EXPERIENCE.md'
documentsExcluded:
  - 'epics-production.md (ancien backlog production 10 epics/46 stories, antérieur aux maquettes)'
  - 'epics-poc-evidence-upload.md (backlog POC clôturé)'
  - 'prds/prd-Escrow-2026-07-15/ (PRD POC remplacé par le PRD production)'
---

# Implementation Readiness Assessment Report

**Date:** 2026-07-24
**Projet:** Escrow_claude

## Inventaire des documents

| Type | Fichier retenu | Modifié |
|---|---|---|
| PRD | `prds/prd-Escrow_claude-2026-07-24/prd.md` (+ addendum) | 24 juil. 2026 |
| Architecture | `architecture/architecture-Escrow-2026-07-15/ARCHITECTURE-SPINE.md` | 17 juil. 2026 |
| Epics/Stories | `epics.md` | 24 juil. 2026 |
| UX | `ux-designs/ux-Escrow_claude-2026-07-24/DESIGN.md` + `EXPERIENCE.md` | 24 juil. 2026 |

**Exclus de l'évaluation (historiques, non supprimés) :** `epics-production.md`, `epics-poc-evidence-upload.md`, `prds/prd-Escrow-2026-07-15/`.

**Points d'attention relevés dès la découverte :**
- Triplication des fichiers epics — seule la version re-dérivée du 24 juil. (`epics.md`, 11 epics / 63 stories) fait foi.
- Architecture (spine) datée des 15–17 juil., antérieure au PRD production du 24 juil. — l'adéquation d'altitude est à valider dans cette évaluation.

## Analyse du PRD

**Source :** `prds/prd-Escrow_claude-2026-07-24/prd.md` (statut : final, 2026-07-24) + `addendum.md`. Règle de préséance : ce PRD prévaut sur tous les documents absorbés (PRD POC, PRD Evidence Upload, backlog production du 18 juil., analyse maquettes).

### Exigences fonctionnelles (FR-P)

#### A. Cœur séquestre & fonds réels
- **FR-P1** *(amendé)* — Encaissement réel des fonds acheteur via PSP ; le crédit alimente le **wallet** (FR-P25) ; l'immobilisation s'effectue par **débit du wallet** avant `FUNDS_LOCKED`.
- **FR-P2** *(amendé)* — `RELEASED` = crédit effectif du wallet vendeur ; `REFUNDED` = crédit effectif du wallet acheteur ; le versement bancaire externe intervient au **retrait** (FR-P43).
- **FR-P3** — Compte de cantonnement ségrégué + grand livre en partie double auditable, rapprochement par transaction.
- **FR-P4** *(amendé)* — Commission calculée et tracée ; part vendeur déduite du crédit de libération, part acheteur prélevée au financement, selon la répartition choisie (FR-P24).
- **FR-P23** — Virement bancaire manuel comme moyen de dépôt : déclaration + preuve jointe ; rapprochement et approbation/rejet opérateur ; l'approbation crédite le wallet. Aucun crédit sans validation humaine.
- **FR-P24** — Barème de frais configurable par l'opérateur : % dégressif par tranche `[ASSUMPTION ~1–3 %]` + minimum fixe ; répartition (acheteur/vendeur/50-50) choisie à la création, visible avant acceptation ; frais de dépôt et de retrait par méthode configurables et affichés avant confirmation.
- **FR-P25** — Wallet d'entreprise : solde unique par entreprise `[ASSUMPTION]`, alimenté par dépôts (FR-P1/FR-P23), débité au financement, crédité à la libération/remboursement ; solde adossé au grand livre (FR-P3), rapprochable du compte cantonné (NFR-P25).
- **FR-P42** — Historique du wallet (ledger utilisateur) : chaque mouvement visible avec référence, horodatage, montant signé, solde après opération ; filtrable par type et période.
- **FR-P43** — Retraits : réservés aux entreprises KYB approuvé (FR-P31) ; vers compte bancaire ou mobile money ; limites min/max et frais par méthode ; approbation opérateur avant exécution ; débit à la demande (réservation), restitution en cas de rejet ; versement en devise locale ; SLA `[ASSUMPTION ≤ 1 j ouvré]`.

#### B. Devises
- **FR-P5** — Transaction bi-devise : taux figé à l'engagement, spread, devise de règlement définie.
- **FR-P6** — Devises validées contre liste ISO-4217 des devises réellement réglables.
- **FR-P26** — USD devise pivot : contrats et wallets en USD au MVP `[ASSUMPTION]` ; affichage indicatif en devise locale ; conversions définies (dépôt au taux courant PSP, montant contrat figé, remboursement au montant figé, retrait au taux du jour) ; risque FX aux frontières porté par l'utilisateur `[ASSUMPTION]`.

#### C. Onboarding, comptes & entreprises
- **FR-P9** — Onboarding relie l'utilisateur à une entreprise (User↔Company).
- **FR-P13** — Réinitialisation de mot de passe et invitation d'utilisateur.
- **FR-P14** — Multi-utilisateur par entreprise avec rôles internes.
- **FR-P16** — Rôle ADMIN octroyé par la plateforme uniquement *(livré)*.
- **FR-P27** — Inscription email + mot de passe avec vérification OTP 6 chiffres (renvoi limité) ; consentement aux documents légaux horodaté.
- **FR-P28** — 2FA TOTP optionnelle (enrôlement QR, activation par OTP, codes de récupération) ; exigible à la connexion une fois activée.
- **FR-P29** — Transaction vers contrepartie non inscrite (invitation email) ; en attente jusqu'à inscription + KYB approuvé ; expiration (FR-P12).

#### D. Conformité KYB/AML
- **FR-P7** — Workflow KYB entreprise complet requis avant transaction.
- **FR-P8** — Screening AML/sanctions à l'onboarding et en continu.
- **FR-P10** — Rétention légale minimale + inaltérabilité (WORM) des preuves et de l'audit.
- **FR-P30** — Formulaire KYB fixe adapté ZLECAf (pas de form-builder) ; revue manuelle opérateur ; motif de rejet notifié, re-soumission possible ; SLA `[ASSUMPTION ≤ 2 j ouvrés]`.
- **FR-P31** — KYB non approuvé = consultation seule : ni création, ni acceptation, ni financement, ni retrait ; dépôt reçu avant approbation immobilisé sur le wallet (verrou AML).

#### E. Preuves & documents ZLECAf
- **FR-1 à FR-16 du PRD Evidence Upload** restent normatives *(livrées)*, sauf : FR-14 (rétention illimitée POC) remplacée par FR-P33 ; NFR-6 (pas d'antivirus, accepté POC) levée par NFR-P7.
- **FR-P32** — Certificat d'Origine ZLECAf comme type de preuve de premier rang : métadonnées dédiées (n°, autorité, date, n° Approved Exporter, états DUPLICATE/ISSUED RETROSPECTIVELY/REPLACEMENT), validité 12 mois signalée ; déclaration d'origine sur facture acceptée (≤ 5 000 USD, ou sans plafond pour Approved Exporter) ; pas de validation automatique au MVP `[ASSUMPTION]`.
- **FR-P33** — Rétention preuves + audit ≥ 5 ans (exigence ZLECAf), purge interdite avant échéance, WORM (cohérent FR-P10). Remplace FR-14 POC.

#### F. Litige, arbitrage & messagerie
- **FR-P11** — Console d'arbitrage : file, assignation, preuves, décision motivée et tracée.
- **FR-P12** — SLA cycle de vie : auto-remboursement si non-expédition, auto-libération après X jours sans litige post-livraison `[ASSUMPTION 7 j]`, expiration des transactions non financées ; étendu aux invitations non acceptées (FR-P29).
- **FR-P34** — Messagerie par transaction : fil unique acheteur/vendeur/arbitre (dès litige), horodatée, conservée avec le dossier (même rétention), verrouillée en état terminal `[ASSUMPTION texte seul]`.

#### G. Back-office opérateur
- **FR-P15** — Provisioning des clés HMAC partenaire (générer, afficher une fois, révoquer, rotation — auditable).
- **FR-P35** — Gestion utilisateurs/entreprises : recherche, fiche détaillée, suspension/réactivation motivée et auditée ; **pas d'impersonation ni d'ajustement manuel de solde** — corrections par écriture compensatoire motivée au grand livre.
- **FR-P36** — Supervision des transactions : liste filtrable, détail complet, export `[ASSUMPTION CSV]` ; vue synthétique d'accueil (files KYB, dépôts manuels, retraits, litiges, tickets ; volumes clés) ; accès via auth + rôle ADMIN existants.
- **FR-P37** — Configuration produit : barème de frais, catégories, délais/SLA, textes légaux ; modifications versionnées et auditées ; transactions en cours conservent leurs conditions d'origine.
- **FR-P38** — Tickets support : création utilisateur (sujet, priorité, pièces jointes via brique upload), fil de réponses opérateur, statuts ouvert/répondu/fermé.

#### H. Notifications
- **FR-P17** — Notifications email/SMS sur transitions clés.
- **FR-P18** — Webhooks sortants fiables (retry + DLQ + journal), scopés tenant ; abonnement self-service des partenaires maintenu.
- **FR-P39** — Notifications in-app (cloche + liste) pour transitions clés et messages reçus `[ASSUMPTION pas de push navigateur]`.

#### I. Offline (PWA)
- **FR-P19 à FR-P22** — File sur échec réseau réel, idempotence du rejeu, cache local du détail de transaction, versement de preuve simple hors-ligne, plafond 20 fichiers miroité front — inchangés.
- **FR-P40** — Périmètre offline MVP : consultation, versement de preuves, ouverture de litige (atomique, livré) ; actions financières exigent une connexion `[ASSUMPTION]`. Remplace l'ambition POC « validation hors-ligne ».

#### J. Site public
- **FR-P41** — Landing simple + pages légales éditables (FR-P37) ; blog/CMS, simulateur, contact, newsletter explicitement hors MVP.

**Total FR : 43 identifiants FR-P (FR-P1 → FR-P43, tous présents), dont 3 amendés (FR-P1/P2/P4) et 21 nouveaux (FR-P23+) ; + 16 FR Evidence Upload héritées normatives (2 remplacées/levées).**

### Exigences non-fonctionnelles (NFR-P)

- **NFR-P1 à NFR-P21** *(backlog production, référencées normatives — non détaillées individuellement dans le PRD)* :
  - Sécurité : secrets externalisés, rate-limiting, TLS/HSTS/CSP, CORS allowlist + Swagger fermé en prod, politique de mots de passe + révocation JWT, chiffrement au repos, antivirus à l'ingestion (NFR-P7), hygiène de session, anti-énumération, idempotence du rejeu offline.
  - Opérabilité : CI/CD, SBOM, observabilité, backup/restore testé, stack déployable, profils d'environnements, résilience, rétention d'audit, tests de charge, E2E, résorption de la dette de tests.
- **NFR-P22 (PCI-DSS)** — Aucune donnée carte ne transite/n'est stockée ; parcours PSP hébergés/tokenisés exclusivement ; pattern « saisie carte en direct » des maquettes rejeté.
- **NFR-P23 (Données & localisation)** — Principes UA/Malabo (minimisation, finalité, notification d'incidents) ; régions d'hébergement tenant compte de la localisation des données financières par pays ; décision d'architecture documentée par corridor avant lancement.
- **NFR-P24 (i18n)** — Interface bilingue EN + FR dès le MVP `[ASSUMPTION]` ; traduction par clés, sans gestionnaire no-code.
- **NFR-P25 (Ségrégation des fonds)** — Invariant comptable : somme des fonds séquestrés (grand livre) rapprochable du solde du compte cantonné à tout instant ; écart = alerte bloquante (lien NFR-P13).

**Total NFR : 25 identifiants NFR-P (NFR-P1 → NFR-P25).**

### Exigences et contraintes additionnelles

- **Dépendance critique n°1** : statut réglementaire de détention de fonds de tiers (wallet) — avis juridique par pays avant tout encaissement réel ; voie recommandée : adossement à un établissement licencié.
- **Dépendances externes** : partenaire bancaire de cantonnement (prérequis lancement) ; PSP agrégateur (spike AR-P1 : Flutterwave vs Paystack) ; fournisseur KYB/AML (AR-P3).
- **Spikes d'architecture ouverts** : AR-P1 (PSP), AR-P2 (cantonnement & comptabilité — **à recadrer en priorité**, périmètre redéfini par le wallet), AR-P3 (KYB/AML), AR-P4 (secrets), AR-P5 (déploiement), AR-P6 (notifications).
- **Cycle de vie normatif** : `INITIATED` → `FUNDS_LOCKED` → `SHIPPED` → `RELEASED` (branches `DISPUTED` → `RELEASED`/`REFUNDED`), étendu en amont par les statuts d'invitation ; `DELIVERY_CONFIRMED` et les résolutions de litige sont des événements, pas des états.
- **Contrainte de re-dérivation** : le backlog écrit sous l'hypothèse « paiement par transaction » devait être re-dérivé de ce PRD (le wallet n'avait aucune story) — c'est l'objet du `epics.md` du 24 juil., à vérifier en étape 3.
- **Hypothèses structurantes ouvertes (§9)** : corridors, barème, USD pivot, PSP/KYB, délai d'auto-libération, voie réglementaire wallet (phase-blocker commercial), statut « en douane » (non bloquant).

### Évaluation de complétude du PRD

**Points forts :** vision et périmètre nets (MVP vs post-MVP explicite) ; glossaire normatif ; règle de préséance claire ; cycle de vie de référence défini ; hypothèses systématiquement taguées `[ASSUMPTION]` ; traçabilité des amendements (FR-P1/P2/P4) ; contraintes réglementaires par corridor documentées en addendum ; garde-fous back-office explicites (pas d'impersonation, pas d'ajustement libre de solde).

**Points de vigilance :**
1. **NFR-P1 à NFR-P21 non détaillées dans le PRD** — référencées par thème seulement ; leur texte normatif réside dans le backlog. La traçabilité NFR dépend donc du fichier epics — à vérifier en étape 3.
2. **FR-P19 à FR-P22 résumées en une ligne groupée** — texte normatif complet également côté backlog.
3. Le PRD délègue explicitement la re-dérivation du backlog (Epic 2 wallet) — la conformité de `epics.md` est le point central de cette évaluation.

## Validation de couverture par les epics

**Source :** `epics.md` (re-dérivé du 2026-07-24 ; frontmatter : PRD production + addendum + ARCHITECTURE-SPINE + DESIGN/EXPERIENCE en entrées). Structure vérifiée : **11 epics / 63 stories** (E1:10, E2:6, E3:5, E4:9, E5:5, E6:4, E7:5, E8:4, E9:5, E10:2, E11:8). Le document contient une FR Coverage Map explicite — chaque entrée a été contre-vérifiée dans le texte des stories.

### Matrice de couverture FR

| FR | Exigence (résumé) | Couverture epics | Statut |
|---|---|---|---|
| FR-P1 | Encaissement PSP → wallet, débit avant FUNDS_LOCKED | Epic 4 — Stories 4.4, 4.6 | ✓ |
| FR-P2 | RELEASED/REFUNDED = crédits wallet effectifs | Epic 4 — Story 4.7 | ✓ |
| FR-P3 | Cantonnement + grand livre partie double | Epic 4 — Stories 4.1 (ADR), 4.3 | ✓ |
| FR-P4 | Commission tracée, prélèvement réparti | Epic 4 — Stories 4.6, 4.7 | ✓ |
| FR-P5 | Bi-devise : taux figé, spread, devise de règlement | Epic 4 — Story 4.6 (taux/devise figés) | ✓ (voir observation n°1) |
| FR-P6 | Devises ISO-4217 réglables | Epic 4 — Story 4.3 (contrainte en base, seed USD) | ✓ |
| FR-P7 | Workflow KYB complet | Epic 3 — Story 3.2 | ✓ |
| FR-P8 | Screening AML/sanctions continu | Epic 3 — Stories 3.1, 3.5 | ✓ |
| FR-P9 | User↔Company | Epic 2 — Stories 2.4, 2.5 | ✓ |
| FR-P10 | Rétention + WORM | Epic 3 — Story 3.5 | ✓ |
| FR-P11 | Console d'arbitrage | Epic 6 — Stories 6.1, 6.2 | ✓ |
| FR-P12 | SLA cycle de vie (expirations, auto-libération/remboursement) | Epic 5 — Stories 5.2, 5.4 (+ 7.3 config) | ✓ |
| FR-P13 | Reset mot de passe + invitations | Epic 2 — Stories 2.5, 2.6 | ✓ |
| FR-P14 | Multi-utilisateur + rôles internes | Epic 2 — Story 2.5 | ✓ |
| FR-P15 | Clés HMAC partenaire | Epic 7 — Story 7.1 | ✓ |
| FR-P16 | ADMIN non auto-attribuable | Epic 1 — Story 1.1 *(livrée)* | ✓ |
| FR-P17 | Notifications email/SMS | Epic 8 — Story 8.2 | ✓ |
| FR-P18 | Webhooks fiables + self-service | Epic 8 — Story 8.3 | ✓ |
| FR-P19 | File sur échec réseau réel | Epic 9 — Story 9.2 | ✓ |
| FR-P20 | Cache local détail transaction | Epic 9 — Story 9.3 | ✓ |
| FR-P21 | Preuve simple offline | Epic 9 — Story 9.4 | ✓ |
| FR-P22 | Plafond 20 fichiers miroité | Epic 9 — Story 9.5 | ✓ |
| FR-P23 | Virement manuel + approbation | Epic 4 — Story 4.5 | ✓ |
| FR-P24 | Barème configurable + répartition | Epic 4 (4.4/4.5/4.6/4.9) + 5.1 + 7.3 | ✓ |
| FR-P25 | Wallet d'entreprise | Epic 4 — Story 4.3 | ✓ |
| FR-P26 | USD pivot + conversions | Epic 4 (4.3→4.9) + 5.1 | ✓ |
| FR-P27 | Inscription OTP + consentement | Epic 2 — Story 2.4 | ✓ |
| FR-P28 | 2FA TOTP | Epic 2 — Story 2.6 | ✓ |
| FR-P29 | Invitation contrepartie non inscrite | Epic 5 — Stories 5.1, 5.2 | ✓ |
| FR-P30 | KYB fixe + revue manuelle SLA | Epic 3 — Stories 3.2, 3.3 | ✓ |
| FR-P31 | Gating KYB | Epic 3 — Story 3.4 (+ gardes 4.9, 5.1) | ✓ |
| FR-P32 | CoO ZLECAf premier rang | Epic 5 — Story 5.3 | ✓ |
| FR-P33 | Rétention ≥ 5 ans | Epic 3 — Story 3.5 (+ 6.3 messages, 11.6 audit) | ✓ |
| FR-P34 | Messagerie par transaction | Epic 6 — Stories 6.3, 6.4 | ✓ |
| FR-P35 | Gestion users/entreprises, garde-fous | Epic 7 — Story 7.2 | ✓ |
| FR-P36 | Supervision + vue synthétique + export | Epic 7 — Story 7.5 | ✓ |
| FR-P37 | Configuration versionnée non rétroactive | Epic 7 — Story 7.3 (+ 10.2 légal) | ✓ |
| FR-P38 | Tickets support | Epic 7 — Story 7.4 | ✓ |
| FR-P39 | Notifications in-app | Epic 8 — Story 8.4 | ✓ |
| FR-P40 | Frontière offline financière | Epic 9 — Story 9.5 | ✓ |
| FR-P41 | Landing + pages légales | Epic 10 — Stories 10.1, 10.2 | ✓ |
| FR-P42 | Ledger utilisateur | Epic 4 — Story 4.8 | ✓ |
| FR-P43 | Retraits approuvés | Epic 4 — Story 4.9 | ✓ |

**FR héritées Evidence Upload (FR-1..FR-16, livrées)** : référencées comme socle par les Epics 5 (5.3, 5.5), 6 et 9 ; exceptions FR-14→FR-P33 (Story 3.5) et NFR-6→NFR-P7 (Story 1.8) explicitement traitées. ✓

### Matrice de couverture NFR

| NFR | Couverture | Statut |
|---|---|---|
| NFR-P1 (secrets) | Story 1.2 (+ 11.2) | ✓ |
| NFR-P2 (anti-bruteforce) | Story 1.3 | ✓ |
| NFR-P3 (TLS/HSTS/CSP) | Story 1.4 | ✓ |
| NFR-P4 (CORS/Swagger) | Story 1.5 (+ 11.3) | ✓ |
| NFR-P5 (mots de passe/JWT) | Story 1.6 (+ 2.6) | ✓ |
| NFR-P6 (chiffrement au repos) | Story 1.7 | ✓ |
| NFR-P7 (antivirus) | Story 1.8 | ✓ |
| NFR-P8 (hygiène de session) | Story 1.9 | ✓ |
| NFR-P9 (anti-énumération) | Story 1.10 + réutilisée dans ~12 stories | ✓ |
| NFR-P10 (idempotence rejeu) | Story 9.1 (fondation Epic 9, étendue au financier en 4.6) | ✓ |
| NFR-P11 (CI/CD) | Story 11.1 | ✓ |
| NFR-P12 (SBOM/scan) | Story 11.1 | ✓ |
| NFR-P13 (observabilité) | Story 11.4 | ✓ |
| NFR-P14 (backup/restore) | Story 11.5 | ✓ |
| NFR-P15 (stack durcie) | Story 11.3 | ✓ |
| NFR-P16 (profils/config) | Story 11.2 | ✓ |
| NFR-P17 (résilience) | Story 11.6 | ✓ |
| NFR-P18 (rétention audit_logs) | Story 11.6 | ✓ |
| NFR-P19 (tests de charge) | Story 11.8 | ✓ |
| NFR-P20 (E2E offline→online) | Story 11.7 | ✓ |
| NFR-P21 (dette de tests) | Story 11.7 | ✓ |
| NFR-P22 (PCI-DSS) | Stories 4.2, 4.4 | ✓ |
| NFR-P23 (localisation données) | Story 11.3 (décision par corridor) + signalée en 3.1/4.2 | ✓ |
| NFR-P24 (i18n) | Story 2.1 (fondation) + propagée partout | ✓ |
| NFR-P25 (ségrégation fonds) | Stories 4.1, 4.3 (invariant + rapprochement) + 11.4 (alerte) | ✓ |

### Exigences manquantes

Aucune exigence sans couverture. Observations mineures (n'invalident pas la couverture) :

1. **FR-P5 « spread »** : le taux figé et la devise de règlement sont couverts (Story 4.6) ; le terme « spread » n'apparaît dans aucun critère d'acceptation. Sous le régime USD pivot (FR-P26), le spread est de fait porté par le PSP aux frontières — cohérent, mais si un spread plateforme était attendu comme source de revenu, il faudrait l'expliciter (Story 4.1/ADR comptable serait l'endroit).
2. **Réciproque propre** : aucun FR présent dans les epics qui serait absent du PRD — la Coverage Map du document et le texte des stories sont alignés.

### Statistiques de couverture

- FR du PRD : **43** — couverts par les epics : **43** → **100 %**
- NFR du PRD : **25** — couverts : **25** → **100 %**
- FR héritées Evidence Upload : 16/16 rattachées (socle livré + exceptions traitées)

## Évaluation de l'alignement UX

### Statut de la documentation UX

**Trouvée et complète** : `ux-designs/ux-Escrow_claude-2026-07-24/DESIGN.md` (identité, tokens, composants — status: final) + `EXPERIENCE.md` (spine d'expérience : IA des 3 espaces, patterns d'états, parcours UJ-1/2/3/litige — status: final). Les deux sourcent explicitement le PRD production et déclarent sa préséance.

### Alignement UX ↔ PRD

**Solide.** Points vérifiés :
- Architecture d'information : chaque FR-P du périmètre MVP atteint une surface et chaque surface est atteinte par un parcours (« fermeture de surface » revendiquée par EXPERIENCE.md et vérifiée sur les tables d'IA : espace public, 14 surfaces client, console arbitre, 8 surfaces back-office).
- Les parcours UJ-1 (onboarding Amina), UJ-2 (fil rouge), UJ-3 (Nadia) et UJ-litige correspondent aux parcours PRD §5, protagonistes et climax compris.
- Le vocabulaire est verrouillé sur le glossaire PRD §3 (dépôt de fonds ≠ versement de preuve, etc.).
- Les garde-fous PRD sont répercutés : NFR-P22 (jamais de saisie carte, parcours PSP hébergé), FR-P35 (pas d'impersonation ni ±solde), FR-P40 (frontière offline financière), NFR-P24 (i18n EN/FR par clés), rejets toujours motivés avec action de reprise (FR-P23/P30/P43).
- Les anti-patterns des maquettes EscrowLab rejetés par le PRD sont explicitement listés comme rejetés côté UX.

**Écarts relevés (mineurs) :**
1. **« Brouillons de messages » en file offline** — EXPERIENCE.md (§Offline, tagué `[ASSUMPTION]`, repris en UX-DR44 dans epics.md) étend le périmètre offline au-delà de FR-P40 (qui liste : consultation, preuves, litige). Aucune story ne l'implémente (les stories 6.3/6.4 et 9.x n'en parlent pas). À trancher : ratifier (et l'affecter à une story) ou abandonner l'hypothèse. Non bloquant.
2. **Étiquetage** : EXPERIENCE.md §State Patterns attribue le « rejeu idempotent » à FR-P20 (qui est le cache local) — l'idempotence est NFR-P10. Coquille sans conséquence : epics.md mappe correctement NFR-P10 → Story 9.1.
3. Langue par défaut EN `[ASSUMPTION]` — le PRD ne fixe pas de défaut ; hypothèse cohérente avec les corridors anglophones, correctement taguée.

### Alignement UX ↔ Architecture

**Constat central de cette évaluation : l'ARCHITECTURE-SPINE est d'altitude « feature » (Evidence Upload, 15–17 juil.), antérieure au PRD production et au contrat UX.**

Ce que le spine couvre et qui soutient l'UX :
- File offline IndexedDB atomique + réconciliation transitoire/permanent (AD-9, AD-10) ← UX-DR44/45, écran offline-reject conservé.
- Zone de versement de preuve, validation stricte, restitution `attachment` (AD-7) ← UX-DR15.
- Machine à états et fenêtres fondées sur l'état (AD-2) ← code couleur des états UX-DR2, verrou terminal UX-DR31.
- Audit même transaction (AD-5), anti-IDOR centralisé (AD-3), HMAC partenaire (AD-8) — socle des exigences transverses UX.

Ce que le spine **ne couvre pas** (nouveau périmètre production) :
- Wallet, grand livre, cantonnement (Epic 4) — aucun invariant comptable dans le spine actuel.
- Intégrations PSP (parcours hébergé, webhooks), KYB/AML, OTP/2FA, notifications, messagerie.
- Fondation UI : tokens, i18n, layout 3 espaces (le choix éventuel d'une lib UI est explicitement délégué à l'architecture par DESIGN.md — point ouvert, les tokens servant de contrat).

**Ce déficit d'altitude est reconnu et outillé par le backlog** : les spikes AR-P1..P6 sont des stories en tête de leurs epics (3.1, 4.1, 4.2, 8.1, 11.2, 11.3) produisant des ADR, et la Story 4.1 exige explicitement l'**amendement du SPINE** avec les invariants comptables avant tout code financier. Les invariants hérités (machine à états, AuditService, HmacSigner) sont déclarés read-only dans epics.md.

### Avertissements

- ⚠️ **L'architecture n'est pas encore au niveau du PRD production** — c'est un état assumé, pas un oubli : la couverture passe par les 6 spikes ADR + amendement du spine (Story 4.1). Risque résiduel : si les stories d'implémentation démarrent avant la conclusion de leur spike d'epic, elles reposeraient sur des hypothèses non tranchées. Le séquencement (« piste zéro » : AR-P2, AR-P1, AR-P3 en avance) mitige ce risque correctement.
- ⚠️ Spring Boot 3.3.5 sur ligne EOL (noté « Deferred » au spine) — dette transverse non rattachée à une story du nouveau backlog (l'Epic 11 ne la mentionne pas). À planifier.

## Revue de qualité des epics et stories

### Valeur utilisateur des epics

| Epic | Verdict |
|---|---|
| E2 Onboarding, E3 KYB, E4 Wallet, E5 Transaction, E6 Litige, E7 Back-office, E8 Notifications, E9 Offline, E10 Site public | ✓ Valeur utilisateur nette, titres et goals orientés résultat |
| E1 Sécurité | ~ Borderline technique, mais cadré « confiance utilisateur » avec un invariant vérifiable par story ; P0 stop-ship hérité — dérogation assumée et justifiée |
| E11 Opérabilité | ~ Epic technique (aucun FR, porte NFR-P11..P21/P23) ; persona « exploitant », condition du lancement commercial — dérogation assumée, piste parallèle |

### Indépendance des epics

Chemin critique 2 → 3 → 4 → 5 : chaque epic ne consomme que les sorties des précédents ; E1 et E11 en pistes parallèles ; 6/7/8 après 5. **Aucune dépendance circulaire ni dépendance d'un epic vers un epic futur détectée.** Le composant « file opérateur » naît en 3.3 et est réutilisé (4.5, 4.9, 7.4, 7.5) — dépendances arrière propres, réutilisation imposée (pas d'implémentations parallèles).

Vérifications ponctuelles : la cloche (Epic 8) apparaît en 2.3 comme emplacement + écrans « à venir » sans lien mort (pas une dépendance avant) ; la garde KYB (3.4) est conçue extensible pour les « endpoints financiers futurs » sans les requérir ; 5.2 consomme Epic 4 (arrière) ; 7.2 (écriture compensatoire) consomme le grand livre d'Epic 4 (arrière, E7 après E5).

### Dimensionnement et structure des stories

- **Critères d'acceptation** : Given/When/Then systématique, testables, chemins d'erreur et concurrence couverts (preuves Testcontainers exigées sur les invariants financiers : solde jamais négatif 4.3, double crédit 4.5/4.7, réservation retrait 4.9, idempotence 9.1). Qualité remarquable et homogène.
- **Timing des migrations DB** : chaque story crée ses tables par migration Flyway dédiée (2.4, 2.5, 2.6, 4.3, 11.6…) — aucune story « créer tout le schéma d'avance ». ✓
- **Brownfield** : pas de starter template (ratifié par le spine) ; les stories étendent l'existant (AuthService/AuthView, brique upload, machine à états, AuditService) sans réécrire. CI en piste zéro (11.1-lite dès lundi). ✓
- **Traçabilité** : chaque AC cite ses FR-P/NFR-P/UX-DR/AD — exemplaire.

### Constats par sévérité

#### 🔴 Violations critiques
Aucune.

#### 🟠 Points majeurs (à traiter avant ou pendant le sprint concerné)
1. **Rôle arbitre sans mécanisme d'octroi.** La console (6.1) suppose un « rôle arbitre octroyé par la plateforme uniquement (cohérent FR-P16) », mais aucune story ne crée ce mécanisme : ni bootstrap (pattern Story 1.1), ni octroi par l'ADMIN dans la gestion des utilisateurs (7.2 n'a pas d'AC d'attribution de rôle). Le layout 2.3 route les 3 espaces par rôle — le rôle arbitre doit exister. **Recommandation** : ajouter un AC à 7.2 (attribution/révocation de rôles plateforme motivée et auditée) ou étendre le bootstrap serveur ; à trancher avant l'Epic 6, idéalement dès 2.3.
2. **Story 10.2 dépend durement de 7.3** (pages légales servies par la configuration produit versionnée) alors que le séquencement déclare l'Epic 10 « flexible ». Si E10 est tiré avant 7.3, 10.2 est bloquée ; de plus 2.4 (consentement horodaté) référence des documents légaux qui n'auront de versionnage qu'en 10.2/7.3 — le consentement des premiers inscrits pointera vers des textes non versionnés. **Recommandation** : contraindre « 10.2 après 7.3 » dans le séquencement et préciser en 2.4 la source des textes légaux d'amorçage.
3. **Couplage temporel inter-pistes 11.4 ↔ Epic 4.** 11.4 exige que la règle d'alerte de ségrégation (NFR-P25) soit « en place avant la mise en service du circuit financier » — or l'Epic 11 est une piste parallèle sans jalon synchronisé avec l'Epic 4. **Recommandation** : faire de « 11.4 livrée » un critère d'entrée explicite de la mise en service (même sandbox→staging) du circuit financier dans le plan de sprint.

#### 🟡 Points mineurs
1. Stories fondation 2.1–2.3 formulées « As a développeur frontend » — écart au canon « user story », mais délibéré, borné (3 stories), avec démo interne et tests ; sans elles, chaque story d'écran réinventerait le socle. Dérogation acceptable.
2. Stories spikes (3.1, 4.1, 4.2, 8.1 partiellement, 11.2/11.3 en ouverture) livrant des ADR sans code produit — dérogation assumée, gates de décision correctement placés en tête d'epic.
3. Story 3.5 dense (screening AML + WORM/rétention = deux préoccupations) — candidate à scission si elle déborde en sprint.
4. UX-DR44 « brouillons de messages » hors FR-P40 sans story porteuse (déjà relevé à l'étape UX) — ratifier ou abandonner.
5. FR-P5 « spread » sans AC explicite (déjà relevé à l'étape couverture) — à expliciter dans l'ADR comptable (4.1) si un spread plateforme est attendu.
6. Dette Spring Boot EOL (Deferred du spine) non rattachée à l'Epic 11.

---

## Mise à jour post-architecture (2026-07-24, soirée)

L'évaluation a été **interrompue après l'étape 5** à la demande d'Oscard pour exécuter `bmad-architecture` — précisément parce que les étapes 4 et 5 avaient établi que le spine (altitude feature Evidence Upload) n'était pas au niveau du PRD production. État après ce travail :

**Nouvelle architecture** : `architecture/architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md` (**status: final**, altitude initiative) — AD-13..AD-30, héritant AD-1..12 du spine Evidence Upload en read-only ; validée par un Reviewer Gate (lint 0 finding + 7 relecteurs parallèles : rubric, vérification-réalité web, adversaire, sécurité/conformité, réconciliation PRD/epics/UX — revues dans `reviews/`). Accompagnée de `SOLUTION-DESIGN.md`.

**Backlog amendé en conséquence (7 amendements appliqués à `epics.md`)** : Story 11.9 ajoutée (migrations runtime Boot 4.1 / RabbitMQ 4.2 LTS / backend objet — exigence de lancement) → **le backlog passe à 64 stories** ; AC d'octroi/révocation `ARBITRATOR` ajouté à la Story 7.2 (prérequis de 6.1, tirable en avance) ; notifications 5.2/5.4 précisées via outbox (AD-22, producteurs Epic 5 / livraison Epic 8) ; contraintes d'ordonnancement inter-pistes ajoutées au séquencement (11.4→Epic 4, 10.2→7.3, 7.2-ARBITRATOR→6.1, 11.9 bloquante avant production) ; Story 4.1 recentrée (validation du plan de comptes contre le partenaire, les invariants comptables étant fixés par le spine) ; statuts 4.5/4.9 en enums anglais (libellés i18n) ; UX-DR44 « brouillons de messages » retiré (epics.md + EXPERIENCE.md).

**Effet sur les constats antérieurs du présent rapport :**

| Constat (étapes 4-5) | Statut après architecture |
|---|---|
| ⚠️ Architecture pas au niveau du PRD production (étape 4) | ✅ **Résolu** — spine production final, gate passé |
| 🟠 Rôle arbitre sans mécanisme d'octroi | ✅ Résolu — AD-21 + AC Story 7.2 + contrainte de séquencement |
| 🟠 10.2 dépend durement de 7.3 | ✅ Résolu — contrainte explicite au séquencement |
| 🟠 Couplage temporel 11.4 ↔ Epic 4 | ✅ Résolu — critère d'entrée dur (AD-16) + contrainte au séquencement |
| 🟡 UX-DR44 brouillons offline sans story | ✅ Tranché — retiré (PRD prévaut), réintroduisible par story dédiée |
| 🟡 FR-P5 « spread » sans AC | ✅ Tranché — AD-14 : aucun spread plateforme au MVP `[ASSUMPTION]`, marge FX = PSP |
| 🟡 Dette Spring Boot EOL non rattachée | ✅ Résolu — Story 11.9 (élargie : RabbitMQ EOL + MinIO orphelin, vérifiés web) |
| 🟡 Stories fondation 2.1-2.3 « As a développeur » ; spikes ADR-only ; 3.5 dense | ➖ Dérogations assumées, inchangées (acceptées) |

**Nouveau point d'attention issu du gate (non bloquant)** : la tenabilité du report AR-P5 suppose une **région d'hébergement commune aux corridors de lancement** — à vérifier en Story 11.3 ; sinon la question multi-région remonte au spine.

## Summary and Recommendations

### Statut de préparation global

# ✅ READY

Le plan est prêt pour le cycle d'implémentation. La chaîne d'artefacts est complète et alignée : PRD production (final) → UX contractuelle (final) → **architecture production (final, gate passé)** → backlog re-dérivé et amendé (11 epics / 64 stories, couverture 100 % des 43 FR-P et 25 NFR-P, traçabilité story→FR/NFR/UX-DR/AD exemplaire).

### Problèmes critiques exigeant une action immédiate

Aucun problème critique bloquant l'entrée en implémentation. Deux vigilances de premier rang (hors logiciel ou déférées avec garde-fou) :
1. **Dépendance critique n°1 (PRD §8)** : voie réglementaire du wallet et contrat partenaire bancaire — ne bloque pas le développement (sandbox assumée) mais bloque le lancement commercial ; la conversation partenaire doit s'ouvrir dès la piste zéro comme prévu au séquencement.
2. **Discipline des contraintes d'ordonnancement inter-pistes** (11.4→Epic 4, 7.2-ARBITRATOR→6.1, 10.2→7.3, 11.9 avant production) : à matérialiser dans le sprint-status lors du sprint planning, sinon elles se perdront.

### Prochaines étapes recommandées

1. **Commit des artefacts de planification** (PRD, UX, spine production + solution design + reviews, epics.md amendé, ce rapport) — rien de tout cela n'est encore commité.
2. **`bmad-sprint-planning`** : régénérer `sprint-status` depuis le backlog amendé (64 stories), en y matérialisant les contraintes d'ordonnancement et la piste zéro (11.1-lite, spikes AR-P2/P1/P3, email partenaire bancaire).
3. **Démarrer le cycle de dev** (CS→DS→CR) sur la piste zéro ; premier candidat : Story 11.1-lite (gate CI), en parallèle des spikes.
4. Optionnel mais recommandé : `bmad-generate-project-context` (le `project-context.md` manquant est recherché par tous les skills à chaque activation).

### Note finale

Cette évaluation a relevé 3 points majeurs et 6 points mineurs à l'étape 5 — tous résolus ou explicitement assumés après l'exécution de `bmad-architecture` et l'application des 7 amendements au backlog. Aucune exigence du PRD n'est sans couverture ; aucune story ne dépend d'un travail futur non séquencé. Évaluateur : Claude (PM expert, workflow bmad-check-implementation-readiness), avec architecture validée par gate multi-relecteurs.

**Rapport clos le 2026-07-24.**
