---
title: "PRD — Plateforme d'Escrow B2B ZLECAf (production)"
status: final
created: 2026-07-24
updated: 2026-07-24
---

# PRD — Plateforme d'Escrow B2B pour le Commerce Intra-Africain (ZLECAf)

> **Statut : production.** Ce document remplace le PRD POC (`Docs/prd_escrow_platform.md`) et devient la référence produit de la plateforme.
> Il absorbe :
> - le PRD Evidence Upload (`_bmad-output/planning-artifacts/prds/prd-Escrow-2026-07-15/prd.md`, finalisé 2026-07-15, fonctionnalité livrée) ;
> - le backlog production (`epics-production.md`, version du 2026-07-18, dont il conserve les identifiants `FR-P*`/`NFR-P*`) ;
> - l'analyse des 56 maquettes EscrowLab et des références réglementaires ZLECAf (`analyse-maquettes-et-references-zlecaf.md`) ;
> - la recherche concurrentielle de juillet 2026.
>
> **Règle de préséance : en cas d'écart entre ce PRD et un document absorbé, le présent PRD prévaut.**
> Les maquettes EscrowLab V3.0 servent de **référence fonctionnelle et UX, pas de spécification** ; la stack technique est conservée (Spring Boot, Vue 3 PWA, PostgreSQL) et les choix d'implémentation relèvent de l'architecture.

---

## 1. Vision & contexte

La ZLECAf crée le plus grand marché unique au monde en nombre de pays, mais le commerce intra-africain bute sur un déficit de confiance entre contreparties transfrontalières, la fragmentation des moyens de paiement et la lourdeur documentaire du dédouanement préférentiel (certificats d'origine, preuves de livraison).

La plateforme fournit un **séquestre (escrow) B2B** : les fonds de l'acheteur sont encaissés et immobilisés sur un compte cantonné à la création de la transaction, puis versés au vendeur uniquement lorsque la livraison est attestée — ou arbitrés sur preuves en cas de litige. Le **dossier de preuves contradictoire** (photos, documents de transport, certificat d'origine ZLECAf) est le pivot du produit : il sécurise la transaction pendant son déroulement et fonde la décision d'arbitrage.

**Espace blanc identifié** (recherche 2026-07) : aucun acteur ne combine aujourd'hui escrow digital et règlement intra-africain sous l'angle ZLECAf. Les comparables sont domestiques (TradeSafe, Vesicash et la grappe nigériane) ou tournés vers les corridors hors Afrique (Truzo). Le différenciateur de la plateforme : **l'ancrage documentaire ZLECAf** (CoO de premier rang, partenaire logistique intégré en machine-à-machine) et **l'offline-first** pour les zones à connectivité instable.

