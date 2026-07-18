---
stepsCompleted: ["step-01-validate-prerequisites", "step-02-design-epics", "step-03-create-stories"]
inputDocuments:
  - _bmad-output/implementation-artifacts/production-backlog.html
  - _bmad-output/implementation-artifacts/deferred-work.md
  - _bmad-output/planning-artifacts/architecture/architecture-Escrow-2026-07-15/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/prds/prd-Escrow-2026-07-15/prd.md
---

# Escrow ZLECAf — Découpage en Epics (Mise en production)

## Overview

Ce document décompose le **backlog de mise en production** (51 items, dérivés du re-triage du ledger `deferred-work.md` sous l'angle production + analyse d'écarts sécurité / opérabilité / métier sur le code réel) en epics et stories implémentables. Il fait suite au POC (Epics 1-5, tous livrés et validés E2E le 2026-07-18) et cadre le passage à une **vraie application destinée à la production**, développée en agile.

**Contexte brownfield** : la plateforme Spring Boot 3 (Java 21) + Vue 3 PWA existe déjà, avec Postgres, MinIO, RabbitMQ, Flyway et JWT. Aucun starter template. Les invariants de l'`ARCHITECTURE-SPINE.md` (12 AD) restent en vigueur ; la production en ajoute de nouveaux (paiement réel, secrets, déploiement).

**Deux verrous structurants** conditionnent tout le reste et doivent ouvrir le chantier :
1. **Le séquestre est fictif** — `PAY_FUNDS`/`RELEASED`/`REFUNDED` ne sont que des transitions d'enum, aucun fonds n'est immobilisé (cœur métier absent).
2. **Auto-enregistrement ADMIN** — faille vivante : n'importe qui s'octroie le rôle qui libère les fonds.

## Requirements Inventory

### Functional Requirements

**A. Cœur métier — séquestre réel des fonds** *(Epic PAY)*
- **FR-P1** — Les fonds de l'acheteur sont réellement encaissés et immobilisés via un PSP **avant** le passage à `FUNDS_LOCKED` ; l'état « fonds bloqués » reflète un cantonnement effectif.
- **FR-P2** — `RELEASED` déclenche un versement effectif au vendeur ; `REFUNDED` un remboursement effectif à l'acheteur.
- **FR-P3** — Compte de cantonnement ségrégué + grand livre en partie double auditable (solde par transaction, rapprochement).
- **FR-P4** — Une commission de plateforme est calculée, tracée et déduite du versement au release.

**B. Multi-devise cross-border** *(Epic FX)*
- **FR-P5** — Transaction bi-devise cross-border : taux de change figé à l'engagement, spread appliqué, devise de règlement définie.
- **FR-P6** — La devise est validée contre une liste ISO-4217 des devises réellement réglables.

**C. Conformité — KYC / KYB / AML** *(Epic CMP)*
- **FR-P7** — Workflow de vérification KYB des entreprises (identité légale, représentant, bénéficiaires effectifs, justificatifs, statut de vérification) requis avant transaction.
- **FR-P8** — Screening AML / listes de sanctions à l'onboarding et en continu.
- **FR-P9** — L'inscription/onboarding relie l'utilisateur à une entreprise (User↔Company).
- **FR-P10** — Rétention légale minimale + inaltérabilité (WORM / object-lock) des preuves et de l'audit.

**D. Arbitrage & cycle de vie** *(Epic ARB)*
- **FR-P11** — Console d'arbitrage dédiée : file des litiges, assignation d'arbitre, consultation des preuves, décision motivée et tracée (remplace le bouton générique).
- **FR-P12** — Délais/SLA sur les transactions : auto-remboursement si non-expédition, auto-release après X jours sans litige, expiration d'une transaction non financée.

**E. Comptes, rôles & onboarding partenaire** *(Epics NOTIF / PART / SEC)*
- **FR-P13** — Réinitialisation de mot de passe et invitation d'utilisateur.
- **FR-P14** — Multi-utilisateur par entreprise avec rôles internes (gestion des membres).
- **FR-P15** — Provisioning des clés HMAC partenaire via UI/API admin sécurisée : générer, afficher une fois, révoquer, roter — auditable.
- **FR-P16** — Le rôle ADMIN (arbitre) est octroyé par la plateforme uniquement ; jamais auto-déclaré à l'inscription.

**F. Notifications** *(Epic NOTIF)*
- **FR-P17** — Notifications utilisateur (email/SMS) sur les transitions clés : fonds reçus, expédition, litige, résolution.
- **FR-P18** — Livraison webhook fiable (retry + DLQ + journal), scopée au tenant, secret généré côté serveur.

**G. Complétude hors-ligne** *(Epic OFF — reprise du ledger Cluster 2-3)*
- **FR-P19** — Un échec réseau (pas seulement `navigator.onLine`) déclenche la mise en file, sans perte de binaire (trancher le risque double-soumission).
- **FR-P20** — Cache local du détail de transaction hors-ligne : le litige en attente reste visible après rechargement.
- **FR-P21** — Le dépôt de preuve simple (hors litige) fonctionne hors-ligne, comme l'ouverture de litige.
- **FR-P22** — Le plafond serveur de 20 fichiers/dépôt est miroité côté front (échec immédiat corrigeable, pas 400 au rejeu).

