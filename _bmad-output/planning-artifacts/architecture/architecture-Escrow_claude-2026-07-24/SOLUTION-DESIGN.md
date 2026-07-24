# Solution Design — Escrow ZLECAf (production)

**Date :** 2026-07-24 · **Statut :** accompagne `ARCHITECTURE-SPINE.md` (contrat de build normatif — en cas d'écart, le spine prévaut) · **Public :** équipe de développement, relecteurs, futur partenaire technique.

Ce document est la lecture humaine de l'architecture : il déroule le raisonnement que le spine, volontairement terse, ne porte pas. Les identifiants `AD-n` renvoient au spine ; `AD-1..12` au spine hérité Evidence Upload (2026-07-15).

---

## 1. Situation et intention

La plateforme sort du POC : le cœur escrow (machine à états, preuves contradictoires, litige, partenaire HMAC, offline IndexedDB) est **livré et testé** (167+ tests backend, 191 tests au total avec le harnais frontend). Le PRD production du 2026-07-24 ajoute ce que le POC simulait : **de l'argent réel** (wallet d'entreprise, PSP, virements manuels, retraits approuvés, grand livre), la **conformité** (KYB/AML, WORM ≥ 5 ans, PCI-DSS), et l'**échelle produit** (back-office, notifications, i18n, site public).

L'intention architecturale tient en trois choix :

1. **Ne rien réécrire.** Le monolithe Spring en couches et la PWA Vue 3 existants sont ratifiés. Le périmètre production s'y greffe comme le POC Evidence Upload s'était greffé sur le cœur : par extension, sous invariants.
2. **Un port par fournisseur externe.** PSP, KYB/AML, email/SMS rejoignent le stockage objet derrière des interfaces hexagonales. Les six décisions fournisseurs ouvertes (AR-P1..P6) deviennent des choix d'**adaptateurs**, plus des choix d'architecture : les spikes du backlog choisissent un fournisseur, pas une structure.
3. **L'argent est un domaine comptable, pas un champ.** Aucun solde stocké ne fait foi : tout mouvement est une écriture en partie double, le solde en dérive, et l'écart avec le compte cantonné du partenaire bancaire déclenche une alarme bloquante. C'est l'invariant qui rend la plateforme auditable par un régulateur ou un partenaire bancaire — la dépendance critique n°1 du PRD.

## 2. Le circuit financier (AD-13..18, AD-28)

### 2.1 Pourquoi un grand livre en partie double

Le modèle wallet (réintégré par décision produit du 24/07) fait de la plateforme un teneur de comptes : chaque entreprise a un solde, alimenté par dépôts, débité au financement, crédité à la libération, sorti au retrait. La tentation naïve — une colonne `balance` mise à jour — casse dès qu'on doit prouver au partenaire régulé que la somme des soldes internes égale le compte cantonné. Le choix : un **grand livre en partie double append-only** (`ledger_entries` + lignes débit/crédit équilibrées), un **writer unique** (`LedgerService`), et des invariants tenus **en base** (équilibre par écriture, montants positifs, immuabilité par trigger et par privilèges du rôle DB applicatif — même une migration ne peut pas retoucher les données, seule une contre-passation motivée le peut).

Le plan de comptes minimal : un compte wallet par entreprise, un compte de séquestre par transaction, un compte de réservations de retrait, un compte de fonds en transit PSP, un compte de produits (commissions et frais), et le compte miroir du cantonnement. La **formule de rapprochement** (AD-16) : `wallets + séquestres + réservations + transit = miroir cantonnement`, contrôlée par job planifié et déclenchable, écart ⇒ alerte critique. La règle d'alerte doit être **opérationnelle avant** que le circuit financier ne serve — c'est un critère d'entrée du plan de sprint, pas une option.

### 2.2 Les mouvements types