**Modèle retenu — wallet d'entreprise adossé à un partenaire régulé.** Chaque entreprise dispose d'un **solde (wallet)** alimenté par dépôts (PSP ou virement bancaire avec preuve) ; les transactions escrow sont financées depuis ce solde, les produits de libération sont crédités au solde du vendeur, qui peut demander un **retrait** vers son compte bancaire ou mobile money (approuvé par l'opérateur). Les fonds correspondants sont matériellement détenus sur un **compte cantonné tenu par un partenaire bancaire ou un établissement licencié** — la plateforme reste orchestrateur technique et tient le grand livre. Ce modèle implique un **statut réglementaire de détention de fonds de tiers** — dépendance critique n°1 du lancement, détaillée en §8.

## 2. Objectifs & métriques

**Objectifs stratégiques**
1. Instaurer la confiance : risque zéro de non-livraison pour l'acheteur, de non-paiement pour le vendeur.
2. Interopérabilité inter-pays malgré la diversité des devises et systèmes financiers.
3. Résilience : service utilisable en connectivité limitée ou instable (PWA offline-first).

**Métriques de succès (12 premiers mois)** `[ASSUMPTION — fourchettes provisoires ±50 %, à recalibrer avec les premières cohortes]`
- Volume séquestré (GMV) : **1–3 M USD** ; **100–300 transactions complétées** sur les corridors de lancement.
- Taux de complétion sans litige ≥ 90 % ; délai médian de résolution des litiges < 14 jours.
- Taux de conversion inscription → première transaction financée (activation KYB comprise) ≥ **25 %**.
- 100 % des litiges ouverts avec ≥ 1 preuve (garanti par conception, hérité du PRD Evidence Upload).

**Contre-métriques**
- Taux d'abandon au paiement (friction PSP) et taux d'échec d'upload en mobile/offline.
- Délai moyen de revue KYB (goulot d'activation) ; file d'attente d'arbitrage ; **délai de traitement des retraits** (confiance vendeur).
- Taux de virements manuels rejetés (qualité des preuves de paiement) ; **solde dormant** sur les wallets (fonds non retirés = risque réglementaire et signal de friction).
- Part des transactions expirées non financées (signal de défiance ou de friction).

## 3. Acteurs

| Rôle | Protagoniste | Enjeu propre |
|---|---|---|
| 🛒 Acheteur B2B | **Amina**, grossiste à Nairobi (KEN) | Fonds en sécurité tant que la marchandise n'est pas livrée conforme. |
| 📦 Vendeur B2B | **Thabo**, producteur à Johannesburg (ZAF) | Certitude que les fonds sont bloqués en sa faveur avant d'expédier ; être payé vite après livraison. |
| ⚖️ Arbitre | **Koffi**, arbitre plateforme | Dossier contradictoire complet pour trancher ; console dédiée. |
| 🛠️ Opérateur plateforme | **Nadia**, ops/compliance | Vérifier les entreprises (KYB), valider les virements manuels, superviser les transactions, configurer les frais. |
| 🚚 Transporteur | **Partenaire logistique** (machine) | Verser des preuves neutres horodatées via API signée HMAC — pas d'interface interactive au MVP. |

Un compte utilisateur appartient à une **entreprise** (relation User↔Company, FR-P9) ; une entreprise peut avoir plusieurs utilisateurs avec des rôles internes (FR-P14). Le rôle ADMIN est octroyé par la plateforme uniquement (FR-P16, livré).

**Glossaire minimal** (conventions de vocabulaire du document) :
| Terme | Sens |
|---|---|
| **Wallet** | Solde en USD d'une entreprise, adossé au compte cantonné partenaire. |
| **Dépôt de fonds** | Alimentation du wallet (PSP ou virement manuel). Toujours qualifié « de fonds ». |
| **Versement de preuve** | Ajout d'une pièce au dossier de preuves d'une transaction (jamais « dépôt »). |
| **Financement** | Débit du wallet acheteur qui fait passer la transaction à `FUNDS_LOCKED`. |
| **Libération** | Crédit du wallet vendeur (commission déduite) en fin de transaction. |
| **Retrait** | Sortie du wallet vers un compte bancaire/mobile money, approuvée par l'opérateur. |
| **Cantonnement** | Détention matérielle des fonds sur le compte ségrégué du partenaire régulé. |
| **Grand livre** | Comptabilité interne en partie double, source de vérité des soldes (FR-P3). |
| **Dossier de preuves** | Ensemble des pièces d'une transaction (contradictoire). |
| **Dossier KYB** | Pièces et informations de vérification d'une entreprise. |

## 4. Périmètre

**Corridors de lancement** `[ASSUMPTION]` : Kenya ↔ Afrique du Sud et Nigeria ↔ Ghana (marchés PSP matures). Extension francophone (UEMOA) en phase 2.

**Dans le périmètre MVP**
- Cycle escrow complet avec **fonds réels** : dépôt de fonds au wallet → financement de la transaction → cantonnement → crédit du vendeur → retrait.
- **Wallet d'entreprise** : solde, dépôts de fonds multi-moyens, historique de transactions (ledger), retraits avec approbation opérateur.
- Onboarding B2B : inscription email + OTP, rattachement entreprise, **KYB obligatoire** avant première transaction.
- Création de transaction par invitation (contrepartie connue, y compris non encore inscrite), répartition des frais configurable.
- Encaissement : un agrégateur PSP (cartes + mobile money + virement) **et** virement bancaire manuel avec preuve validée par l'opérateur.
- Dossier de preuves contradictoire (livré) étendu au **Certificat d'Origine ZLECAf** comme type de premier rang.
- Litige avec arbitrage outillé (console, SLA, décision motivée) et **messagerie par transaction**.
- Back-office minimum vital ; support par tickets ; notifications email/SMS ; PWA offline-first préservée et complétée.
- Landing publique simple + pages légales.

**Hors périmètre MVP — voir §10 Évolutions**
- Jalons (milestones) ; marketplace/annuaire de contreparties ; PAPSS ; SSO social ; CMS/blog ; form-builders dynamiques (KYC, retraits) ; notifications de masse ; interface interactive transporteur ; app mobile native ; OCR documentaire.

## 5. Parcours utilisateurs

Les parcours litige (UJ-litige : Amina ouvre avec preuves, Thabo se défend, Koffi tranche et verrouille le dossier) et partenaire (le transporteur pousse des preuves signées HMAC) sont **repris du PRD Evidence Upload** (finalisé, livré) et ne sont pas re-décrits ici ; ils s'enrichissent de la **messagerie par transaction** (FR-P34), qui n'existait pas alors.

**UJ-1 — Onboarding d'Amina.** `[ASSUMPTION — séquence type, à affiner en UX]`
Amina s'inscrit (email + mot de passe), reçoit un OTP 6 chiffres, vérifie son adresse. Elle crée ou rejoint l'entreprise « Nairobi Wholesale Ltd », renseigne le dossier KYB (identité légale, registre de commerce, représentant, bénéficiaires effectifs — FR-P7) et soumet. Nadia (ops) revoit le dossier sous SLA ; Amina explore l'app en lecture pendant la revue mais ne peut ni créer ni financer de transaction tant que le KYB n'est pas approuvé. Elle est notifiée de l'approbation. Elle peut activer une 2FA TOTP.

**UJ-2 — Transaction heureuse (fil rouge).**
Thabo initie une transaction : contrepartie (email d'Amina), description de la marchandise, montant en **USD** (devise pivot), conditions et délai de livraison, répartition des frais (acheteur / vendeur / 50-50). Amina reçoit l'invitation, examine, accepte : la transaction doit alors être **financée depuis son wallet**. Son solde est insuffisant : elle le **recharge** via le PSP (carte, mobile money, virement instantané) ou par virement bancaire manuel, en joignant la preuve de virement (validée par l'opérateur). Le financement débite son solde et l'état passe à `FUNDS_LOCKED` : Thabo voit la garantie et expédie (`SHIPPED`), en versant au dossier les documents (bordereau, **CoO ZLECAf**). Le transporteur partenaire pousse ses preuves de livraison. Amina confirme la réception (`DELIVERY_CONFIRMED`) — ou, sans contestation de sa part dans le délai configuré après livraison attestée, la libération est automatique (FR-P12). Le montant, commission déduite selon la répartition choisie, est **crédité au wallet de Thabo**, qui demande un **retrait** vers son compte bancaire **dans sa devise locale** ; chacun télécharge le récapitulatif. Tout du long, Amina et Thabo échangent dans la messagerie de la transaction.

**UJ-3 — Nadia opère.** `[ASSUMPTION]`
Nadia traite quatre files dans le back-office : les dossiers **KYB** en attente (approbation/rejet motivé), les **virements manuels** à rapprocher (preuve de paiement à l'appui — inspiré maquette 36), les **demandes de retrait** (contrôle des coordonnées, approbation/rejet — inspiré maquettes 37-38), et les **tickets support**. Elle supervise les transactions (recherche, filtres par état), consulte le détail de chacune, et gère la configuration : barème de commission, catégories, clés HMAC partenaires (FR-P15). Elle n'arbitre pas : les litiges sont la file de Koffi (FR-P11).

## 6. Exigences fonctionnelles

Les identifiants `FR-P1` à `FR-P22` proviennent du backlog production existant et restent normatifs (résumés ici en une ligne) ; `FR-P23+` sont introduits par ce PRD. **Amendements explicites** liés au modèle wallet : FR-P1, FR-P2 et FR-P4 sont reformulés ci-dessous (la reformulation fait foi) ; le backlog (Epic 2 notamment, écrit sous l'hypothèse « paiement par transaction ») devra être **re-dérivé de ce PRD** — le wallet n'y a aujourd'hui aucune story.

### A. Cœur séquestre & fonds réels

**Cycle de vie de référence** : les états existants `INITIATED` → `FUNDS_LOCKED` → `SHIPPED` → `RELEASED` (branches `DISPUTED` → `RELEASED`/`REFUNDED`) restent la colonne vertébrale, étendus **en amont** par les statuts d'invitation (créée / acceptée / expirée — FR-P29, FR-P12). `DELIVERY_CONFIRMED` et `RESOLVE_DISPUTE_RELEASE`/`RESOLVE_DISPUTE_REFUND` sont des **événements** (terminologie du cœur existant, qui fait foi), pas des états.

- **FR-P1** — Encaissement réel des fonds acheteur via PSP — le crédit alimente le **wallet** (FR-P25) ; l'immobilisation s'effectue par **débit du wallet** avant `FUNDS_LOCKED`.
- **FR-P2** *(amendé)* — `RELEASED` = **crédit effectif du wallet vendeur** ; `REFUNDED` = crédit effectif du wallet acheteur ; le versement bancaire externe intervient au **retrait** (FR-P43).
- **FR-P3** — Compte de cantonnement ségrégué + grand livre en partie double auditable, rapprochement par transaction.
- **FR-P4** *(amendé)* — Commission calculée et tracée ; la part vendeur est **déduite du crédit de libération**, la part acheteur **prélevée au financement**, selon la répartition choisie (FR-P24).
- **FR-P23** — **Virement bancaire manuel** comme moyen de dépôt : l'acheteur déclare le virement et joint une preuve (référence + document) ; l'opérateur rapproche et approuve/rejette ; l'approbation crédite le wallet. Aucun crédit sans validation humaine.
- **FR-P24** — **Barème de frais configurable** par l'opérateur : pourcentage dégressif par tranche de montant `[ASSUMPTION : ~1–3 %, calibrage avant lancement]`, avec minimum fixe ; la **répartition** (acheteur / vendeur / 50-50) est choisie à la création et visible des deux parties avant acceptation. Frais de dépôt de fonds et de retrait par méthode (fixe + %) configurables et affichés avant confirmation.
- **FR-P25** — **Wallet d'entreprise** : chaque entreprise dispose d'un solde unique `[ASSUMPTION : un wallet par entreprise, pas par utilisateur]`, alimenté par dépôts (PSP — FR-P1 — ou virement manuel — FR-P23), débité au financement d'une transaction, crédité à la libération côté vendeur ou au remboursement côté acheteur. Le solde affiché est adossé au grand livre (FR-P3) et rapprochable du compte cantonné (NFR-P25).
- **FR-P42** — **Historique du wallet (ledger utilisateur)** : chaque mouvement (dépôt de fonds, financement, crédit de libération, remboursement, frais, retrait) est visible avec référence, horodatage, montant signé et **solde après opération** ; filtrable par type et période.
- **FR-P43** — **Retraits** : réservés aux entreprises **KYB approuvé** (FR-P31) ; demande vers compte bancaire ou mobile money (coordonnées saisies et vérifiables), limites min/max et frais par méthode, **approbation opérateur** avant exécution ; débit du solde à la demande (montant réservé), restitution en cas de rejet ; versement dans la devise locale du bénéficiaire ; SLA de traitement `[ASSUMPTION : ≤ 1 jour ouvré après demande]`.

### B. Devises
- **FR-P5** — Transaction bi-devise : taux figé à l'engagement, spread, devise de règlement définie.
- **FR-P6** — Devises validées contre liste ISO-4217 des devises réellement réglables.
- **FR-P26** — Le **USD est la devise pivot** : contrats libellés en USD et **wallets tenus en USD** au MVP `[ASSUMPTION]` ; les montants sont affichés en devise locale à titre indicatif. Articulation des conversions : dépôt de fonds converti en USD au taux courant du PSP ; montant du contrat **figé** à l'engagement (FR-P5) ; remboursement recrédité en USD au montant figé (aucun risque FX interne) ; retrait converti dans la devise locale du bénéficiaire au taux du jour d'exécution (FR-P43). Le risque de change aux frontières (dépôt de fonds, retrait) est porté par l'utilisateur `[ASSUMPTION]`.

### C. Onboarding, comptes & entreprises
- **FR-P9** — L'onboarding relie l'utilisateur à une entreprise (User↔Company).
- **FR-P13** — Réinitialisation de mot de passe et invitation d'utilisateur.
- **FR-P14** — Multi-utilisateur par entreprise avec rôles internes.
- **FR-P16** — Rôle ADMIN octroyé par la plateforme uniquement *(livré)*.
- **FR-P27** — Inscription par email + mot de passe avec **vérification OTP 6 chiffres** (renvoi limité) ; consentement aux documents légaux horodaté.
- **FR-P28** — **2FA TOTP optionnelle** (enrôlement QR, activation par OTP, codes de récupération) ; exigible à la connexion une fois activée.
- **FR-P29** — Une transaction peut être créée vers une **contrepartie non inscrite** (invitation par email) ; la transaction reste en attente d'acceptation jusqu'à inscription + KYB approuvé de la contrepartie, avec expiration (FR-P12).

### D. Conformité KYB/AML
- **FR-P7** — Workflow KYB entreprise complet (identité légale, représentant, bénéficiaires effectifs, justificatifs, statut) requis avant transaction.
- **FR-P8** — Screening AML/sanctions à l'onboarding et en continu.
- **FR-P10** — Rétention légale minimale + inaltérabilité (WORM) des preuves et de l'audit.
- **FR-P30** — Le formulaire KYB est **fixe et adapté ZLECAf** (pas de form-builder) ; la revue est **manuelle par l'opérateur** au MVP, avec motif de rejet notifié et re-soumission possible ; SLA de revue `[ASSUMPTION : ≤ 2 jours ouvrés]`.
- **FR-P31** — Tant que le KYB n'est pas approuvé : consultation seule — ni création, ni acceptation, ni financement de transaction, **ni retrait** (un dépôt de fonds reçu avant approbation reste immobilisé sur le wallet jusqu'au KYB approuvé — verrou AML aligné sur le pattern des maquettes).

### E. Preuves & documents ZLECAf
- Les FR-1 à FR-16 du **PRD Evidence Upload** (versement, types/tailles, contradictoire, retrait logique, verrou en état terminal, offline atomique, partenaire HMAC) restent normatives — fonctionnalité livrée — **à deux exceptions près** : FR-14 (rétention illimitée POC) est remplacée par FR-P33, et NFR-6 (absence d'antivirus, risque accepté POC) est levée par NFR-P7 (scan obligatoire). Les autres NFR de ce PRD restent valides.
- **FR-P32** — Le **Certificat d'Origine ZLECAf** est un type de preuve de premier rang : métadonnées dédiées (n° de certificat, autorité émettrice, date d'émission, n° « Approved Exporter » le cas échéant, états DUPLICATE / ISSUED RETROSPECTIVELY / REPLACEMENT), validité 12 mois signalée à l'affichage. La **déclaration d'origine sur facture** est acceptée comme alternative (envois ≤ 5 000 USD, ou **sans plafond pour un « Approved Exporter »** identifié par son numéro d'autorisation). Aucune validation automatique de conformité au MVP `[ASSUMPTION]` — le typage sert le dossier et l'arbitrage.
- **FR-P33** — La rétention des preuves et de l'audit est **≥ 5 ans** (exigence documentaire ZLECAf), remplaçant la « rétention illimitée POC » par une politique explicite (purge interdite avant échéance, WORM — cohérent FR-P10).

### F. Litige, arbitrage & messagerie
- **FR-P11** — Console d'arbitrage : file des litiges, assignation, preuves, décision motivée et tracée.
- **FR-P12** — SLA du cycle de vie : auto-remboursement si non-expédition, auto-libération après X jours sans litige post-livraison, expiration des transactions non financées *(étendu par ce PRD aux invitations non acceptées — FR-P29)*.
- **FR-P34** — **Messagerie par transaction** : fil unique visible de l'acheteur, du vendeur et — dès litige ouvert — de l'arbitre ; horodatée, conservée avec le dossier (même rétention que les preuves), verrouillée avec le dossier en état terminal `[ASSUMPTION : texte seul au MVP, les fichiers passent par le dépôt de preuves]`.

### G. Back-office opérateur
- **FR-P15** — Provisioning des clés HMAC partenaire (générer, afficher une fois, révoquer, assurer la rotation — auditable).
- **FR-P35** — **Gestion des utilisateurs et entreprises** : recherche, fiche détaillée (statuts de vérification, solde, transactions associées), suspension/réactivation motivée et auditée. Pas d'impersonation (« Login as User ») ni d'ajustement manuel de solde en saisie libre — toute correction passe par une **écriture compensatoire motivée, auditée et tracée au grand livre** (jamais une modification directe du solde).
- **FR-P36** — **Supervision des transactions** : liste filtrable par état/corridor/période, détail complet (fonds, preuves, messages, audit), export `[ASSUMPTION : CSV]` ; **vue synthétique d'accueil** du back-office (files en attente : KYB, dépôts de fonds manuels, retraits, litiges, tickets ; volumes clés). L'accès opérateur repose sur l'authentification et le rôle ADMIN existants (FR-P16, NFR-P2/P5) — pas de portail d'authentification distinct au MVP.
- **FR-P37** — **Configuration produit** : barème de frais (FR-P24), catégories de transactions, délais/SLA (FR-P12), textes légaux. Toute modification est versionnée et auditée ; les transactions en cours conservent leurs conditions d'origine.
- **FR-P38** — **Tickets support** : création côté utilisateur (sujet, priorité, pièces jointes — réutilise la brique upload existante), fil de réponses côté opérateur, statuts ouvert/répondu/fermé.

### H. Notifications
- **FR-P17** — Notifications email/SMS sur transitions clés (fonds reçus, expédition, litige, résolution).
- **FR-P18** — Webhooks sortants fiables (retry + DLQ + journal), scopés tenant ; l'**abonnement self-service** des partenaires (configuration des URLs de callback, hérité du PRD POC §4.3 et du cœur existant) demeure.
- **FR-P39** — Notifications **in-app** (cloche + liste) pour les mêmes transitions clés et les messages reçus `[ASSUMPTION : pas de push navigateur au MVP]`.

### I. Offline (PWA)
- **FR-P19** à **FR-P22** — File sur échec réseau réel, idempotence du rejeu, cache local du détail de transaction, versement de preuve simple hors-ligne, plafond 20 fichiers miroité côté front — inchangés.
- **FR-P40** — Le périmètre offline du MVP couvre : consultation (transactions en cache), versement de preuves, ouverture de litige (atomique, livré). Les actions **financières** (dépôt de fonds, financement, retrait, acceptation engageante) exigent une connexion `[ASSUMPTION — pas d'engagement de fonds optimiste]`. Cette formulation **remplace** l'ambition POC « initiation des actions de validation hors-ligne » (PRD POC §4.4), volontairement resserrée sur les actions non financières.

### J. Site public
- **FR-P41** — Landing page simple (proposition de valeur, CTA inscription) + pages légales (CGU, confidentialité, politique de paiement) éditables par l'opérateur (FR-P37). Explicitement hors MVP : blog/CMS, simulateur d'escrow public, formulaire de contact et newsletter (le support passe par les tickets — FR-P38).

## 7. Exigences non-fonctionnelles

`NFR-P1` à `NFR-P21` (backlog production) restent normatives :
- **Sécurité** — secrets externalisés, rate-limiting, TLS/HSTS/CSP, CORS allowlist et Swagger fermé en production, politique de mots de passe et révocation JWT, chiffrement au repos, antivirus à l'ingestion, hygiène de session, anti-énumération, idempotence du rejeu offline.
- **Opérabilité** — CI/CD, SBOM, observabilité, backup/restore testé, stack déployable, profils d'environnements, résilience, rétention d'audit, tests de charge, E2E et résorption de la dette de tests.

S'y ajoutent :

- **NFR-P22 (PCI-DSS)** — Aucune donnée carte ne transite ni n'est stockée par la plateforme : parcours de paiement **hébergés/tokenisés par le PSP** exclusivement. Le pattern « saisie carte en direct » des maquettes (14) est explicitement rejeté.
- **NFR-P23 (Données & localisation)** — Conformité aux principes UA/Malabo : minimisation, finalité, notification d'incidents ; le choix des régions d'hébergement tient compte des exigences de **localisation des données financières** des pays servis (ex. Nigeria) — décision d'architecture documentée par corridor avant lancement.
- **NFR-P24 (i18n)** — Interface bilingue **anglais + français** dès le MVP `[ASSUMPTION — corridors anglophones d'abord, mais l'i18n se pose dès la fondation]` ; architecture de traduction par clés, sans gestionnaire de langues no-code.
- **NFR-P25 (Ségrégation des fonds)** — Invariant comptable : à tout instant, la somme des fonds séquestrés en base (grand livre FR-P3) est rapprochable du solde du compte cantonné partenaire ; tout écart déclenche une alerte bloquante (lien NFR-P13).

## 8. Conformité & dépendances externes

- **Partenaire bancaire de cantonnement** : prérequis au lancement (modèle Truzo/FirstRand) — la plateforme ne détient pas les fonds en propre. C'est le **véhicule** de la dépendance critique n°1 (statut réglementaire, ci-dessous), à sécuriser par corridor.
- **PSP agrégateur** : Flutterwave ou Paystack `[décision en spike AR-P1]` — encaissement (cartes, mobile money, virement) et payouts en devise locale.
- **Fournisseur KYB/AML** : décision en spike AR-P3.
- **Décisions d'architecture ouvertes (spikes du backlog)** : AR-P1 (PSP), **AR-P2 (modèle de cantonnement & comptabilité — périmètre directement redéfini par le wallet, à recadrer en priorité)**, AR-P3 (KYB/AML), AR-P4 (gestion des secrets), AR-P5 (cible de déploiement), AR-P6 (fournisseur notifications email/SMS).
- **Cadre ZLECAf** : le produit outille la conformité documentaire (CoO, rétention 5 ans) sans se substituer aux autorités ; les annexes de référence sont un draft — veille réglementaire à maintenir (AfCFTA RoO Manual).
- **Statut réglementaire de la plateforme — dépendance critique n°1** : le wallet constitue une **détention de fonds de tiers** (monnaie électronique dans la plupart des juridictions). Deux voies par corridor : agrément propre (EMI/MMO/EME — coûteux, ex. Nigeria MMO ₦2 Mds de capital) ou **adossement à un établissement licencié** (banque/EMI partenaire portant les soldes, la plateforme opérant en agent technique — modèle recommandé au lancement `[ASSUMPTION]`). Avis juridique par pays **avant tout encaissement réel**. **[NOTE FOR PM : jalon du plan de lancement ; le choix de la voie conditionne le spike AR-P1 et le contrat partenaire.]**

## 9. Questions ouvertes & hypothèses

Les tags `[ASSUMPTION]` en ligne font foi ; les plus structurantes :
1. Corridors de lancement (KEN↔ZAF, NGA↔GHA) — à valider contre les opportunités commerciales réelles.
2. Barème de frais (~1–3 % dégressif) — calibrage pricing avant lancement.
3. USD pivot — à confronter aux attentes des premières cohortes (contrats en NGN/KES/ZAR ?).
4. Choix de l'agrégateur PSP unique (spike AR-P1) et du fournisseur KYB (AR-P3) — décisions d'architecture bloquantes pour Epics 2 et 3.
5. Délai d'auto-libération par défaut (FR-P12) `[ASSUMPTION : 7 jours après livraison attestée]`.
6. **Voie réglementaire du wallet** (§8) : agrément propre ou adossement à un établissement licencié, par corridor — **phase-blocker du lancement commercial** (pas de la conception produit) ; l'hypothèse de travail est l'adossement.
7. **[OPEN — non bloquant]** Faut-il un statut « transaction en douane » distinct de `SHIPPED` (vérification d'origine pouvant durer 6 mois, mainlevée sous caution) ? À instruire avec les premiers cas réels ; le modèle d'états actuel reste inchangé au MVP.
8. **Hypothèses d'exécution** (tags en ligne, non structurantes) : un wallet par entreprise (FR-P25), pas de validation automatique des CoO (FR-P32), messagerie texte seul (FR-P34), export CSV (FR-P36), pas de push navigateur (FR-P39), pas d'engagement de fonds hors-ligne (FR-P40), i18n EN+FR (NFR-P24), séquence d'onboarding (UJ-1), files opérateur (UJ-3), risque FX aux frontières porté par l'utilisateur (FR-P26), SLA KYB et retraits (FR-P30/P43), fourchettes de métriques (§2).

## 10. Évolutions futures (post-MVP)

1. **Jalons (milestones)** : financement et libération partiels par étape.
2. **PAPSS** : règlement panafricain en participant indirect via banque sponsor ; extension corridors francophones (UEMOA/CEMAC).
3. **Marketplace/annuaire** de contreparties vérifiées (le KYB devient un actif de confiance monnayable).
4. **Transporteur interactif** (compte, écrans) au-delà du partenaire API.
5. SSO (Google/LinkedIn), notifications push navigateur, notifications de masse.
6. CMS/blog et site vitrine complet ; form-builders dynamiques si la diversité des juridictions l'exige.
7. OCR/extraction des documents douaniers ; validation automatisée des CoO.
8. App mobile native si la PWA rencontre ses limites terrain.

---

> Détails hors-PRD (comparables et frais, réglementation par pays, correspondance maquettes → périmètre, notes PSP/PAPSS, alternatives écartées) : voir `addendum.md`.