### NonFunctional Requirements

**Sécurité** *(Epic SEC / DATA)*
- **NFR-P1** — Aucun secret en dur ; secrets externalisés + gestion de secrets ; échec au démarrage si une var critique manque (pas de repli sur un défaut public).
- **NFR-P2** — Rate-limiting / anti-bruteforce sur `/auth/login` et `/register`.
- **NFR-P3** — TLS/HTTPS de bout en bout + HSTS + en-têtes de sécurité (CSP, X-Frame-Options).
- **NFR-P4** — CORS restreint à une allowlist ; Swagger/OpenAPI fermés en production.
- **NFR-P5** — Politique de mot de passe renforcée + révocation/refresh du JWT.
- **NFR-P6** — Chiffrement au repos des preuves (MinIO) et des secrets (HMAC partenaire, webhook).
- **NFR-P7** — Scan anti-malware (AV) des fichiers uploadés avant stockage.
- **NFR-P8** — Hygiène de session sur appareil partagé : `logout()` vide la file offline et réinitialise les stores ; file scopée par utilisateur (pas de rejeu inter-user) ; 403 nu traité comme session expirée.
- **NFR-P9** — Réponses 403/404 uniformisées (pas d'oracle d'énumération de transactions).
- **NFR-P10** — Idempotence du rejeu de la file offline (`Idempotency-Key`) — aucun doublon sur réseau instable.

**Opérabilité** *(Epic OPS / QA)*
- **NFR-P11** — Pipeline CI/CD : build + tests (backend + frontend) + gate avant merge.
- **NFR-P12** — Scan de vulnérabilités / SBOM (dépendances + images).
- **NFR-P13** — Observabilité : métriques Prometheus, logs structurés + corrélation de requêtes, tracing, alerting.
- **NFR-P14** — Sauvegarde/restauration Postgres + MinIO, procédure de restore testée.
- **NFR-P15** — Stack réellement déployable : `restart`, limites CPU/mémoire, consoles d'admin non exposées, reverse-proxy/TLS, orchestration.
- **NFR-P16** — Profils multi-environnements (dev/staging/prod) + configuration externalisée (y compris API base frontend).
- **NFR-P17** — Résilience : retry/timeout sur dépendances externes (MinIO/RabbitMQ), pool Hikari dimensionné.
- **NFR-P18** — Rétention / partitionnement de `audit_logs` (croissance illimitée maîtrisée).
- **NFR-P19** — Tests de charge / performance sur les parcours chauds.
- **NFR-P20** — Tests E2E frontend (parcours PWA offline→online).
- **NFR-P21** — Résorption de la dette de tests (ledger Cluster 4 : chemin backend relatif, mock de clé, commentaire trompeur, couverture composant).

### Additional Requirements

_Décisions de niveau architecture à trancher dans un **spike de conception** avant implémentation des epics PAY/CMP/OPS. Elles amendent l'`ARCHITECTURE-SPINE.md` (nouveaux AD production)._

- **AR-P1** — Choisir le(s) PSP(s) pour l'encaissement, l'immobilisation, le versement et le remboursement en contexte ZLECAf cross-border (ex. rails mobile-money + bancaires régionaux).
- **AR-P2** — Modèle de cantonnement & comptabilité : compte ségrégué, grand livre en partie double, rapprochement, idempotence des mouvements financiers.
- **AR-P3** — Intégration d'un fournisseur KYB/KYC/AML (screening sanctions, vérification d'entreprise).
- **AR-P4** — Solution de gestion des secrets (Vault / SOPS / KMS cloud) + rotation.
- **AR-P5** — Cible de déploiement (k8s managé ou équivalent) + ingress/reverse-proxy/TLS + registre d'images versionnées.
- **AR-P6** — Fournisseur de notifications (email/SMS) adapté au contexte africain.

### FR Coverage Map

| Exigence | Epic | Rattachement |
|----------|------|--------------|
| FR-P1, FR-P2, FR-P3, FR-P4 | Epic 2 | Séquestre réel des fonds |
| FR-P5, FR-P6 | Epic 9 | Devises cross-border |
| FR-P7, FR-P8, FR-P9, FR-P10 | Epic 3 | Conformité KYC/KYB/AML |
| FR-P11, FR-P12 | Epic 4 | Arbitrage & cycle de vie |
| FR-P13, FR-P14, FR-P15 | Epic 8 | Comptes & administration |
| FR-P16 | Epic 1 | Rôle ADMIN plateforme-only |
| FR-P17, FR-P18 | Epic 7 | Notifications fiables |
| FR-P19, FR-P20, FR-P21, FR-P22 | Epic 10 | Résilience hors-ligne |
| NFR-P1→P9 | Epic 1 | Sécurité (accès, transport, session) |
| NFR-P6, NFR-P7 | Epic 1 | Protection des données au repos |
| NFR-P10 | Epic 10 | Idempotence du rejeu offline |
| NFR-P11→P18 | Epic 5 | Opérabilité & déploiement |
| NFR-P19, NFR-P20, NFR-P21 | Epic 11 | Qualité & dette technique |
| AR-P1, AR-P2 | Epic 2 | Spike paiement / cantonnement |
| AR-P3 | Epic 3 | Fournisseur KYB/AML |
| AR-P4 | Epic 1 | Gestion des secrets |
| AR-P5 | Epic 5 | Cible de déploiement |
| AR-P6 | Epic 7 | Fournisseur notifications |