| Mouvement | Écriture (simplifiée) | Règles clés |
|---|---|---|
| Dépôt PSP confirmé | transit PSP → wallet (− frais → produits) | webhook signé, idempotent par référence PSP (AD-17) |
| Dépôt manuel approuvé | miroir cantonnement → wallet (− frais) | **jamais sans décision opérateur persistée** (AD-28) |
| Financement | wallet acheteur → séquestre transaction (+ part frais acheteur → produits) | même transaction DB que `FUNDS_LOCKED` (AD-18) |
| Libération | séquestre → wallet vendeur (− part frais vendeur → produits) | même transaction que `RELEASED` |
| Remboursement | séquestre → wallet acheteur (montant figé USD) | aucun risque FX interne (AD-14) |
| Demande de retrait | wallet → réservations | disponible = solde − réservations (AD-15) |
| Retrait exécuté | réservations → miroir cantonnement (− frais → produits) | payout PSP confirmé d'abord ; rejet ⇒ écriture inverse |

Le **risque FX** est poussé aux frontières : dépôt converti au taux PSP du jour, retrait au taux du jour d'exécution, contrat figé en USD entre les deux. Aucun spread plateforme au MVP — si le pricing en fait un produit un jour, il naîtra comme écriture de produits, pas comme un arrondi caché.

### 2.3 Idempotence et concurrence

Un seul mécanisme sert tout le monde (AD-18) : un store serveur de clés d'idempotence partagé entre les opérations financières et le rejeu de la file offline (c'était déjà l'exigence NFR-P10 du POC ; elle est généralisée au lieu d'être dupliquée). Concurrence sur les soldes : verrou pessimiste de la ligne de compte (`SELECT … FOR UPDATE`) — deux débits concurrents dont la somme excède le solde ⇒ un seul passe, prouvé par test Testcontainers, convention du projet.

## 3. Cycle de vie escrow : étendre sans trahir (AD-19)

La matrice réelle d'`EscrowStateMachine` (lue dans le code) ne connaît ni l'auto-remboursement ni l'expiration — le POC n'en avait pas besoin. Le gate adversarial a montré qu'un « machine inchangée » naïf rendait la Story 5.4 inimplémentable sans tricher. La résolution :

- **États inchangés** (aucun nouvel état) ; **transitions ajoutées de façon bornée et nommée** : `FUNDS_LOCKED --AUTO_REFUND_TIMEOUT--> REFUNDED` et `SHIPPED --AUTO_RELEASE_TIMEOUT--> RELEASED`, déclenchées par un acteur `SYSTEM` réservé aux jobs du scheduler. La matrice whitelist reste la source unique — toute autre transition nouvelle est un conflit à remonter.
- **L'amont vit hors machine** : invitation créée/acceptée/expirée et l'expiration avant financement sont un statut porté par la transaction (`EXPIRED`), avec le même effet de verrou qu'un état terminal (dossier, preuves, messagerie en lecture seule).
- **L'arbitre entre dans la matrice** : `RESOLVE_*` s'ouvre au rôle `ARBITRATOR` (jusqu'ici ADMIN faisait office d'arbitre).

Les jobs SLA sont idempotents *par construction* : ils passent par les gardes de la machine, une exécution concurrente ne produit qu'une transition.

## 4. Conformité : KYB, rôles, chiffrement, rétention (AD-20/21/25/28/29/30)

- **Gating KYB** (AD-20) : une machine à états sur l'entreprise (`DRAFT→SUBMITTED→UNDER_REVIEW→APPROVED|REJECTED`) + une **garde serveur unique** sur toute action engageante, au même rang que l'anti-IDOR hérité (AD-3). Subtilité alignée sur le PRD : le **dépôt pré-KYB est permis** mais les fonds sont **immobilisés** (ni financement ni retrait) — le verrou AML est porté par la garde, pas par un état comptable. Le screening AML est **continu** : re-screening périodique planifié, hit post-approbation ⇒ alerte opérateur tracée.
- **Rôles** : deux axes distincts. Le rôle **plateforme** (AD-21 : `BUYER/SELLER/ADMIN/ARBITRATOR`, les deux derniers jamais auto-attribuables — extension du correctif Story 1.1) route les trois espaces de la PWA. Le rôle **interne d'entreprise** (AD-30 : `MANAGER/MEMBER` sur le lien User↔Company) gouverne l'administration d'entreprise (inviter, gérer les rôles), via une garde centralisée. Cette séparation évite le piège classique « le gestionnaire d'entreprise devient un rôle plateforme ».
- **Approbation humaine** (AD-28) : dépôts manuels et retraits exigent une décision opérateur **distincte, persistée et unique** — le crédit et le payout ne sont jamais le même geste que l'approbation. Le contrôle à quatre yeux est explicitement différé (à revisiter si un régulateur/partenaire l'impose).
- **Chiffrement au repos** (AD-29) : la liste des données sensibles est nommée (coordonnées bancaires de retrait, secrets TOTP et codes de récupération, résultats de screening, binaires de preuves/KYB, secrets HMAC/webhook) — toute nouvelle catégorie sensible doit statuer son chiffrement. Clés hors code, outil tranché au spike AR-P4.
- **WORM** (AD-25) : pas de DELETE applicatif sur preuves/KYB/screening/messages/audit ; échéance de purge calculée par enregistrement (≥ 5 ans) ; le partitionnement d'`audit_logs` doit prouver qu'il ne purge pas avant échéance.