## Epic List

### Epic 1 : Sécurité & protection des données
Verrouiller le contrôle d'accès et protéger les données pour que la plateforme ne soit plus exploitable — supprimer l'auto-attribution du rôle ADMIN, brider le bruteforce, chiffrer secrets et données au repos, forcer TLS, et assainir l'hygiène de session sur appareil partagé. **Tier P0 (stop-ship).**
**FRs covered:** FR-P16 · **NFRs:** NFR-P1→P10 · **AR:** AR-P4

### Epic 2 : Séquestre réel des fonds
Transformer le séquestre fictif en flux financier réel : encaisser et immobiliser les fonds via un PSP avant `FUNDS_LOCKED`, verser au vendeur au release, rembourser à l'acheteur, tenir un compte de cantonnement et un grand livre auditable, prélever la commission. **Tier P0 — ouvre un spike d'architecture (AR-P1, AR-P2).**
**FRs covered:** FR-P1, FR-P2, FR-P3, FR-P4 · **AR:** AR-P1, AR-P2

### Epic 3 : Conformité KYC/KYB/AML
Vérifier l'identité légale des entreprises et filtrer les sanctions avant tout mouvement de fonds, relier chaque utilisateur à son entreprise, et garantir la conservation légale inaltérable des preuves. **Tier P1.**
**FRs covered:** FR-P7, FR-P8, FR-P9, FR-P10 · **AR:** AR-P3

### Epic 4 : Arbitrage & cycle de vie
Doter les arbitres d'une console dédiée (file des litiges, assignation, décision motivée et tracée) et garantir qu'aucune transaction ne reste bloquée grâce aux délais/SLA (auto-release, auto-remboursement, expiration des transactions non financées). **Tier P1.**
**FRs covered:** FR-P11, FR-P12

### Epic 5 : Opérabilité & déploiement
Rendre la plateforme déployable et exploitable en production : pipeline CI/CD avec gate de tests, observabilité (métriques, logs structurés, tracing, alerting), sauvegarde/restauration, stack durcie et orchestrée, profils multi-environnements, résilience des dépendances. **Tier P1 — à démarrer tôt et en parallèle.**
**NFRs:** NFR-P11→P18 · **AR:** AR-P5

### Epic 6 : Notifications fiables
Prévenir de façon fiable acheteurs, vendeurs et partenaires des événements clés (fonds reçus, expédition, litige, résolution) par email/SMS, et fiabiliser la livraison des webhooks (retry, DLQ, scoping tenant, secret serveur). **Tier P2.**
**FRs covered:** FR-P17, FR-P18 · **AR:** AR-P6

### Epic 7 : Comptes & administration
Permettre aux organisations de gérer leurs membres et de récupérer leurs accès (invitation, reset de mot de passe, multi-utilisateur par entreprise) et onboarder proprement les partenaires (provisioning des clés HMAC via admin : générer, révoquer, roter). **Tier P2.**
**FRs covered:** FR-P13, FR-P14, FR-P15

### Epic 8 : Devises cross-border
Rendre réglable une transaction bi-devise ZLECAf : taux de change figé à l'engagement, spread, devise de règlement, et validation ISO-4217 des devises supportées. **Tier P2.**
**FRs covered:** FR-P5, FR-P6

### Epic 9 : Résilience hors-ligne complète
Compléter le hors-ligne promis par l'Epic 4 du POC : mise en file sur échec réseau réel, cache du détail hors-ligne, dépôt de preuve simple hors-ligne, miroir du plafond de fichiers, et idempotence du rejeu (aucun doublon). **Tier P2 — reprend le ledger Cluster 2-3.**
**FRs covered:** FR-P19, FR-P20, FR-P21, FR-P22 · **NFR:** NFR-P10

### Epic 10 : Qualité & dette technique
Consolider la base : tests de charge/performance, tests E2E frontend du parcours offline→online, et résorption de la dette de tests héritée du ledger (chemin backend relatif, mock de clé figée, commentaire trompeur, couverture composant). **Tier P3 — en continu une fois la CI en place.**
**NFRs:** NFR-P19, NFR-P20, NFR-P21

---

## Epic 1 : Sécurité & protection des données

Verrouiller le contrôle d'accès et protéger les données au repos. Priorité stop-ship : plusieurs failles sont exploitables aujourd'hui. Les stories sont ordonnées de la plus critique/courte à la plus lourde.

### Story 1.1 : Retirer l'auto-attribution du rôle ADMIN

As a plateforme,
I want que le rôle d'un compte soit décidé côté serveur et non par le client à l'inscription,
So that personne ne puisse s'octroyer le rôle qui arbitre les litiges et libère les fonds.

**Acceptance Criteria:**

**Given** un appel `POST /api/v1/auth/register` avec `role: "ADMIN"` dans le corps
**When** l'inscription est traitée
**Then** le compte créé reçoit un rôle non-privilégié par défaut (ex. `BUYER`)
**And** aucune valeur de rôle fournie par le client n'est prise en compte.

**Given** un besoin légitime de compte ADMIN (arbitre)
**When** un administrateur existant l'octroie via un chemin protégé (ou un seed d'amorçage documenté)
**Then** l'octroi est journalisé dans `audit_logs`
**And** l'option « Admin » disparaît de l'écran d'inscription public.

### Story 1.2 : Externaliser les secrets et échouer au démarrage si absents

As a exploitant,
I want que tous les secrets proviennent de l'environnement sans valeur de repli publique,
So that l'application ne démarre jamais silencieusement sur un secret connu.

**Acceptance Criteria:**

**Given** une variable de secret critique manquante (JWT, DB, stockage)
**When** l'application démarre
**Then** le démarrage échoue explicitement avec un message nommant la variable
**And** aucun défaut en dur (`change-me`, `minioadmin`) n'est présent dans le code ou les images.

**Given** un déploiement
**When** on inspecte la configuration
**Then** les secrets sont injectés via un mécanisme de gestion de secrets (AR-P4), pas dans le compose ni le jar.

### Story 1.3 : Brider le bruteforce sur l'authentification

As a plateforme,
I want limiter les tentatives sur `/auth/login` et `/register`,
So that les mots de passe ne puissent pas être devinés en masse ni les comptes énumérés.

**Acceptance Criteria:**

**Given** un nombre de tentatives échouées dépassant le seuil pour une IP/identifiant
**When** une nouvelle tentative arrive
**Then** elle est rejetée (429) avec back-off
**And** l'événement est observable (métrique/log).

**Given** un usage légitime sous le seuil
**When** l'utilisateur se connecte
**Then** l'authentification fonctionne normalement.

### Story 1.4 : Restreindre CORS et fermer Swagger en production

As a exploitant,
I want une allowlist d'origines et une documentation d'API fermée en production,
So that l'API ne soit pas appelable par n'importe quelle origine ni cartographiée par un tiers.

**Acceptance Criteria:**

**Given** le profil de production
**When** une requête arrive d'une origine hors allowlist
**Then** le CORS la refuse
**And** `/swagger-ui/**` et `/v3/api-docs/**` répondent 404/401.

**Given** un profil de développement
**When** on accède à Swagger
**Then** il reste disponible (comportement conditionné au profil).

### Story 1.5 : Forcer TLS et poser les en-têtes de sécurité

As a utilisateur,
I want que tout le trafic soit chiffré et durci par en-têtes,
So that mes jetons et données financières ne transitent jamais en clair.

**Acceptance Criteria:**

**Given** une requête HTTP nue en production
**When** elle atteint la couche d'entrée
**Then** elle est redirigée/refusée au profit de HTTPS
**And** les réponses portent HSTS, CSP et X-Frame-Options.

### Story 1.6 : Renforcer la politique de mot de passe et la révocation JWT

As a plateforme,
I want des mots de passe robustes et des jetons révocables,
So that un token volé ou un compte compromis puisse être neutralisé.

**Acceptance Criteria:**

**Given** une inscription/changement de mot de passe
**When** le mot de passe ne respecte pas la politique renforcée (longueur/complexité)
**Then** il est rejeté avec un message actionnable.

**Given** un logout, un changement de rôle ou une désactivation de compte
**When** une requête ultérieure présente l'ancien jeton
**Then** elle est rejetée (révocation/refresh en place).

### Story 1.7 : Uniformiser les réponses 403/404

As a plateforme,
I want une réponse indistincte entre « inexistant » et « pas à toi »,
So that un attaquant ne puisse pas énumérer les transactions existantes.

**Acceptance Criteria:**

**Given** une requête sur une transaction inexistante ou dont l'appelant n'est pas partie
**When** l'autorisation est évaluée
**Then** la réponse est identique dans les deux cas (contrat uniforme au niveau plateforme)
**And** les tests asservissant l'ancien 403 sont mis à jour.

### Story 1.8 : Assainir l'hygiène de session sur appareil partagé

As a utilisateur d'un appareil partagé,
I want que ma déconnexion efface mes données locales et que ma file ne soit rejouée que par moi,
So that les preuves d'un autre utilisateur ne soient jamais gelées à tort ni exposées.

**Acceptance Criteria:**

**Given** un `logout()`
**When** il s'exécute
**Then** la file hors-ligne IndexedDB et les stores (`escrow`, détail) sont réinitialisés.