## 5. Intégrations : les quatre ports

| Port | Contrat | Adaptateur | Décision |
|---|---|---|---|
| `EvidenceStorage` (hérité AD-6) | put/get/delete par clé opaque, cleanup rollback | MinIO S3 → **backend définitif à trancher (exigence de lancement — dépôt MinIO archivé)** | bascule triviale grâce au port |
| `PaymentGateway` (AD-17) | initier collect **hébergé**, payout, taux appliqué + taux de référence, vérifier webhook | Flutterwave **ou** Paystack | spike AR-P1 (Story 4.2, POC sandbox exigé) |
| `KybScreeningProvider` (AD-20) | screening à la soumission + re-screening périodique, stockage des résultats | fournisseur ou « manuel outillé » | spike AR-P3 (Story 3.1) |
| `NotificationSender` (AD-22/23) | envoi email/SMS depuis gabarits EN/FR versionnés par code d'événement | fournisseur adapté aux corridors africains | spike AR-P6 (Story 8.1) |

Le PCI-DSS (NFR-P22) est structurel : **aucun parcours de saisie carte n'existe dans le système** — la redirection vers le parcours hébergé du PSP est la seule voie, et le POC sandbox du spike AR-P1 doit le prouver de bout en bout.

**Notifications** : le pattern outbox (AD-22) découple la transaction métier de la livraison. Décision de séquencement assumée : la table outbox et ses producteurs naissent avec l'Epic 5 (qui promet des notifications), les relais de livraison arrivent en Epic 8 — entre les deux, les événements s'accumulent sans perte. Le paradoxe « le backend ne parle pas à l'utilisateur (AD-23) mais envoie des emails » est résolu par la frontière : les **réponses API** portent des codes machine ; l'**adaptateur de notifications** possède ses gabarits bilingues versionnés — deux catalogues, chacun gardé par la CI.

## 6. Frontend : fondation et frontières