**Given** une entrée en file créée par l'utilisateur A
**When** l'utilisateur B se connecte sur le même appareil et déclenche un `flush()`
**Then** l'entrée de A n'est pas rejouée sous le jeton de B (file scopée par utilisateur).

**Given** une réponse 403 sur session JWT expirée
**When** l'intercepteur HTTP la reçoit
**Then** il la traite comme une session expirée (purge + redirection vers `/auth`), comme le 401.

### Story 1.9 : Chiffrer les preuves et les secrets au repos

As a exploitant,
I want les binaires de preuve et les secrets stockés chiffrés,
So that un accès non autorisé à la base ou au stockage objet ne divulgue rien d'exploitable.

**Acceptance Criteria:**

**Given** un dépôt de preuve
**When** l'objet est écrit dans MinIO
**Then** il est chiffré au repos (SSE ou chiffrement applicatif).

**Given** un secret HMAC partenaire ou webhook persisté
**When** on inspecte la base
**Then** il n'est pas lisible en clair (haché/chiffré selon l'usage).

### Story 1.10 : Scanner les fichiers uploadés (anti-malware)

As a plateforme,
I want analyser chaque fichier avant stockage,
So that des preuves malveillantes ne soient ni conservées ni re-servies.

**Acceptance Criteria:**

**Given** un fichier uploadé (dépôt utilisateur ou partenaire)
**When** il est reçu
**Then** il passe un scan AV avant écriture en stockage
**And** un fichier détecté positif est rejeté (400) sans être stocké, avec trace d'audit.

---

## Epic 2 : Séquestre réel des fonds

Transformer le séquestre fictif en flux financier réel. La story 2.1 est un **spike** qui tranche l'architecture (PSP, cantonnement) et conditionne les suivantes.

### Story 2.1 : Spike — architecture de paiement & cantonnement

As a équipe,
I want trancher le(s) PSP, le modèle de compte de cantonnement et le grand livre,
So that les stories d'encaissement/versement s'appuient sur une base décidée et régulée.

**Acceptance Criteria:**

**Given** le contexte ZLECAf cross-border (mobile-money + rails bancaires régionaux)
**When** le spike se termine
**Then** un addendum d'architecture documente : PSP retenu(s), flux capture/payout/refund, modèle de compte ségrégué, schéma du grand livre, et l'idempotence des mouvements (AR-P1, AR-P2).

### Story 2.2 : Encaisser et immobiliser les fonds avant FUNDS_LOCKED

As a acheteur,
I want que mon paiement soit réellement encaissé et immobilisé,
So that l'état « fonds bloqués » corresponde à un cantonnement effectif.

**Acceptance Criteria:**

**Given** une transaction `INITIATED`
**When** l'acheteur paie via le PSP et l'encaissement est confirmé
**Then** la transaction passe `FUNDS_LOCKED` et le montant est immobilisé sur le compte de cantonnement
**And** un échec/annulation du PSP laisse la transaction `INITIATED` (aucune transition mensongère).

### Story 2.3 : Verser au vendeur (release) et rembourser l'acheteur (refund)

As a vendeur / acheteur,
I want recevoir effectivement les fonds à la résolution,
So that le cycle financier soit réellement bouclé.

**Acceptance Criteria:**

**Given** une transaction passant à `RELEASED`
**When** la transition est committée
**Then** un versement au vendeur est initié (net de commission) et son statut suivi jusqu'à confirmation.

**Given** une transaction passant à `REFUNDED`
**When** la transition est committée
**Then** un remboursement à l'acheteur est initié et suivi jusqu'à confirmation.

### Story 2.4 : Grand livre en partie double

As a exploitant,
I want une comptabilité en partie double par transaction,
So that chaque mouvement de fonds soit auditable et rapprochable.

**Acceptance Criteria:**

**Given** tout mouvement (capture, immobilisation, versement, remboursement, commission)
**When** il est enregistré
**Then** il produit des écritures équilibrées (débit/crédit) rattachées à la transaction
**And** un rapprochement expose le solde cantonné et le détecte s'il diverge.

### Story 2.5 : Prélever la commission de plateforme au release

As a plateforme,
I want calculer et prélever une commission au versement,
So that le modèle économique soit tenu et tracé.

**Acceptance Criteria:**

**Given** un `RELEASED`
**When** le versement est préparé
**Then** la commission est calculée selon le barème, déduite du versement vendeur, et écrite au grand livre.

---

## Epic 3 : Conformité KYC/KYB/AML

### Story 3.1 : Spike — fournisseur KYB/KYC/AML

As a équipe,
I want choisir et cadrer l'intégration d'un fournisseur de vérification et de screening,
So that les stories de conformité s'appuient sur un fournisseur décidé (AR-P3).

**Acceptance Criteria:**

**Given** les obligations cross-border ZLECAf
**When** le spike se termine
**Then** un addendum documente le fournisseur retenu, les données requises, les webhooks de statut et le stockage des justificatifs.

### Story 3.2 : Relier l'utilisateur à son entreprise à l'inscription

As a utilisateur B2B,
I want être rattaché à mon entreprise dès l'onboarding,
So that la vérification, la facturation et le périmètre partenaire puissent s'appliquer.

**Acceptance Criteria:**

**Given** une inscription
**When** l'utilisateur crée/rejoint une entreprise
**Then** l'association `User→Company` est persistée
**And** une transaction ne peut se créer que pour des entreprises reliées.

### Story 3.3 : Vérification KYB avant transaction

As a plateforme régulée,
I want vérifier l'identité légale d'une entreprise avant qu'elle ne mouvemente des fonds,
So that seules des contreparties vérifiées transigent.

**Acceptance Criteria:**

**Given** une entreprise au statut non vérifié
**When** un utilisateur tente de créer/financer une transaction
**Then** l'action est bloquée avec un message orientant vers la vérification.

**Given** une soumission KYB (identité légale, représentant, bénéficiaires effectifs, justificatifs)
**When** le fournisseur confirme
**Then** le statut passe « vérifié » et l'action est débloquée.

### Story 3.4 : Screening AML / sanctions

As a plateforme régulée,
I want filtrer entreprises et parties contre les listes de sanctions,
So that aucun flux ne concerne une entité listée.

**Acceptance Criteria:**

**Given** un onboarding ou une transaction
**When** le screening s'exécute
**Then** un résultat positif bloque et alerte pour revue
**And** le screening est rejoué périodiquement (surveillance continue).

### Story 3.5 : Rétention légale et inaltérabilité des preuves/audit

As a plateforme régulée,
I want conserver preuves et audit de façon inaltérable sur une durée légale,
So that la valeur probante soit garantie en cas de litige/contrôle.

**Acceptance Criteria:**

**Given** une preuve ou une entrée d'audit
**When** elle est écrite
**Then** elle est protégée en écriture (object-lock/WORM) pour la durée de rétention définie
**And** aucune suppression avant échéance n'est possible.

---

## Epic 4 : Arbitrage & cycle de vie

### Story 4.1 : File des litiges et assignation d'arbitre

As a arbitre,
I want une file des litiges assignables,
So that je traite les dossiers de façon organisée plutôt que par un bouton isolé.

**Acceptance Criteria:**

**Given** des transactions en `DISPUTED`
**When** un arbitre ouvre la console
**Then** il voit la file, peut s'assigner ou se voir assigner un dossier, et consulter ses preuves.

### Story 4.2 : Décision d'arbitrage motivée et tracée

As a arbitre,
I want rendre une décision motivée (release/refund),
So that l'issue soit justifiée et opposable.

**Acceptance Criteria:**

**Given** un dossier assigné
**When** l'arbitre tranche `RESOLVE_RELEASE`/`RESOLVE_REFUND` avec un motif obligatoire
**Then** la transition s'applique, le motif et l'auteur sont écrits en audit
**And** seul un arbitre assigné (ou habilité) peut trancher.

### Story 4.3 : Délais, SLA et auto-résolution

As a plateforme,
I want des délais qui débloquent les transactions dormantes,
So that des fonds ne restent jamais immobilisés indéfiniment.

**Acceptance Criteria:**

**Given** une transaction financée sans expédition au-delà du délai
**When** l'échéance passe
**Then** un auto-remboursement (ou une relance selon règle) est déclenché et audité.

**Given** une transaction livrée sans litige au-delà du délai
**When** l'échéance passe
**Then** un auto-release est déclenché et audité.

**Given** une transaction non financée au-delà du délai
**When** l'échéance passe
**Then** elle expire proprement.

---

## Epic 5 : Opérabilité & déploiement

### Story 5.1 : Pipeline CI avec gate de tests

As a équipe,
I want que chaque commit build et teste backend + frontend,
So that les régressions soient attrapées avant merge.

**Acceptance Criteria:**

**Given** une pull request
**When** la CI s'exécute
**Then** build + tests backend (Testcontainers) + tests frontend tournent et bloquent le merge en cas d'échec.

### Story 5.2 : Scan de vulnérabilités et SBOM

As a équipe,
I want scanner dépendances et images,
So that les CVE soient détectées tôt.

**Acceptance Criteria:**

**Given** la CI
**When** elle s'exécute
**Then** un scan (dépendances + images) produit un rapport et un SBOM, avec seuil de sévérité bloquant.

### Story 5.3 : Observabilité (métriques, logs structurés, tracing)

As a exploitant,
I want métriques, logs corrélés et tracing,
So that je détecte et diagnostique les incidents sans dépendre des clients.

**Acceptance Criteria:**

**Given** l'application en fonctionnement
**When** on interroge l'observabilité
**Then** des métriques Prometheus (JVM/HTTP/pool) sont exposées, les logs sont structurés avec un identifiant de corrélation, et les requêtes sont traçables de bout en bout.

### Story 5.4 : Sauvegarde et restauration

As a exploitant,
I want des sauvegardes Postgres + MinIO et une procédure de restore testée,
So that aucune donnée financière/légale ne soit perdue.

**Acceptance Criteria:**