- **Trois espaces, une PWA** (UX-DR20) : layouts distincts (client mobile-first, console et `/admin` desktop-first) routés par le rôle plateforme, sous la même auth JWT.
- **Tokens = contrat** : `DESIGN.md` fournit les tokens CSS ; Tailwind (existant) est conservé mais configuré **sur** les tokens — aucune valeur brute. Le mapping état→couleur est un module unique (UX-DR2).
- **`OperatorQueue` unique** : le composant de file opérateur naît en Story 3.3 (revue KYB) avec un contrat explicite (colonnes paramétrées, tri ancienneté, badge SLA, panneau latéral et formulaire d'action en slots, motif obligatoire au rejet) et est **réutilisé** par les dépôts manuels, les retraits, les tickets et la vue synthétique — le gate adversarial a montré que sans contrat nommé, quatre variantes incompatibles étaient garanties.
- **Offline en deux briques** (AD-27) : un **cache de lecture** horodaté (« données au… », survit à la ré-authentification, jamais d'optimisme financier) et la **file de rejeu** IndexedDB héritée, restreinte par whitelist aux actions non financières. La frontière FR-P40 est ainsi mécanique, pas disciplinaire.
- **Accessibilité et i18n** : canal d'annonces `aria-live` centralisé (transitions, upload, online/offline, rejeu), plancher WCAG 2.2 AA, catalogues EN/FR gardés par la CI (clé manquante = échec).

## 7. Dette de runtime (vérifiée web, juillet 2026)

Trois composants du socle sont en fin de vie et deviennent des **exigences de lancement** (stories à ajouter à l'Epic 11) :

| Composant | État | Cible |
|---|---|---|
| Spring Boot 3.3.5 | ligne 3.x EOL (3.5 close 30/06/2026) | 4.1.x (support jusqu'à 07/2027 ; inclut Framework 7, springdoc, hypersistence-utils) |
| RabbitMQ 3.13 | ligne 3.x hors support depuis 07/2025 | 4.2 LTS |
| MinIO (pin 09/2025) | dépôt OSS archivé 04/2026 — binaire orphelin | backend S3 définitif (AWS S3, Garage, SeaweedFS…) — bascule triviale via AD-6 |

Vite 7 / Pinia 3 : une majeure de retard côté frontend, rattrapage opportuniste dans la même fenêtre de migration, non bloquant.

## 8. Risques et conditions de tenabilité

1. **Dépendance critique n°1 (hors logiciel)** : le statut réglementaire du wallet (détention de fonds de tiers). L'architecture l'absorbe par le mode **sandbox par profil** (AD-17) : tout se développe et se démontre en sandbox PSP ; seule la production réelle attend l'avis juridique et le contrat bancaire par corridor. Critère de sélection du partenaire : banque déjà participante PAPSS (prépare le post-MVP).
2. **AR-P5 (hébergement)** : le report n'est tenable que si les corridors de lancement admettent une **région commune** conforme aux exigences de localisation (Nigeria notamment). Si le spike 11.3 conclut au multi-région, la question **remonte au spine** — le monolithe mono-déploiement est l'hypothèse à vérifier, pas un acquis.
3. **Couplage inter-pistes** : l'alerte de ségrégation (11.4) doit précéder la mise en service du circuit financier (Epic 4) ; la borne « démo pilote » (après 5.4) reste en sandbox. Ces deux contraintes d'ordonnancement doivent vivre dans le plan de sprint.
4. **Cantonnement « continu » UEMOA** : la fréquence de rapprochement est un paramètre par corridor (AD-16) — l'extension francophone (phase 2) pourra durcir la fréquence sans changer l'invariant.

## 9. Ce que ce spine change au backlog (handoff)

Sept amendements à porter sur `epics.md` (aucun ne remet en cause la structure 11 epics / 63 stories) :

1. **Nouvelle story Epic 11** : migrations de runtime (Spring Boot 4.1, RabbitMQ 4.2, backend objet définitif ; rattrapage Vite/Pinia opportuniste).
2. **Octroi du rôle ARBITRATOR** : ajouter un AC à la Story 7.2 (attribution/révocation motivée et auditée) ou une petite story dédiée — **avant l'Epic 6** (la console 6.1 présuppose le rôle).
3. **Stories 5.2/5.4** : préciser que les notifications passent par l'outbox (producteurs Epic 5, livraison Epic 8) — assumer l'accumulation ou re-séquencer 8.1/8.2 plus tôt.
4. **Plan de sprint** : contrainte « 11.4 (alerte AD-16) avant mise en service du circuit financier » ; contrainte « 10.2 après 7.3 ».
5. **Story 4.1 (spike AR-P2)** : recentrer — les invariants comptables sont posés (AD-13..16) ; le spike valide le plan de comptes contre le partenaire bancaire et documente les schémas d'écritures détaillés.
6. **Stories 4.5/4.9** : les statuts en français (`EN_RAPPROCHEMENT`, `REJETÉE`…) sont des libellés i18n — les valeurs stockées sont des enums anglais (convention).
7. **UX-DR44** (brouillons de messages hors-ligne) : ratifier (story dédiée) ou retirer de l'UX — hors whitelist AD-27 tant que non tranché.

---

*Revues du gate (7 relecteurs) : `reviews/`. Journal de décisions : `.memlog.md`. Contrat normatif : `ARCHITECTURE-SPINE.md`.*