**Given** un planning de sauvegarde
**When** il s'exécute
**Then** des sauvegardes Postgres + MinIO sont produites et vérifiées.

**Given** un exercice de restauration
**When** on restaure depuis une sauvegarde
**Then** l'état est reconstitué de façon cohérente (procédure documentée).

### Story 5.5 : Stack déployable et orchestrée

As a exploitant,
I want une stack de production durcie derrière un reverse-proxy TLS,
So that le déploiement soit robuste et les consoles d'admin non exposées.

**Acceptance Criteria:**

**Given** la configuration de production (AR-P5)
**When** on déploie
**Then** politiques de redémarrage, limites de ressources, absence d'exposition des consoles d'admin, reverse-proxy/TLS et orchestration sont en place
**And** les images sont versionnées et publiées dans un registre.

### Story 5.6 : Profils multi-environnements et configuration externalisée

As a équipe,
I want des profils dev/staging/prod et une config externalisée,
So that une même image serve tous les environnements sans rebuild.

**Acceptance Criteria:**

**Given** un environnement cible
**When** l'application démarre avec son profil
**Then** la configuration (y compris l'URL d'API du frontend) est injectée à l'exécution, pas figée au build.

### Story 5.7 : Résilience des dépendances et rétention de l'audit

As a exploitant,
I want des appels résilients aux dépendances et un audit borné,
So that les blips transitoires ne cassent pas les requêtes et les tables ne gonflent pas sans fin.

**Acceptance Criteria:**

**Given** un blip transitoire de MinIO/RabbitMQ
**When** un appel échoue
**Then** un retry avec timeout/back-off est appliqué avant d'échouer proprement
**And** le pool de connexions est dimensionné.

**Given** la croissance de `audit_logs`
**When** la politique de rétention s'applique
**Then** l'audit est partitionné/archivé selon la durée définie.

---

## Epic 6 : Notifications fiables

### Story 6.1 : Notifications email/SMS des transitions

As a partie (acheteur/vendeur),
I want être notifié des événements clés,
So that je réagisse à temps (paiement, expédition, litige, résolution).

**Acceptance Criteria:**

**Given** une transition clé (fonds reçus, expédition, litige, résolution)
**When** elle est committée
**Then** une notification email/SMS est envoyée aux parties concernées via le fournisseur retenu (AR-P6)
**And** l'envoi est journalisé.

### Story 6.2 : Livraison webhook fiable et sécurisée

As a partenaire,
I want une livraison de webhook fiable et légitime,
So that je ne rate aucun événement et je peux vérifier son authenticité.

**Acceptance Criteria:**

**Given** un abonnement webhook
**When** il est créé
**Then** il est scopé au tenant de l'appelant et son secret est généré côté serveur (jamais fourni par le client).

**Given** un échec de livraison
**When** il survient
**Then** la livraison est réessayée avec back-off puis routée en DLQ, avec journal de livraison (plus de perte silencieuse).

---

## Epic 7 : Comptes & administration

### Story 7.1 : Réinitialisation de mot de passe

As a utilisateur,
I want réinitialiser un mot de passe oublié,
So that je ne sois pas définitivement bloqué.

**Acceptance Criteria:**

**Given** une demande de reset pour un email connu
**When** elle est soumise
**Then** un lien/à usage unique et à durée limitée est envoyé, permettant de définir un nouveau mot de passe conforme à la politique
**And** un email inconnu ne révèle pas son inexistence.

### Story 7.2 : Invitation d'utilisateur

As a administrateur d'entreprise,
I want inviter des collaborateurs,
So that mon organisation utilise la plateforme à plusieurs.

**Acceptance Criteria:**

**Given** une invitation envoyée à une adresse
**When** l'invité l'accepte
**Then** son compte est rattaché à l'entreprise avec le rôle attribué
**And** l'invitation expire si non utilisée.

### Story 7.3 : Multi-utilisateur et rôles internes par entreprise

As a administrateur d'entreprise,
I want gérer les membres et leurs droits,
So that chacun ait le niveau d'accès approprié.

**Acceptance Criteria:**

**Given** une entreprise avec plusieurs membres
**When** l'admin gère l'équipe
**Then** il peut lister, ajouter/retirer des membres et fixer des rôles internes, avec autorisations appliquées.

### Story 7.4 : Provisioning des clés HMAC partenaire via admin

As a administrateur,
I want provisionner les clés partenaire via une interface,
So that l'onboarding partenaire soit sécurisé et auditable (fin de l'insertion SQL manuelle).

**Acceptance Criteria:**

**Given** un partenaire à onboarder
**When** l'admin crée une clé
**Then** le secret est généré côté serveur, affiché une seule fois, et l'action auditée.

**Given** une clé compromise/obsolète
**When** l'admin la révoque ou la rote
**Then** elle cesse d'authentifier immédiatement, l'historique de nonces étant préservé.

---

## Epic 8 : Devises cross-border

### Story 8.1 : Validation ISO-4217 des devises

As a plateforme,
I want n'accepter que des devises réellement supportées,
So that aucune transaction inréglable ne soit créée.

**Acceptance Criteria:**

**Given** une création de transaction
**When** la devise n'appartient pas à l'allowlist ISO-4217 supportée
**Then** la création est rejetée (400) avec un message clair.

### Story 8.2 : Règlement bi-devise cross-border

As a acheteur/vendeur de devises différentes,
I want un taux figé et une devise de règlement claire,
So that une transaction cross-border soit honorable.

**Acceptance Criteria:**

**Given** un acheteur et un vendeur de devises distinctes
**When** la transaction est engagée
**Then** un taux de change est figé (avec spread) et la devise de règlement de chaque partie est déterminée
**And** versement et remboursement utilisent le taux figé, tracé au grand livre.

---

## Epic 9 : Résilience hors-ligne complète

### Story 9.1 : Corrections front rapides (plafond fichiers + normalisation d'id)

As a utilisateur hors-ligne,
I want un retour immédiat sur les limites et un marquage fiable,
So that je ne mette pas en file une action vouée au rejet et que l'affichage optimiste s'arme.

**Acceptance Criteria:**

**Given** une sélection de plus de 20 fichiers
**When** l'utilisateur valide
**Then** le front rejette immédiatement avec un message (miroir du plafond serveur), sans mise en file.

**Given** un id de transaction venu de la route (chaîne)
**When** un événement est mis en file
**Then** la comparaison normalisée pose bien le marqueur optimiste (`String()===String()` partout).

### Story 9.2 : Idempotence du rejeu

As a plateforme,
I want que le rejeu d'une action mise en file soit idempotent,
So that une réponse perdue ne crée pas de doublon — fondation qui sécurise la mise en file sur échec réseau.

**Acceptance Criteria:**

**Given** une action rejouée portant une `Idempotency-Key`
**When** le serveur l'a déjà traitée
**Then** il renvoie le même résultat sans créer de doublon
**And** le client peut rejouer en sécurité après une réponse perdue.

### Story 9.3 : Mise en file sur échec réseau réel

As a utilisateur sur réseau instable,
I want que mes actions soient mises en file même quand `navigator.onLine` ment,
So that aucun binaire ne soit perdu sur portail captif ou réseau dégradé.

**Acceptance Criteria:**

**Given** un POST parti « en ligne » qui échoue faute de réseau
**When** l'échec est détecté
**Then** l'action et ses binaires sont mis en file pour rejeu
**And** le risque de double-soumission est neutralisé par l'idempotence de la Story 9.2.

### Story 9.4 : Cache du détail hors-ligne

As a utilisateur hors-ligne,
I want revoir un détail de transaction avec son litige en attente,
So that un rechargement ne fasse pas disparaître mon action optimiste.

**Acceptance Criteria:**

**Given** un détail consulté puis un rechargement hors-ligne
**When** la vue se monte sans réseau
**Then** le détail (et le litige en attente) est restitué depuis un cache local plutôt qu'un panneau d'erreur.

### Story 9.5 : Dépôt de preuve simple hors-ligne

As a utilisateur hors-ligne,
I want déposer une preuve simple hors litige,
So that le hors-ligne couvre tout le dépôt, comme promis par l'epic.

**Acceptance Criteria:**

**Given** un état autorisant le dépôt et l'absence de réseau
**When** l'utilisateur dépose une preuve simple
**Then** elle est mise en file et rejouée à la reconnexion, avec affichage optimiste, sans « Upload failed ».

---

## Epic 10 : Qualité & dette technique

### Story 10.1 : Résorber la dette de tests héritée du ledger

As a équipe,
I want nettoyer les fragilités de tests connues,
So that la suite soit fiable en CI et non trompeuse.

**Acceptance Criteria:**

**Given** la suite frontend
**When** elle tourne dans un checkout/CI sans `backend/`
**Then** la garde anti-dérive skip proprement avec message explicite (plus d'ENOENT).

**Given** les mocks et commentaires de tests
**When** on les revoit
**Then** la clé `TOKEN_STORAGE_KEY` n'est plus recopiée en dur, le commentaire trompeur de `SyncFailureNotice.spec.js` est corrigé, et la couverture de composant restante est comblée.

### Story 10.2 : Tests de charge / performance

As a équipe,
I want mesurer débit et latence des parcours chauds,
So that le dimensionnement (pool/JVM) ne soit plus à l'aveugle.

**Acceptance Criteria:**

**Given** un scénario de charge (dépôt, litige, download)
**When** il s'exécute
**Then** des métriques de débit/latence sont produites et comparées à des objectifs.

### Story 10.3 : Tests E2E frontend offline→online

As a équipe,
I want automatiser le parcours PWA offline→online,
So that le cœur métier hors-ligne soit validé de bout en bout.

**Acceptance Criteria:**

**Given** un scénario Playwright couvrant dépôt/litige hors-ligne puis synchronisation
**When** il s'exécute
**Then** il vérifie mise en file, rejeu, réconciliation et affichage, sans perte ni doublon.
