---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-Escrow_claude-2026-07-24/prd.md
  - _bmad-output/planning-artifacts/prds/prd-Escrow_claude-2026-07-24/addendum.md
  - _bmad-output/planning-artifacts/architecture/architecture-Escrow-2026-07-15/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/architecture/architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md
  - _bmad-output/planning-artifacts/epics-production.md
  - _bmad-output/planning-artifacts/ux-designs/ux-Escrow_claude-2026-07-24/DESIGN.md
  - _bmad-output/planning-artifacts/ux-designs/ux-Escrow_claude-2026-07-24/EXPERIENCE.md
---

# Escrow_claude - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for Escrow_claude, decomposing the requirements from the PRD, UX Design if it exists, and Architecture requirements into implementable stories.

> **Re-dérivation (2026-07-24)** : ce document remplace `epics-production.md` (2026-07-18), antérieur au PRD production finalisé. Règle de préséance : le PRD du 2026-07-24 prévaut. Le backlog précédent reste consultable pour la traçabilité ; l'Epic 1 sécurité y était en cours (Story 1.1 livrée — commit 204def7). L'ancien breakdown POC est préservé dans `epics-poc-evidence-upload.md`.

## Requirements Inventory

### Functional Requirements

**A. Cœur séquestre & fonds réels**
- FR-P1 : Encaissement réel des fonds acheteur via PSP — le crédit alimente le wallet ; l'immobilisation s'effectue par débit du wallet avant `FUNDS_LOCKED`.
- FR-P2 *(amendé)* : `RELEASED` = crédit effectif du wallet vendeur ; `REFUNDED` = crédit effectif du wallet acheteur ; versement bancaire externe au retrait (FR-P43).
- FR-P3 : Compte de cantonnement ségrégué + grand livre en partie double auditable, rapprochement par transaction.
- FR-P4 *(amendé)* : Commission calculée et tracée ; part vendeur déduite du crédit de libération, part acheteur prélevée au financement (répartition FR-P24).
- FR-P23 : Virement bancaire manuel comme moyen de dépôt de fonds : déclaration + preuve jointe, rapprochement et approbation opérateur ; aucun crédit sans validation humaine.
- FR-P24 : Barème de frais configurable (pourcentage dégressif par tranche + minimum fixe) ; répartition acheteur/vendeur/50-50 choisie à la création, visible avant acceptation ; frais de dépôt de fonds et de retrait par méthode.
- FR-P25 : Wallet d'entreprise — solde unique par entreprise, alimenté par dépôts de fonds, débité au financement, crédité à la libération/remboursement ; adossé au grand livre (FR-P3) et rapprochable du compte cantonné (NFR-P25).
- FR-P42 : Historique du wallet (ledger utilisateur) — chaque mouvement avec référence, horodatage, montant signé, solde après opération ; filtrable.
- FR-P43 : Retraits — réservés KYB approuvé ; coordonnées bancaires/mobile money, limites et frais par méthode, approbation opérateur, réservation du montant à la demande, restitution au rejet, versement en devise locale ; SLA ≤ 1 j ouvré `[ASSUMPTION]`.

**B. Devises**
- FR-P5 : Transaction bi-devise : taux figé à l'engagement, spread, devise de règlement définie.
- FR-P6 : Devises validées contre liste ISO-4217 des devises réellement réglables.
- FR-P26 : USD devise pivot — contrats et wallets en USD au MVP ; affichage indicatif en devise locale ; conversions aux frontières (dépôt de fonds, retrait) au taux courant, risque FX porté par l'utilisateur `[ASSUMPTION]`.

**C. Onboarding, comptes & entreprises**
- FR-P9 : L'onboarding relie l'utilisateur à une entreprise (User↔Company).
- FR-P13 : Réinitialisation de mot de passe et invitation d'utilisateur.
- FR-P14 : Multi-utilisateur par entreprise avec rôles internes.
- FR-P16 : Rôle ADMIN octroyé par la plateforme uniquement *(livré — Story 1.1, commit 204def7)*.
- FR-P27 : Inscription email + mot de passe avec OTP 6 chiffres (renvoi limité) ; consentement légal horodaté.
- FR-P28 : 2FA TOTP optionnelle (QR, activation OTP, codes de récupération) ; exigible à la connexion une fois activée.
- FR-P29 : Transaction vers contrepartie non inscrite (invitation email) ; en attente jusqu'à inscription + KYB approuvé ; expiration (FR-P12).

**D. Conformité KYB/AML**
- FR-P7 : Workflow KYB entreprise complet (identité légale, représentant, bénéficiaires effectifs, justificatifs, statut) requis avant transaction.
- FR-P8 : Screening AML/sanctions à l'onboarding et en continu.
- FR-P10 : Rétention légale minimale + inaltérabilité (WORM) des preuves et de l'audit.
- FR-P30 : Formulaire KYB fixe adapté ZLECAf ; revue manuelle opérateur avec motif de rejet et re-soumission ; SLA ≤ 2 j ouvrés `[ASSUMPTION]`.
- FR-P31 : Sans KYB approuvé : consultation seule — ni création, ni acceptation, ni financement, ni retrait (fonds déposés immobilisés jusqu'à approbation).

**E. Preuves & documents ZLECAf**
- FR-1 à FR-16 *(PRD Evidence Upload — livrées)* : versement de preuves, types/tailles, contradictoire, retrait logique, verrou en état terminal, offline atomique, partenaire HMAC. Exceptions : FR-14 remplacée par FR-P33 ; NFR-6 levée par NFR-P7.
- FR-P32 : Certificat d'Origine ZLECAf = type de preuve de premier rang (métadonnées dédiées, états DUPLICATE/RETROSPECTIVE/REPLACEMENT, validité 12 mois signalée) ; déclaration d'origine sur facture en alternative (≤ 5 000 USD ou Approved Exporter sans plafond) ; pas de validation automatique au MVP `[ASSUMPTION]`.
- FR-P33 : Rétention preuves + audit ≥ 5 ans (purge interdite avant échéance, WORM — cohérent FR-P10).

**F. Litige, arbitrage & messagerie**
- FR-P11 : Console d'arbitrage : file des litiges, assignation, preuves, décision motivée et tracée.
- FR-P12 : SLA cycle de vie : auto-remboursement si non-expédition, auto-libération après X jours post-livraison `[ASSUMPTION : 7 jours]`, expiration des transactions non financées et invitations non acceptées.
- FR-P34 : Messagerie par transaction — fil unique acheteur/vendeur (+ arbitre dès litige), horodatée, rétention alignée preuves, verrouillée en état terminal ; texte seul au MVP `[ASSUMPTION]`.

**G. Back-office opérateur**
- FR-P15 : Provisioning des clés HMAC partenaire (générer, afficher une fois, révoquer, rotation — auditable).
- FR-P35 : Gestion utilisateurs/entreprises : recherche, fiche détaillée, suspension/réactivation motivée ; pas d'impersonation ni d'ajustement de solde en saisie libre (écritures compensatoires auditées uniquement).
- FR-P36 : Supervision des transactions (liste filtrable, détail complet, export CSV `[ASSUMPTION]`) ; vue synthétique d'accueil (files en attente + volumes) ; accès via auth + rôle ADMIN existants.
- FR-P37 : Configuration produit (barème, catégories, SLA, textes légaux) versionnée et auditée ; non-rétroactivité sur transactions en cours.
- FR-P38 : Tickets support : création utilisateur (sujet, priorité, pièces jointes via brique upload existante), fil de réponses opérateur, statuts.

**H. Notifications**
- FR-P17 : Notifications email/SMS sur transitions clés.
- FR-P18 : Webhooks sortants fiables (retry + DLQ + journal), scopés tenant ; abonnement self-service conservé.
- FR-P39 : Notifications in-app (cloche + liste) pour transitions clés et messages ; pas de push navigateur au MVP `[ASSUMPTION]`.

**I. Offline (PWA)**
- FR-P19 : File déclenchée sur échec réseau réel, sans perte de binaire.
- FR-P20 : Cache local du détail de transaction hors-ligne.
- FR-P21 : Versement de preuve simple (hors litige) fonctionnel hors-ligne.
- FR-P22 : Plafond serveur 20 fichiers/versement miroité côté front.
- FR-P40 : Périmètre offline MVP = consultation + preuves + litige ; les actions financières exigent une connexion `[ASSUMPTION]` (remplace l'ambition POC §4.4).

**J. Site public**
- FR-P41 : Landing simple + pages légales éditables ; blog/CMS, simulateur, contact, newsletter explicitement hors MVP.

### NonFunctional Requirements

**Sécurité (backlog production, normatives)**
- NFR-P1 : Aucun secret en dur ; secrets externalisés ; échec au démarrage si variable critique manquante.
- NFR-P2 : Rate-limiting/anti-bruteforce sur `/auth/login` et `/register`.
- NFR-P3 : TLS/HTTPS bout en bout + HSTS + en-têtes de sécurité (CSP, X-Frame-Options).
- NFR-P4 : CORS allowlist ; Swagger/OpenAPI fermés en production.
- NFR-P5 : Politique de mot de passe renforcée + révocation/refresh JWT.
- NFR-P6 : Chiffrement au repos des preuves (stockage objet) et des secrets (HMAC partenaire, webhook).
- NFR-P7 : Scan anti-malware des fichiers uploadés avant stockage.
- NFR-P8 : Hygiène de session appareil partagé : logout vide la file offline + stores ; file scopée utilisateur.
- NFR-P9 : Réponses 403/404 uniformisées (anti-énumération).
- NFR-P10 : Idempotence du rejeu de la file offline (`Idempotency-Key`), aucun doublon.

**Opérabilité (backlog production, normatives)**
- NFR-P11 : Pipeline CI/CD : build + tests backend/frontend + gate avant merge.
- NFR-P12 : Scan de vulnérabilités / SBOM (dépendances + images).
- NFR-P13 : Observabilité : métriques, logs structurés + corrélation, tracing, alerting.
- NFR-P14 : Sauvegarde/restauration Postgres + stockage objet, restore testé.
- NFR-P15 : Stack réellement déployable (restart, limites ressources, consoles non exposées, reverse-proxy/TLS).
- NFR-P16 : Profils multi-environnements + config externalisée.
- NFR-P17 : Résilience : retry/timeout sur stockage/broker, pools dimensionnés.
- NFR-P18 : Rétention/partitionnement de `audit_logs`.
- NFR-P19 : Tests de charge/performance sur parcours chauds.
- NFR-P20 : Tests E2E frontend (parcours PWA offline→online).
- NFR-P21 : Résorption de la dette de tests.

**Introduites par le PRD production**
- NFR-P22 : PCI-DSS — aucune donnée carte ne transite/n'est stockée ; parcours PSP hébergés/tokenisés exclusivement.
- NFR-P23 : Données & localisation — principes UA/Malabo ; régions d'hébergement tenant compte de la localisation des données financières par corridor.
- NFR-P24 : i18n — interface bilingue EN + FR dès le MVP `[ASSUMPTION]` ; traduction par clés.
- NFR-P25 : Ségrégation des fonds — invariant comptable grand livre ↔ compte cantonné ; écart = alerte bloquante.

### Additional Requirements

**Issues de l'architecture** — deux spines gouvernent : le spine Evidence Upload (feature, livré — AD-1..12 ci-dessous) et le **spine production** (`architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md`, status final, 2026-07-24) qui ajoute **AD-13..AD-30** (grand livre partie double writer unique/solde dérivé, réservation retrait comptable, rapprochement bloquant, ports PaymentGateway/KybScreeningProvider/NotificationSender, machine à états étendue de façon bornée + acteur SYSTEM + statut amont EXPIRED, gating KYB + SUSPENDED, rôles 2 axes plateforme×interne, outbox transactionnel, contrat i18n à deux catalogues, conditions copiées à la création, WORM, messagerie, offline 2 briques, approbation humaine préalable, chiffrement au repos, autorisation interne d'entreprise). Toute story cite le spine production comme contrat.
- Brownfield : AUCUN starter template ; conventions du code existant ratifiées (package-by-layer Spring, stores Pinia, migrations Flyway V1..V5).
- Invariants hérités à respecter par toute nouvelle story : machine à états `EscrowStateMachine` ; writer d'audit unique `AuditService` (même transaction) ; `HmacSigner` constant-time.
- AD-1 : ouverture de litige composite et atomique (multipart, 1..N pièces).
- AD-2 : fenêtre de dépôt de preuves fondée sur l'état (pas l'événement).
- AD-3 : contrôle d'appartenance centralisé anti-IDOR avant toute opération.
- AD-4 : immuabilité, retrait logique avec plancher.
- AD-5 : audit dans la même transaction, payload enrichi.
- AD-6 : stockage binaire derrière le port `EvidenceStorage` (clé opaque, MinIO S3-compatible ; backend définitif différé).
- AD-7 : validation serveur stricte à l'ingestion ; restitution en `attachment`.
- AD-8 : HMAC entrant durci (clé dédiée par partenaire, nonce anti-rejeu, ±5 min).
- AD-9 : file offline IndexedDB, une entrée atomique portant le binaire.
- AD-10 : synchro avec réconciliation transitoire vs permanent.
- AD-11 : horodatage serveur source de vérité, heure client conservée en audit.
- AD-12 : intégrité `evidence_files` garantie en base.
- Spikes d'architecture — **recentrés par le spine production (2026-07-24)** : les structures sont fixées (ports, invariants comptables AD-13..16), les spikes choisissent les fournisseurs/adaptateurs : AR-P1 (adaptateur PSP + POC sandbox), AR-P2 (validation du plan de comptes contre le partenaire bancaire + schémas d'écritures détaillés), AR-P3 (fournisseur KYB/AML), AR-P4 (outil de secrets), AR-P5 (cible de déploiement & régions — condition de tenabilité : région commune aux corridors), AR-P6 (fournisseur email/SMS).
- Dépendance critique n°1 (hors périmètre logiciel, conditionne les epics financiers) : statut réglementaire wallet — adossement à un établissement licencié recommandé ; avis juridique par corridor avant tout encaissement réel.

### UX Design Requirements

Contrat UX : `ux-designs/ux-Escrow_claude-2026-07-24/` (`DESIGN.md` + `EXPERIENCE.md`, status: final, 2026-07-24).

**A. Tokens de design & implémentation CSS**
- UX-DR1 : Tokens de couleur complets en CSS custom properties (marque navy/vert recalibré, surfaces & texte, sémantique success/warning/danger/info/neutral/offline, montants signés crédit/débit) — pas de bibliothèque UI imposée.
- UX-DR2 : Code couleur sémantique STABLE du cycle de vie escrow (INITIATED=warning, FUNDS_LOCKED/SHIPPED=info, RELEASED=success, DISPUTED=danger, REFUNDED/expirée=neutral, offline=offline) — appliqué badges, timeline, filtres, back-office ; jamais réinventé par écran.
- UX-DR3 : Rampe typographique Inter en tokens (display 28/heading 20/body 15/label 13/caption 12/amount 15/amount-hero 32) ; `tabular-nums` OBLIGATOIRE sur tout montant/solde/tableau financier.
- UX-DR4 : Tokens d'espacement base 4 (4→48px, gutters 16/24) et rayons (sm 4, md 8 défaut, lg 12 cartes/modales, full badges/avatars).
- UX-DR5 : Élévation quasi nulle : cartes bordées sans ombre ; ombre légère réservée aux surfaces flottantes.
- UX-DR6 : Interdits visuels : dégradés décoratifs, > 1 accent de marque, rouge/vert sans texte, navy comme couleur d'état, vert clair EscrowLab #50DF77 (contraste rejeté).

**B. Composants réutilisables**
- UX-DR7 : Bouton (primary/danger tokenisés minHeight 44px + secondaire/ghost) ; un seul primaire par surface ; confirmations financières avec montant dans le libellé.
- UX-DR8 : Carte standard (surface-card, bordure, radius lg, padding 16).
- UX-DR9 : Carte wallet (fond navy inverse, solde amount-hero, mention « adossé au compte cantonné », actions Déposer/Retirer ; hors-ligne : dernière valeur horodatée, actions désactivées).
- UX-DR10 : Badge d'état (pilule, couleur sémantique + libellé i18n, jamais d'icône seule ; vocabulaire unique sur les 3 espaces).
- UX-DR11 : Ligne de ledger (montant signé tabulaire + solde après opération en caption ; signe et libellé portent l'info, pas la couleur seule ; clic → détail du mouvement).
- UX-DR12 : Bannière KYB persistante à 4 variantes (non soumis/en revue avec délai/rejeté avec motif + CTA/approuvé→toast).
- UX-DR13 : Bannière offline pleine largeur avec compteur d'éléments en file — SEUL indicateur global offline.
- UX-DR14 : Timeline de transaction verticale (état courant plein, futurs muted, prochaine action nommée avec responsable, échéances SLA affichées).
- UX-DR15 : Zone de versement de preuve (pointillés ; états prêt/envoi/en file/échec/versé ; multi-fichiers plafond 20 ; caméra mobile, drag-drop desktop ; variante CoO ZLECAf avec panneau de métadonnées + alerte validité 12 mois).
- UX-DR16 : File opérateur (tableau desktop, tri ancienneté, badge SLA tricolore, panneau latéral Approuver/Rejeter, motif obligatoire au rejet, avancement auto).
- UX-DR17 : Modale de confirmation financière (récapitulatif montant/frais par partie/solde après ; bouton portant le montant ; anti double-soumission).
- UX-DR18 : Messagerie de transaction (texte seul, rôles identifiés, arbitre dès litige, lecture seule en terminal, renvoi vers dossier de preuves pour fichiers).
- UX-DR19 : Cloche de notifications (badge non-lus, liste antéchronologique, navigation vers la surface concernée ; pas de push).

**C. Architecture d'information & navigation**
- UX-DR20 : Une seule PWA, trois espaces sous la même auth JWT + rôles : app client mobile-first installable, console d'arbitrage desktop-first, back-office `/admin` desktop-first (pas de portail de login distinct).
- UX-DR21 : Espace public : landing + pages légales + inscription (consentement horodaté) + OTP 6 chiffres (renvoi limité, compte à rebours) + connexion (TOTP si 2FA) + réinitialisation mot de passe.
- UX-DR22 : Navigation client 5 entrées (Accueil/Transactions/Wallet/Support/Profil) + cloche — sidebar desktop, onglets bas mobile.
- UX-DR23 : 14 surfaces app client : dashboard, liste transactions bidirectionnelle, wizard 2 étapes avec récapitulatif des frais, détail transaction (timeline + preuves + messagerie + actions contextuelles), dossier de preuves, litige, wallet + ledger filtrable, dépôt de fonds (PSP hébergé OU virement manuel), retrait, KYB (statuts + brouillon), profil & entreprise (2FA, membres, invitations), support, notifications.
- UX-DR24 : Console d'arbitrage : file des litiges (assignation, ancienneté vs SLA) + dossier de litige (contradictoire complet, décision motivée RELEASE/REFUND verrouillante).
- UX-DR25 : Back-office 8 surfaces : vue synthétique (files + compteurs SLA), file KYB, dépôts manuels (déclaration + preuve côte à côte), retraits (montant réservé, restitution au rejet), transactions (export CSV), utilisateurs & entreprises (AUCUN champ de solde éditable, pas d'impersonation), configuration (barème/catégories/SLA/légal versionnés + clés HMAC affichées UNE fois), tickets.

**D. Patterns d'états**
- UX-DR26 : Chargement : skeletons calqués sur la cible ; jamais de spinner plein écran après première peinture ; toute attente a couleur + libellé + délai annoncé.
- UX-DR27 : États vides différenciés (première fois avec CTA conditionné KYB / filtré avec reset / wallet vide).
- UX-DR28 : Erreurs : toast + relance avec saisie conservée (réseau) ; carte d'erreur avec référence d'incident (serveur) ; tout rejet affiche motif ET action de reprise.
- UX-DR29 : États métier wallet : dépôt en rapprochement hors solde disponible ; montant réservé visible au retrait ; fonds pré-KYB « immobilisés » explicites.
- UX-DR30 : Invitation en attente : expiration affichée + renvoyer ; contrepartie non inscrite signalée.
- UX-DR31 : État terminal : tout en lecture seule + « Dossier verrouillé le… » + récapitulatif téléchargeable.
- UX-DR32 : Session expirée : retour connexion avec cible conservée ; la file offline survit à la ré-authentification.
- UX-DR33 : Gating KYB global : actions engageantes visibles mais désactivées avec explication ; à l'approbation : notification + activation simultanée wallet/« Nouvelle transaction ».

**E. Primitives d'interaction**
- UX-DR34 : Deux vitesses — consulter instantané/optimiste ; engager de l'argent toujours lent volontairement (modale récap, montant dans le bouton, idempotence, jamais en un clic).
- UX-DR35 : Formulaires : validation à la volée, erreurs sous champ, saisie conservée, wizards réversibles, financiers en 1 colonne + carte de frais, KYB en brouillon avec pièces conservées après rejet.
- UX-DR36 : Interdits : scroll infini sur données financières (pagination), modales empilées, saisie de carte dans nos écrans (PSP hébergé uniquement — NFR-P22), action destructrice back-office sans motif, affordance hover-only côté client.
- UX-DR37 : Microcopie verrouillée sur le glossaire PRD (« dépôt de fonds » ≠ « versement de preuve », jamais « upload ») ; montants « 12 500,00 USD (≈ devise locale) » ; attentes toujours datées ; offline explicite.

**F. Accessibilité & i18n**
- UX-DR38 : WCAG 2.2 AA sur les 3 espaces : contrastes par tokens, état financier jamais par couleur seule, cibles ≥ 44px, clavier complet, focus-ring visible.
- UX-DR39 : `aria-live` pour transitions/upload/online-offline/rejeu ; labels + `aria-describedby` ; champ OTP acceptant le collage.
- UX-DR40 : i18n EN/FR par clés, aucune chaîne en dur, défaut EN `[ASSUMPTION]`, bascule persistée, formats localisés, libellés « EN / FR » sans drapeaux.
- UX-DR41 : Bas débit : miniatures compressées, lazy-loading, budget de poids strict (cible Android entrée de gamme en 3G).

**G. Offline & synchronisation**
- UX-DR42 : Frontière offline rappelée in situ : preuves/litige/consultation OK hors-ligne ; toute action financière désactivée avec explication (FR-P40).
- UX-DR43 : Cache de consultation horodaté « données au… » ; jamais d'optimisme financier hors-ligne.
- UX-DR44 : File IndexedDB (livrée) étendue : preuves, litige atomique ; état visible par élément (en file→envoi→confirmé/rejeté). *(Les « brouillons de messages hors-ligne », `[ASSUMPTION]` UX initiale, sont retirés — hors périmètre FR-P40, hors whitelist AD-27 ; réintroduisibles par story dédiée.)*
- UX-DR45 : Reconnexion : rejeu idempotent avec progression ; conflits notifiés, jamais d'écrasement silencieux ; écran offline-reject existant conservé.
- UX-DR46 : PWA installable : manifest, icônes, shell précaché.

**H. Responsive & plateforme**
- UX-DR47 : Client mobile-first (breakpoints 768/1024 ; onglets bas → colonne 720px → sidebar + détail 2 colonnes ; contenus max 720/960px).
- UX-DR48 : Back-office et console desktop-first (sidebar fixe, tableaux max 1280px, panneau latéral ; < 768px non optimisé).

### FR Coverage Map

- FR-P1 : Epic 4 — Encaissement PSP créditant le wallet, immobilisation par débit
- FR-P2 : Epic 4 — RELEASED/REFUNDED = crédits de wallet effectifs
- FR-P3 : Epic 4 — Cantonnement ségrégué + grand livre partie double
- FR-P4 : Epic 4 — Commission calculée/tracée, prélèvement réparti
- FR-P5 : Epic 4 — Transaction bi-devise, taux figé
- FR-P6 : Epic 4 — Devises ISO-4217 réglables
- FR-P7 : Epic 3 — Workflow KYB complet
- FR-P8 : Epic 3 — Screening AML/sanctions
- FR-P9 : Epic 2 — Rattachement User↔Company
- FR-P10 : Epic 3 — Rétention légale + WORM
- FR-P11 : Epic 6 — Console d'arbitrage
- FR-P12 : Epic 5 — SLA cycle de vie (auto-libération, expirations)
- FR-P13 : Epic 2 — Reset mot de passe + invitations
- FR-P14 : Epic 2 — Multi-utilisateur + rôles internes
- FR-P15 : Epic 7 — Provisioning clés HMAC partenaire
- FR-P16 : Epic 1 — ADMIN non auto-attribuable *(livré, Story 1.1)*
- FR-P17 : Epic 8 — Notifications email/SMS
- FR-P18 : Epic 8 — Webhooks fiables + abonnement self-service
- FR-P19 : Epic 9 — File sur échec réseau réel
- FR-P20 : Epic 9 — Cache local détail transaction
- FR-P21 : Epic 9 — Versement de preuve simple offline
- FR-P22 : Epic 9 — Plafond 20 fichiers miroité
- FR-P23 : Epic 4 — Virement manuel avec preuve + approbation
- FR-P24 : Epic 4 — Barème de frais configurable + répartition
- FR-P25 : Epic 4 — Wallet d'entreprise
- FR-P26 : Epic 4 — USD pivot + articulation conversions
- FR-P27 : Epic 2 — Inscription email + OTP + consentement
- FR-P28 : Epic 2 — 2FA TOTP optionnelle
- FR-P29 : Epic 5 — Invitation contrepartie non inscrite
- FR-P30 : Epic 3 — Formulaire KYB fixe + revue manuelle SLA
- FR-P31 : Epic 3 — Gating KYB (création/acceptation/financement/retrait)
- FR-P32 : Epic 5 — CoO ZLECAf type de preuve de premier rang
- FR-P33 : Epic 3 — Rétention ≥ 5 ans
- FR-P34 : Epic 6 — Messagerie par transaction
- FR-P35 : Epic 7 — Gestion utilisateurs/entreprises
- FR-P36 : Epic 7 — Supervision + vue synthétique
- FR-P37 : Epic 7 — Configuration produit versionnée
- FR-P38 : Epic 7 — Tickets support
- FR-P39 : Epic 8 — Notifications in-app
- FR-P40 : Epic 9 — Frontière offline (actions financières exclues)
- FR-P41 : Epic 10 — Landing + pages légales
- FR-P42 : Epic 4 — Ledger utilisateur
- FR-P43 : Epic 4 — Retraits approuvés
- FR-1..FR-16 : *(livrées — Evidence Upload ; étendues par Epics 5/9, exceptions FR-14→FR-P33, NFR-6→NFR-P7)*
- NFR-P1..P9 : Epic 1 — Sécurité (piste parallèle P0)
- NFR-P10 : Epic 9 — Idempotence du rejeu offline (fondation de la file)
- NFR-P11..P21 : Epic 11 — Opérabilité & qualité (piste parallèle)
- NFR-P22 : Epic 4 — PCI-DSS (parcours PSP hébergés)
- NFR-P23 : Epic 11 — Localisation des données (décision par corridor)
- NFR-P24 : Epic 2 — i18n EN/FR (fondation UI)
- NFR-P25 : Epic 4 — Invariant de ségrégation des fonds

## Epic List

### Epic 1 : Sécurité & protection des données *(hérité, en cours)*
Les utilisateurs confient leur argent à une plateforme durcie contre les attaques et les fuites.
**FRs covered:** FR-P16 *(livré)* ; porte NFR-P1..P10.
**Notes :** continuité de l'ancien Epic 1 (P0 stop-ship) ; Story 1.1 livrée (commit 204def7).

### Epic 2 : Onboarding & comptes d'entreprise
Un professionnel s'inscrit, vérifie son email, crée ou rejoint son entreprise, invite ses collègues avec des rôles, sécurise son compte (2FA TOTP).
**FRs covered:** FR-P9, FR-P13, FR-P14, FR-P27, FR-P28.
**Notes :** porte la fondation UI (tokens + composants de base UX-DR1..10, layout 3 espaces UX-DR20..22) et l'i18n par clés (NFR-P24, UX-DR40) — premier epic à écrans, réutilisé par tous les suivants.

### Epic 3 : Conformité KYB/AML
L'entreprise est vérifiée (formulaire ZLECAf fixe, revue opérateur sous SLA) et peut opérer légalement ; screening sanctions ; documents conservés ≥ 5 ans en WORM ; gating global tant que non approuvée.
**FRs covered:** FR-P7, FR-P8, FR-P10, FR-P30, FR-P31, FR-P33.
**Notes :** spike AR-P3 (fournisseur KYB/AML) en première story — *tirable en avance (piste zéro)* ; UX-DR12 (bannière KYB), UX-DR33 (gating). La Story 3.3 **fonde le composant « file opérateur » (UX-DR16)** réutilisé ensuite par 4.5, 4.9, 7.4 et 7.5.

### Epic 4 : Wallet & circuit financier réel
L'acheteur dépose des fonds réels (PSP hébergé ou virement manuel approuvé), finance ses transactions depuis son wallet ; le vendeur est crédité et retire vers sa banque/mobile money en devise locale ; grand livre, cantonnement, frais.
**FRs covered:** FR-P1, FR-P2, FR-P3, FR-P4, FR-P5, FR-P6, FR-P23, FR-P24, FR-P25, FR-P26, FR-P42, FR-P43 ; porte NFR-P22, NFR-P25.
**Notes :** epic volontairement consolidé (mêmes fichiers cœur : wallet, ledger) à stories strictement ordonnées ; spikes AR-P2 (cantonnement/comptabilité — recadré wallet) puis AR-P1 (PSP) en tête — *tirables en avance (piste zéro)* ; files d'approbation opérateur minimales incluses (la console complète = Epic 7), construites sur le **composant file opérateur né en 3.3** (UX-DR16, pas de réimplémentation) ; le tout fonctionne en **sandbox PSP** jusqu'à signature du partenaire bancaire (seule la production réelle en dépend) ; UX-DR9, 11, 17, 29, 34.

### Epic 5 : Transaction escrow de bout en bout
Le fil rouge UJ-2 : invitation (y compris contrepartie non inscrite), acceptation, financement, expédition avec dossier documentaire CoO ZLECAf, livraison, libération automatique ou confirmée, expirations.
**FRs covered:** FR-P12, FR-P29, FR-P32.
**Notes :** s'appuie sur le cœur escrow + preuves livré (FR-1..16) et les Epics 2-4 ; UX-DR14 (timeline), 15 (zone de preuve + variante CoO), 23 (wizard), 30, 31.

### Epic 6 : Litige, arbitrage & messagerie
Les parties dialoguent par transaction ; un litige documenté est instruit et tranché par l'arbitre dans sa console dédiée.
**FRs covered:** FR-P11, FR-P34.
**Notes :** UX-DR18 (messagerie), UX-DR24 (console) ; le socle litige+preuves est livré.

### Epic 7 : Back-office opérateur
L'opérateur gère utilisateurs et entreprises, supervise les transactions, configure le produit (frais, catégories, SLA, textes légaux, clés HMAC) et traite les tickets support.
**FRs covered:** FR-P15, FR-P35, FR-P36, FR-P37, FR-P38.
**Notes :** UX-DR16 (file opérateur), UX-DR25 (8 surfaces) ; garde-fous : pas d'impersonation, pas de ±solde libre.

### Epic 8 : Notifications & webhooks fiables
Chaque partie est prévenue au bon moment : email/SMS, in-app (cloche), webhooks partenaires avec retry/DLQ.
**FRs covered:** FR-P17, FR-P18, FR-P39.
**Notes :** spike AR-P6 (fournisseur email/SMS contexte africain) ; UX-DR19.

### Epic 9 : Offline complet *(hérité)*
La PWA tient ses promesses en réseau instable : file sur échec réel, idempotence, cache de consultation, frontière financière explicite.
**FRs covered:** FR-P19, FR-P20, FR-P21, FR-P22, FR-P40.
**Notes :** continuité du travail livré (IndexedDB, Story 4.1 POC) ; UX-DR13, 42..46.

### Epic 10 : Site public
Un prospect comprend la proposition de valeur et s'inscrit ; les pages légales sont publiées et éditables.
**FRs covered:** FR-P41.
**Notes :** UX-DR21 ; volontairement petit et tardif.

### Epic 11 : Opérabilité, qualité & déploiement *(hérité, à démarrer tôt)*
La plateforme se déploie, s'observe, se sauvegarde et se teste — condition du lancement commercial.
**FRs covered:** aucun FR ; porte NFR-P11..P21, NFR-P23.
**Notes :** spikes AR-P4 (secrets) et AR-P5 (cible de déploiement) ; piste parallèle dès maintenant.

**Séquencement** *(amendé en table ronde du 2026-07-24)* :

- **Piste zéro — démarre lundi** : Story 11.1-lite (gate CI build + tests backend/frontend sur chaque PR ; SBOM et seuils de vulnérabilités suivent le cours normal de l'Epic 11) ; **spikes AR-P2, AR-P1, AR-P3 tirés en avance de leurs epics** (stories 4.1, 4.2, 3.1 — décisions d'architecture, aucune dépendance aux epics amont) ; ouverture de la **conversation partenaire bancaire de cantonnement** (hors logiciel — dépendance critique n°1 du PRD §8, le lead time contractuel se compte en mois).
- **Chemin critique de développement** : 2 → 3 → 4 → 5 ; les Epics 1 et 11 avancent en pistes parallèles ; 6, 7, 8 après 5 ; 9 et 10 flexibles.
- **Borne « démo pilote »** : après la Story 5.4, le parcours complet Amina-paie → Thabo-est-crédité est démontrable en **sandbox PSP** — la sandbox est assumée comme mode de démonstration tant que le contrat bancaire n'est pas signé ; seule la mise en production réelle en dépend.
- **Composant « file opérateur » (UX-DR16) unique** : il naît en Story 3.3 (revue KYB, première file livrée) ; les stories 4.5, 4.9, 7.4 et 7.5 le **réutilisent** — interdiction d'implémentations parallèles.
- **Contraintes d'ordonnancement inter-pistes (spine production, 2026-07-24)** : ① la règle d'alerte de ségrégation (Story 11.4, invariant AD-16) est **active avant** toute mise en service — même staging — du circuit financier de l'Epic 4 ; ② la Story 10.2 (pages légales) vient **après** la 7.3 (configuration versionnée) ; ③ le mécanisme d'octroi du rôle `ARBITRATOR` (Story 7.2) précède la Story 6.1 (console d'arbitrage) — tirer cet AC de 7.2 en avance si l'Epic 6 démarre avant l'Epic 7 ; ④ la Story 11.9 (migrations runtime) est une **exigence de lancement**, planifiable en parallèle mais bloquante avant production.
- **Piste commerciale (hors backlog, à suivre au plan de lancement)** : validation des corridors `[ASSUMPTION §9.1 du PRD]` contre les opportunités réelles, négociation partenaire bancaire, avis juridiques par pays.

Chaque epic reste autonome et n'exige aucun epic futur pour fonctionner.

## Epic 1 : Sécurité & protection des données *(hérité, en cours)*

Les utilisateurs confient leur argent à une plateforme durcie contre les attaques et les fuites — P0 stop-ship du lancement.

### Story 1.1: Rôle ADMIN non auto-attribuable ✅ *(livrée — commit 204def7)*

As a opérateur de la plateforme,
I want que le rôle ADMIN ne soit jamais attribuable via l'inscription publique,
So that aucun inconnu ne puisse s'octroyer le pouvoir d'arbitrage.

**Acceptance Criteria:**

**Given** le formulaire d'inscription public
**When** une requête revendique le rôle ADMIN (UI ou API directe)
**Then** elle est rejetée par validation whitelist côté service (FR-P16)
**And** le seul ADMIN est semé côté serveur par un bootstrap idempotent.

*(Livrée et vérifiée : revue adversariale, 238 tests verts.)*

### Story 1.2: Externalisation des secrets

As a opérateur,
I want qu'aucun secret ne vive dans le code ou les fichiers versionnés,
So that une fuite du dépôt ne compromette pas la production.

**Acceptance Criteria:**

**Given** un démarrage sans variable critique (JWT, DB, MinIO, clés HMAC)
**When** l'application boote
**Then** elle échoue explicitement en nommant la variable manquante (NFR-P1)
**And** aucun secret en dur ne subsiste dans le code, les migrations ou docker-compose.

**Given** l'audit du dépôt git
**When** on recherche les motifs de secrets connus
**Then** aucune valeur sensible active n'est trouvée ; les valeurs historiques compromises sont révoquées/rotées.

### Story 1.3: Anti-bruteforce sur l'authentification

As a utilisateur,
I want que mon compte résiste aux attaques par force brute,
So that mes fonds et mes données restent protégés même si mon email est connu.

**Acceptance Criteria:**

**Given** N tentatives échouées sur `/auth/login` ou `/register` depuis une même origine
**When** le seuil est franchi
**Then** les tentatives suivantes reçoivent 429 avec backoff progressif (NFR-P2)
**And** l'événement est journalisé dans `audit_logs`.

**Given** un utilisateur légitime après la fenêtre de limitation
**When** il se reconnecte avec le bon mot de passe
**Then** l'accès est rétabli sans intervention manuelle.

### Story 1.4: TLS de bout en bout et en-têtes de sécurité

As a utilisateur,
I want que tout échange avec la plateforme soit chiffré et durci,
So that mes données financières ne puissent être interceptées ou détournées.

**Acceptance Criteria:**

**Given** une requête HTTP en clair
**When** elle atteint le reverse-proxy
**Then** elle est redirigée en HTTPS et HSTS est servi (NFR-P3)
**And** CSP et X-Frame-Options sont présents sur toutes les réponses HTML.

### Story 1.5: CORS allowlist et Swagger fermé en production

As a opérateur,
I want une surface d'API strictement bornée en production,
So that ni les origines inconnues ni la documentation interne ne soient exposées.

**Acceptance Criteria:**

**Given** le profil production
**When** une origine hors allowlist appelle l'API
**Then** la requête CORS est refusée (NFR-P4)
**And** `/swagger-ui` et la spec OpenAPI répondent 404/403.

### Story 1.6: Politique de mots de passe et révocation JWT

As a utilisateur,
I want des règles de mot de passe robustes et une déconnexion réellement effective,
So that un jeton volé ou un mot de passe faible ne donnent pas accès durable à mon compte.

**Acceptance Criteria:**

**Given** une inscription ou un changement de mot de passe
**When** le mot de passe ne respecte pas la politique
**Then** il est rejeté avec les règles explicitées (NFR-P5).

**Given** une déconnexion ou une révocation
**When** le JWT/refresh token révoqué est présenté
**Then** l'accès est refusé côté serveur (pas seulement côté client).

### Story 1.7: Chiffrement au repos

As a opérateur,
I want les preuves et les secrets chiffrés au repos,
So that un accès disque ou une copie de sauvegarde ne divulgue rien en clair.

**Acceptance Criteria:**

**Given** le stockage objet des preuves et les secrets en base (clés HMAC partenaires, secrets webhook)
**When** on inspecte les données au repos
**Then** elles sont chiffrées (NFR-P6), clés gérées hors code (lien AR-P4)
**And** la rotation d'une clé de chiffrement est documentée et testée.

### Story 1.8: Scan anti-malware à l'ingestion

As a partie prenante d'une transaction,
I want que tout fichier uploadé soit scanné avant stockage,
So that le dossier de preuves ne devienne pas un vecteur d'infection.

**Acceptance Criteria:**

**Given** un upload (preuve, pièce KYB, pièce de ticket)
**When** l'antivirus détecte un contenu malveillant
**Then** le fichier est rejeté avec motif explicite et l'événement audité (NFR-P7)
**And** le fichier sain suit le flux normal sans dégradation sensible du temps de réponse.

*(Lève officiellement la NFR-6 « risque accepté POC » du PRD Evidence Upload.)*

### Story 1.9: Hygiène de session sur appareil partagé

As a utilisateur sur un appareil partagé,
I want que ma déconnexion efface toute trace locale de mon activité,
So that l'utilisateur suivant ne voie ni mes transactions ni ma file d'attente.

**Acceptance Criteria:**

**Given** une déconnexion
**When** elle s'exécute
**Then** file offline et stores locaux sont vidés, la file étant scopée par utilisateur (NFR-P8)
**And** un 403 nu est traité comme session expirée avec retour à la connexion et cible conservée (UX-DR32).

### Story 1.10: Anti-énumération des ressources

As a opérateur,
I want des réponses uniformisées sur les ressources protégées,
So that un attaquant ne puisse pas cartographier les transactions ou wallets existants.

**Acceptance Criteria:**

**Given** une requête sur une ressource inexistante vs une ressource d'un tiers
**When** les réponses sont comparées
**Then** elles sont indistinguables (NFR-P9)
**And** les tests d'intégration le prouvent sur transactions, preuves et wallets.

## Epic 2 : Onboarding & comptes d'entreprise

**Goal :** Un professionnel s'inscrit avec vérification OTP et consentement horodaté, crée ou rejoint son entreprise, invite ses collègues avec des rôles internes et sécurise son compte (2FA TOTP) — le tout sur une fondation UI (tokens, composants, layout 3 espaces, i18n EN/FR) que tous les epics suivants réutiliseront.

**FRs couverts :** FR-P9, FR-P13, FR-P14, FR-P27, FR-P28 — plus la fondation UI (UX-DR1..10, UX-DR20..22, UX-DR47..48) et l'i18n (NFR-P24, UX-DR40).

**Contexte brownfield :** auth JWT, `AuthService` et `AuthView` existants (Vue 3 PWA, stores Pinia, migrations Flyway V1..V5, harnais Vitest livré en Story 4.1 POC) ; le rôle ADMIN plateforme n'est pas auto-attribuable (FR-P16, livré). Les stories étendent l'existant sans le réécrire.

### Story 2.1: Fondation UI — tokens de design et i18n EN/FR par clés

As a développeur frontend de la plateforme,
I want un socle de tokens CSS (couleurs, typographie, espacements, rayons) et une infrastructure i18n par clés installés dans la PWA existante,
So that tous les écrans à venir partagent un langage visuel et textuel unique, sans aucune valeur ni chaîne en dur.

**Acceptance Criteria:**

**Given** la PWA Vue 3 existante
**When** l'application est chargée
**Then** l'ensemble des tokens de couleur (UX-DR1 : marque navy/vert recalibré, surfaces et texte, sémantique success/warning/danger/info/neutral/offline, montants signés crédit/débit) est exposé en CSS custom properties globales
**And** la rampe typographique Inter (UX-DR3 : display 28 → caption 12, amount et amount-hero), les espacements base 4 et les rayons (UX-DR4) sont définis en tokens, avec un utilitaire `tabular-nums` applicable à tout montant/solde
**And** l'élévation respecte UX-DR5 (cartes bordées sans ombre ; ombre réservée aux surfaces flottantes).

**Given** le code couleur sémantique du cycle de vie escrow
**When** un écran doit représenter un état de transaction
**Then** une correspondance unique état→token est centralisée dans un module partagé (UX-DR2 : INITIATED=warning, FUNDS_LOCKED/SHIPPED=info, RELEASED=success, DISPUTED=danger, REFUNDED/expirée=neutral, offline=offline), jamais redéfinie par écran
**And** les interdits UX-DR6 sont vérifiés par lint ou test (aucun dégradé décoratif, pas de navy comme couleur d'état, absence du vert #50DF77).

**Given** l'infrastructure i18n par clés en place (NFR-P24, UX-DR40) et les chaînes des vues existantes (dont `AuthView`) migrées vers des clés EN et FR
**When** l'utilisateur bascule la langue via le sélecteur « EN / FR » (libellés texte, sans drapeaux)
**Then** toute l'interface s'affiche dans la langue choisie, avec l'anglais par défaut et les formats de date/nombre localisés
**And** le choix est persisté et restauré à la session suivante.

**Given** une clé de traduction manquante dans l'une des deux langues
**When** l'application est exécutée en développement ou sous le harnais Vitest
**Then** l'absence est détectée (avertissement bloquant en CI de test) au lieu d'afficher silencieusement une chaîne en dur
**And** aucune nouvelle chaîne littérale n'est admise dans les composants migrés.

### Story 2.2: Bibliothèque de composants de base réutilisables

As a développeur frontend de la plateforme,
I want des composants Vue réutilisables (bouton, carte standard, carte wallet, badge d'état) construits exclusivement sur les tokens de la Story 2.1,
So that chaque story d'écran à venir assemble des composants éprouvés au lieu de réinventer le style.

**Acceptance Criteria:**

**Given** les tokens livrés en 2.1
**When** le composant Bouton est utilisé
**Then** il offre les variantes primary/danger (tokenisées, minHeight 44px — UX-DR7, UX-DR38) plus secondaire et ghost, avec libellés passés par clés i18n
**And** il supporte un libellé porteur de montant pour les confirmations financières, et la règle « un seul primaire par surface » est documentée dans la démo du composant.

**Given** les composants Carte
**When** la carte standard et la carte wallet sont rendues
**Then** la carte standard respecte UX-DR8 (surface-card, bordure, radius lg, padding 16)
**And** la carte wallet (composant de présentation piloté par props, sans dépendance au backend wallet) respecte UX-DR9 : fond navy inverse, solde en amount-hero `tabular-nums`, mention « adossé au compte cantonné », actions Déposer/Retirer désactivables, et variante hors-ligne affichant la dernière valeur horodatée avec actions désactivées.

**Given** le composant Badge d'état
**When** il reçoit un état du cycle de vie escrow
**Then** il rend une pilule utilisant exclusivement le mapping sémantique central de 2.1 avec libellé i18n, jamais une icône seule (UX-DR10, UX-DR2)
**And** des tests unitaires Vitest couvrent chaque composant (états, variantes, désactivation) dans les deux langues.

**Given** les états de chargement des composants livrés
**When** une surface attend ses données
**Then** des composants skeleton calqués sur la mise en page cible sont fournis dans la bibliothèque (UX-DR26)
**And** la règle « jamais de spinner plein écran après la première peinture, toute attente a couleur + libellé » est documentée et appliquée dans la démo.

**Given** une page de démonstration interne (route de développement, exclue du build de production)
**When** un relecteur l'ouvre
**Then** tous les composants y sont présentés dans leurs variantes et les deux langues
**And** la revue visuelle est possible sans dépendre d'aucun écran métier futur.

### Story 2.3: Layout trois espaces et navigation responsive

As a utilisateur authentifié (client, arbitre ou opérateur),
I want accéder à l'espace correspondant à mon rôle dans une seule et même PWA, avec une navigation adaptée à mon appareil,
So that je retrouve immédiatement mes fonctions sans portail de connexion distinct ni interface incohérente.

**Acceptance Criteria:**

**Given** l'auth JWT et les rôles existants
**When** un utilisateur se connecte via la vue de connexion existante intégrée au nouveau layout
**Then** une seule PWA sert trois espaces (app client, console d'arbitrage, back-office `/admin`) sous la même authentification (UX-DR20), et l'utilisateur est routé vers l'espace de son rôle
**And** les guards de route bloquent l'accès aux espaces des autres rôles avec une réponse uniforme anti-énumération (NFR-P9) et un écran d'accès refusé i18n.

**Given** l'espace client
**When** il est affiché
**Then** la navigation expose 5 entrées — Accueil, Transactions, Wallet, Support, Profil — plus l'emplacement de la cloche de notifications (UX-DR22 ; la cloche active arrive en Epic 8)
**And** les entrées dont les surfaces relèvent d'epics ultérieurs mènent à des écrans « à venir » i18n, sans lien mort.

**Given** le client mobile-first (UX-DR47)
**When** la fenêtre passe les breakpoints 768 et 1024
**Then** la navigation bascule d'onglets bas (mobile) à colonne 720px puis sidebar avec zone de détail 2 colonnes, contenus limités à 720/960px
**And** les cibles tactiles restent ≥ 44px et le focus clavier est visible sur toute la navigation (UX-DR38).

**Given** la console d'arbitrage et le back-office desktop-first (UX-DR48)
**When** ils sont ouverts à ≥ 768px puis < 768px
**Then** à large écran : sidebar fixe et contenus tableaux max 1280px ; sous 768px : un message explicite indique que la surface n'est pas optimisée mobile
**And** les shells des trois espaces sont couverts par des tests de rendu (guards, redirections par rôle).

**Given** la PWA installée ou installable
**When** l'application est ajoutée à l'écran d'accueil puis relancée
**Then** manifest, icônes et écran de démarrage sont conformes et le shell applicatif (layout + navigation) est précaché et s'affiche sans réseau (UX-DR46)
**And** l'installabilité est vérifiée par un audit automatisé (Lighthouse PWA ou équivalent) dans le harnais existant.

### Story 2.4: Inscription vérifiée par OTP, consentement horodaté et rattachement à l'entreprise

As a professionnel non inscrit,
I want créer mon compte avec email et mot de passe, vérifier mon email par un code à 6 chiffres et déclarer mon entreprise,
So that je dispose d'un compte vérifié, juridiquement consenti et rattaché à mon entreprise, prêt pour le parcours KYB.

**Acceptance Criteria:**

**Given** le formulaire d'inscription (extension de l'`AuthService`/`AuthView` existants, formulaire 1 colonne avec validation à la volée — UX-DR35)
**When** je soumets un email, un mot de passe conforme à la politique en vigueur et la raison sociale de mon entreprise, et que je coche le consentement aux documents légaux
**Then** un compte non vérifié est créé, le consentement est persisté avec horodatage serveur (FR-P27), une entreprise est créée et reliée à l'utilisateur — User↔Company, l'utilisateur créateur en gestionnaire (FR-P9) — et un OTP à 6 chiffres à durée de vie limitée est envoyé à l'email
**And** les nouvelles tables (OTP, consentements, rattachement) sont créées par une migration Flyway dédiée à cette story, et le rôle ADMIN plateforme reste inaccessible depuis ce parcours (FR-P16).

**Given** l'écran de saisie OTP, dont le champ accepte le collage (UX-DR39)
**When** je saisis le code correct avant expiration
**Then** le compte passe vérifié, la session JWT est émise via le mécanisme existant et je suis dirigé vers l'espace client
**And** un compte non vérifié qui tente de se connecter est renvoyé vers l'étape OTP, jamais vers l'application.

**Given** un code erroné ou expiré
**When** je le soumets
**Then** un message d'erreur i18n s'affiche sous le champ, la saisie du formulaire est conservée (UX-DR28, UX-DR35) et le compte reste non vérifié
**And** le nombre de tentatives de validation est plafonné avant invalidation du code.

**Given** la demande de renvoi du code
**When** je clique « Renvoyer » 
**Then** le renvoi est limité (compte à rebours affiché avant nouvel envoi, plafond de renvois — FR-P27, UX-DR21) et tout dépassement est rejeté avec un message daté
**And** la réponse d'inscription ne révèle pas si l'email est déjà enregistré (NFR-P9).

### Story 2.5: Multi-utilisateur — rôles internes et invitation de collègues

As a gestionnaire d'une entreprise inscrite,
I want inviter mes collègues par email en leur attribuant un rôle interne,
So that plusieurs personnes de mon entreprise opèrent sur le même compte avec des droits différenciés (FR-P14).

**Acceptance Criteria:**

**Given** la surface « Profil & entreprise » de l'espace client (UX-DR23 — sous-ensemble membres/invitations)
**When** un gestionnaire saisit l'email d'un collègue et choisit un rôle interne (gestionnaire ou membre)
**Then** une invitation avec jeton unique à expiration est créée (migration Flyway dédiée) et un email d'invitation est envoyé (FR-P13)
**And** l'invitation apparaît dans la liste des membres avec son statut, sa date d'expiration et une action « Renvoyer » (UX-DR30), le tout journalisé via l'`AuditService` existant.

**Given** un destinataire ouvrant un lien d'invitation valide
**When** il complète le parcours d'inscription livré en 2.4 (mot de passe, consentement horodaté, vérification OTP)
**Then** son compte est rattaché à la même entreprise que l'inviteur avec le rôle interne prévu par l'invitation (FR-P9, FR-P14), sans création d'entreprise en doublon
**And** l'invitation est marquée consommée et ne peut pas être réutilisée.

**Given** un jeton d'invitation expiré ou déjà consommé
**When** le lien est ouvert
**Then** un écran d'erreur i18n explique la cause et propose de demander un nouvel envoi au gestionnaire (UX-DR28)
**And** la réponse ne révèle pas si l'email visé possède déjà un compte (NFR-P9).

**Given** un utilisateur au rôle interne « membre »
**When** il tente d'inviter, de renvoyer une invitation ou de modifier le rôle d'autrui
**Then** l'API refuse (403 uniformisé) et l'UI n'expose ces actions qu'aux gestionnaires
**And** aucun parcours de cette story ne permet d'octroyer le rôle ADMIN plateforme (FR-P16), ce que des tests d'API prouvent.

### Story 2.6: Sécurité du compte — réinitialisation de mot de passe et 2FA TOTP

As a utilisateur titulaire d'un compte,
I want réinitialiser mon mot de passe oublié et activer une double authentification TOTP optionnelle,
So that je garde l'accès à mon compte et le protège à la hauteur des fonds qu'il manipule (FR-P13, FR-P28).

**Acceptance Criteria:**

**Given** l'écran public « Mot de passe oublié » (UX-DR21)
**When** je soumets un email
**Then** la réponse affichée est strictement identique que le compte existe ou non (NFR-P9), et si le compte existe un jeton de réinitialisation à usage unique et à expiration est envoyé par email (migration Flyway dédiée)
**And** la définition d'un nouveau mot de passe conforme à la politique révoque les sessions/refresh tokens existants (NFR-P5) puis me ramène à la connexion avec un message de confirmation i18n.

**Given** la section sécurité de « Profil & entreprise »
**When** j'active la 2FA TOTP (FR-P28)
**Then** un QR code et le secret sont affichés pour l'enrôlement, l'activation n'est effective qu'après validation d'un code TOTP correct, et des codes de récupération à usage unique sont affichés une seule fois avec avertissement explicite
**And** la désactivation ultérieure exige un code TOTP ou un code de récupération valide, chaque changement étant journalisé via l'`AuditService`.

**Given** un compte dont la 2FA est activée
**When** la connexion email + mot de passe réussit
**Then** un code TOTP (ou un code de récupération, alors invalidé) est exigé avant l'émission du JWT (FR-P28) ; le champ accepte le collage (UX-DR39)
**And** un code invalide affiche une erreur i18n sans divulguer d'information supplémentaire, avec un plafond de tentatives.

**Given** un jeton de réinitialisation expiré ou déjà utilisé
**When** le lien est ouvert
**Then** un écran d'erreur i18n l'indique et propose de relancer la procédure depuis le début (UX-DR28)
**And** des tests backend prouvent l'usage unique du jeton, y compris en cas de soumissions concurrentes.

### Story 2.7: Politique de session sur appareil partagé (NFR-P8)

> **SÉQUENCEMENT : à exécuter juste APRÈS la Story 2.3, pas en fin d'epic.** Numérotée 2.7 pour ne pas renuméroter 2.4–2.6 (les clés de `sprint-status.yaml` et les références croisées y sont adossées), mais elle se place dans l'ordre d'exécution entre 2.3 et 2.4. Deux raisons : après 2.3 le bouton de déconnexion a sa place définitive dans le layout trois espaces, donc son test s'écrit une seule fois ; et la faille se ferme avant que 2.4/2.5/2.6 n'ajoutent des parcours authentifiés par-dessus.

As a personne utilisant la plateforme depuis un poste partagé,
I want que ma session ne survive pas à mon départ,
So that la personne suivante ne se retrouve pas silencieusement authentifiée à ma place, avec mes transactions et mes preuves (NFR-P8).

**Origine :** décision produit D4 tranchée le 2026-07-28. Le mode de défaillance DOMINANT de l'appareil partagé — fermer l'onglet sans se déconnecter — était resté entier après la Story 1.9 : `ttl-seconds: 86400`, jeton en `localStorage`, aucune expiration d'inactivité. Cette story porte la politique **et ses quatre conséquences directes**, qui sont des implémentations de cette politique et non des correctifs indépendants (ledger, ex-bundle SESSION-PARTAGÉE).

**Acceptance Criteria:**

**Given** un utilisateur qui se connecte sans cocher « rester connecté »
**When** il ferme l'onglet puis qu'une autre personne rouvre l'application sur le même poste
**Then** aucune session n'est restaurée — le jeton vit en `sessionStorage` et meurt avec l'onglet
**And** l'option « rester connecté », explicite et non cochée par défaut, bascule le stockage en `localStorage` pour les appareils personnels.

**Given** une session active laissée sans interaction
**When** le délai d'inactivité est dépassé, que l'onglet soit resté ouvert ou que l'application soit rouverte après ce délai
**Then** la session est terminée par le chemin existant `endSession` (purge locale complète avant l'appel réseau) et l'utilisateur est renvoyé vers l'authentification avec un motif affiché
**And** le contrôle s'exerce aussi AU DÉMARRAGE, pas seulement par minuterie — sinon un onglet rouvert échappe à la mesure.

**Given** plusieurs onglets ouverts sur le même appareil
**When** l'utilisateur se déconnecte, ou que sa session expire, dans l'un d'eux
**Then** les autres onglets terminent leur session sans intervention et cessent d'afficher une interface authentifiée
**And** ni `BroadcastChannel` ni écouteur `storage` n'existant aujourd'hui, le mécanisme retenu est documenté avec sa règle d'autorité entre onglets.

**Given** une déconnexion sur un réseau qui ne répond pas (portail captif, DNS suspendu)
**When** l'utilisateur clique sur « se déconnecter »
**Then** la navigation vers l'écran d'authentification n'attend pas indéfiniment la révocation serveur — l'attente est bornée, `keepalive` garantissant que la requête aboutit même après la navigation
**And** l'hygiène locale reste inchangée : tout le local est purgé AVANT l'appel réseau, ordre déjà prouvé par test.

**Given** un jeton présent dont le profil `escrow_user` est illisible
**When** l'application démarre
**Then** cet état incohérent est traité explicitement — soit le jeton est effacé, soit la file signale « session incohérente » — au lieu d'être lu comme « personne n'est connectée », ce qui fait naître des entrées hors-ligne orphelines, supprimées à la déconnexion suivante.

**Given** une réponse de lecture émise pendant la session de A
**When** elle se résout après la connexion de B
**Then** elle est rejetée : une époque de session monotone, portée par `stores/session.js`, est capturée à l'émission et comparée à la résolution
**And** le compteur anti-course d'`evidence` ne peut plus être rembobiné à 0 par un `$reset()` au point de rendre une réponse de A égale à la première lecture de B.

**Given** la politique livrée
**When** la suite de tests est exécutée
**Then** chaque garde est prouvée par mutation (règle du `project-context.md`) — en particulier le test du bouton de déconnexion et du câblage de `main.js`, aujourd'hui sans aucune couverture alors que ce sont les deux seuls appelants de production des primitives de session.

## Epic 3 : Conformité KYB/AML

**Goal :** L'entreprise est vérifiée (formulaire ZLECAf fixe, revue opérateur sous SLA ≤ 2 j ouvrés) et peut opérer légalement ; screening AML/sanctions à l'onboarding et en continu ; justificatifs et audit conservés ≥ 5 ans en WORM ; tant qu'elle n'est pas approuvée, la plateforme reste en consultation seule (FR-P7, FR-P8, FR-P10, FR-P30, FR-P31, FR-P33).

### Story 3.1: Spike AR-P3 — décision d'architecture fournisseur KYB/AML

As a architecte de la plateforme,
I want trancher le choix du fournisseur de vérification KYB et de screening AML/sanctions (ou d'un mode manuel assumé au MVP),
So that les stories d'implémentation de l'epic reposent sur une décision documentée et non sur des hypothèses implicites.

**Acceptance Criteria:**

**Given** le spike AR-P3 identifié dans l'architecture, **When** l'étude est menée, **Then** une décision d'architecture (ADR) est produite dans `_bmad-output/planning-artifacts/` comparant au moins trois options (fournisseurs KYB/AML couvrant les juridictions ZLECAf, et l'option « revue 100 % manuelle + listes de sanctions publiques » comme repli), **And** la comparaison couvre : couverture pays ZLECAf, screening sanctions/PEP à l'appel et en continu (FR-P8), coût par vérification, modalités d'intégration API, conformité de localisation des données (NFR-P23).

**Given** l'ADR rédigée, **When** elle est relue, **Then** elle énonce un choix motivé et son contrat d'intégration (mode webhook/polling, données transmises, stockage des résultats de screening, articulation avec la revue manuelle opérateur de FR-P30), **And** elle précise l'impact sur le modèle de données KYB des stories 3.2 à 3.5 sans imposer de dépendance à un service non contractualisé au MVP.

**Given** le périmètre « spike », **When** la story est livrée, **Then** aucun code produit n'est mergé : le livrable est exclusivement la décision d'architecture et ses conséquences sur le backlog de l'epic.

### Story 3.2: Formulaire KYB entreprise et soumission du dossier

As a représentant d'une entreprise inscrite,
I want renseigner le formulaire KYB fixe (identité légale, représentant, bénéficiaires effectifs, justificatifs) en pouvant sauvegarder un brouillon, puis soumettre mon dossier,
So that mon entreprise engage sa vérification et puisse opérer légalement sur la plateforme.

**Acceptance Criteria:**

**Given** une entreprise sans dossier KYB, **When** l'utilisateur ouvre la surface KYB, **Then** le formulaire fixe adapté ZLECAf (FR-P30) présente les sections identité légale, représentant, bénéficiaires effectifs et justificatifs (FR-P7), les pièces étant versées via la brique d'upload existante avec validation serveur stricte (AD-7), **And** la saisie est conservée en brouillon réutilisable entre sessions (UX-DR35).

**Given** un formulaire complet, **When** l'utilisateur soumet le dossier, **Then** le statut de l'entreprise passe à « en revue » avec horodatage serveur, l'événement est journalisé via `AuditService` dans la même transaction (AD-5), **And** un dossier incomplet ne peut pas être soumis : les champs manquants sont signalés sous champ sans perte de saisie (UX-DR35).

**Given** un utilisateur connecté dont l'entreprise n'est pas approuvée, **When** il navigue dans l'app, **Then** la bannière KYB persistante (UX-DR12) affiche la variante « non soumis » (avec CTA vers le formulaire) ou « en revue » (avec délai annoncé SLA ≤ 2 j ouvrés), selon le statut du dossier.

### Story 3.3: File de revue KYB opérateur — approbation, rejet motivé, re-soumission

As a opérateur de la plateforme,
I want instruire les dossiers KYB dans une file dédiée du back-office et approuver ou rejeter chaque dossier avec un motif obligatoire,
So that seules des entreprises vérifiées manuellement sous SLA opèrent sur la plateforme.

**Acceptance Criteria:**

**Given** des dossiers KYB soumis, **When** l'opérateur (rôle ADMIN) ouvre la file KYB du back-office, **Then** les dossiers sont listés triés par ancienneté avec badge SLA tricolore par rapport au SLA de 2 j ouvrés (FR-P30, UX-DR16), **And** le panneau latéral affiche le dossier complet (identité, bénéficiaires, justificatifs) avec les actions Approuver / Rejeter.

**Given** un dossier en revue, **When** l'opérateur rejette, **Then** un motif est obligatoire (UX-DR16), le statut passe à « rejeté », la décision motivée est auditée (AD-5), **And** l'entreprise voit la variante « rejeté » de la bannière (motif + CTA de reprise, UX-DR12) et peut re-soumettre avec ses pièces conservées (FR-P30, UX-DR35).

**Given** un dossier en revue, **When** l'opérateur approuve, **Then** le statut passe à « approuvé » avec horodatage et auteur audités, **And** côté client la bannière disparaît au profit d'un toast de confirmation (UX-DR12).

**Given** un utilisateur sans rôle ADMIN, **When** il appelle les endpoints de revue KYB, **Then** la réponse est uniformisée 403/404 anti-énumération (NFR-P9) et aucune donnée de dossier n'est exposée.

### Story 3.4: Gating global KYB — consultation seule tant que non approuvé

As a plateforme conforme,
I want bloquer toute action engageante (création, acceptation, financement, retrait) pour les entreprises sans KYB approuvé, côté serveur comme côté interface,
So that aucune opération n'est engagée par une entreprise non vérifiée (FR-P31).

**Acceptance Criteria:**

**Given** une entreprise dont le KYB n'est pas approuvé, **When** l'un de ses utilisateurs appelle un endpoint engageant (création ou acceptation de transaction, et tout endpoint financier futur via la même garde), **Then** un contrôle serveur centralisé — au même titre que le contrôle d'appartenance AD-3 — refuse l'opération avec un code d'erreur dédié, **And** la consultation (lecture des transactions, du profil, du dossier KYB) reste intégralement permise (FR-P31).

**Given** la même entreprise, **When** l'utilisateur parcourt l'interface, **Then** les actions engageantes sont visibles mais désactivées avec une explication liée au statut KYB (UX-DR33), **And** les fonds éventuellement déposés sont présentés comme « immobilisés jusqu'à approbation » (FR-P31, UX-DR29).

**Given** l'approbation du dossier par l'opérateur (Story 3.3), **When** le statut passe à « approuvé », **Then** les capacités engageantes (wallet, « Nouvelle transaction ») sont activées simultanément sans reconnexion requise (UX-DR33), **And** la levée du gating est auditée.

### Story 3.5: Screening AML/sanctions et rétention WORM ≥ 5 ans

As a responsable conformité de la plateforme,
I want un screening AML/sanctions exécuté à la soumission KYB puis re-déclenché périodiquement, et une conservation inaltérable des justificatifs, résultats de screening et journaux d'audit pendant au moins 5 ans,
So that la plateforme détecte les contreparties sanctionnées en continu et satisfait ses obligations légales de rétention (FR-P8, FR-P10, FR-P33).

**Acceptance Criteria:**

**Given** la décision d'architecture de la Story 3.1, **When** un dossier KYB est soumis, **Then** un screening sanctions/PEP est exécuté selon le mode retenu (API fournisseur ou contrôle manuel outillé), son résultat horodaté est enregistré au dossier et audité (AD-5), **And** un résultat positif (hit) bloque l'approbation automatique du dossier et le signale dans la file opérateur pour instruction (FR-P8, FR-P30).

**Given** des entreprises déjà approuvées, **When** le re-screening périodique planifié s'exécute (FR-P8 « en continu »), **Then** chaque passage est journalisé avec son résultat, **And** un hit sur une entreprise approuvée crée une alerte opérateur tracée sans destruction du dossier existant.

**Given** des justificatifs KYB, résultats de screening et entrées d'audit, **When** une suppression ou modification est tentée (y compris par un opérateur) avant l'échéance de rétention de 5 ans, **Then** l'opération est refusée par construction — retrait logique uniquement, binaire et écritures d'audit préservés (WORM, FR-P10, FR-P33, AD-4), **And** l'échéance de purge autorisée est calculée et vérifiable par enregistrement.

**Given** la politique de rétention en place, **When** les tests automatisés s'exécutent, **Then** ils prouvent sur base réelle (Testcontainers, convention du projet) qu'aucun chemin applicatif ne permet la purge anticipée des preuves KYB ni des `audit_logs` associés.

## Epic 4 : Wallet & circuit financier réel

**Goal :** L'acheteur dépose des fonds réels (PSP hébergé ou virement manuel approuvé) dans le wallet USD de son entreprise et finance ses transactions depuis ce wallet ; le vendeur est crédité à la libération et retire vers sa banque ou son mobile money en devise locale ; le tout est adossé à un grand livre en partie double rapprochable du compte cantonné (FR-P1, P2, P3, P4, P5, P6, P23, P24, P25, P26, P42, P43 ; NFR-P22, NFR-P25 ; UX-DR9, 11, 17, 29, 34).

**Notes d'ordonnancement :** stories strictement séquentielles — chaque story ne dépend que des précédentes, jamais d'une story future. Les deux spikes (AR-P2 puis AR-P1) ouvrent l'epic : aucune écriture financière n'est codée avant que le modèle comptable et le PSP soient tranchés. Les files d'approbation opérateur livrées ici (dépôts manuels, retraits) sont volontairement minimales — la console complète relève de l'Epic 7. Rappel dépendance critique n°1 (hors périmètre logiciel) : la voie réglementaire du wallet (adossement à un établissement licencié) conditionne tout encaissement réel en production ; elle ne bloque pas le développement en sandbox.

### Story 4.1: Spike AR-P2 — Modèle de cantonnement & comptabilité recadré wallet

As a architecte de la plateforme,
I want trancher le modèle de cantonnement et de comptabilité interne, recadré sur le modèle wallet du PRD production,
So that toutes les stories financières suivantes s'écrivent sur un plan de comptes stable et une décision d'architecture documentée, sans re-conception en cours d'epic.

**Acceptance Criteria:**

*(Story recentrée le 2026-07-24 : les invariants comptables sont désormais **fixés par le spine production** — AD-13 (grand livre writer unique, solde dérivé), AD-14 (monnaie), AD-15 (réservations), AD-16 (formule de rapprochement wallets+séquestres+réservations+transit = miroir cantonnement). Le spike ne re-conçoit pas : il valide et détaille.)*

**Given** le spine production (AD-13..16) et le partenaire bancaire de cantonnement pressenti, **When** le spike est mené, **Then** une ADR valide le plan de comptes du spine **contre la réalité du partenaire** (comptes virtuels vs compte omnibus, format et fréquence des relevés servant le rapprochement AD-16, exigences par corridor — dont cantonnement « continu » UEMOA en phase 2), **And** elle documente l'articulation avec la voie réglementaire recommandée (adossement à un établissement licencié — dépendance critique n°1) et le critère « banque déjà participante PAPSS ».

**Given** les mouvements du glossaire PRD (dépôt de fonds, financement, libération, remboursement, frais, retrait), **When** l'ADR est rédigée, **Then** elle spécifie les **schémas d'écritures détaillés** de chaque mouvement sur le plan de comptes d'AD-16 (y compris les cas d'échec : rejet de dépôt manuel, échec de payout, restitution de réservation), **And** chaque schéma est vérifié équilibré et rejouable idempotent (AD-18).

**Given** un écart constaté entre le spine et la réalité du partenaire, **When** il est identifié, **Then** il est traité comme un **conflit à remonter** (mise à jour du spine production versionnée via son memlog), jamais comme une dérogation locale, **And** aucun code de production n'est livré par cette story.

### Story 4.2: Spike AR-P1 — Choix du PSP agrégateur (Flutterwave vs Paystack) + POC sandbox

As a architecte de la plateforme,
I want comparer Flutterwave et Paystack et valider le candidat retenu par un POC sandbox de bout en bout,
So that les stories de dépôt PSP et de payout s'appuient sur un fournisseur choisi sur preuves, compatible NFR-P22 et avec nos corridors de lancement.

**Acceptance Criteria:**

**Given** les corridors de lancement (Kenya↔Afrique du Sud, Nigeria↔Ghana) et les besoins du PRD, **When** la matrice de comparaison est produite, **Then** elle couvre au minimum : moyens d'encaissement (cartes, mobile money, virement), devises réellement réglables par corridor (alimente la liste ISO-4217 de FR-P6), parcours de paiement hébergés/tokenisés (NFR-P22), payouts en devise locale (FR-P43), frais et délais de settlement, qualité des webhooks (signature, rejeu), contraintes de localisation des données (NFR-P23 — signalées, décision portée par l'Epic 11).

**Given** le candidat pressenti, **When** le POC sandbox est exécuté, **Then** un encaissement de test aboutit via le parcours **hébergé** du PSP (aucune donnée carte ne transite par nos écrans ni nos serveurs — NFR-P22, UX-DR36), **And** le webhook de confirmation est reçu, sa signature vérifiée, et le rejeu du même webhook est détecté.

**Given** le POC sandbox, **When** un payout de test vers un compte bancaire ou mobile money sandbox est déclenché, **Then** il aboutit en devise locale avec une référence traçable, **And** le taux de conversion appliqué par le PSP est récupérable par API (nécessaire à FR-P26).

**Given** les résultats de la matrice et du POC, **When** la décision est arrêtée, **Then** une ADR nomme le PSP retenu, les moyens de paiement activés par corridor au MVP, le mode d'authentification des webhooks et la stratégie de clés sandbox/production (secrets externalisés — NFR-P1), **And** le code du POC est conservé hors du code de production (répertoire spike ou branche dédiée).

### Story 4.3: Grand livre en partie double + wallet d'entreprise

As a plateforme (équipe et opérateur),
I want un grand livre en partie double append-only et un wallet USD par entreprise dont le solde en dérive,
So that chaque centime a une origine et une destination auditables et que l'invariant de ségrégation des fonds est contrôlable en continu (FR-P3, FR-P25, NFR-P25).

**Acceptance Criteria:**

**Given** l'ADR de la Story 4.1, **When** la migration Flyway est appliquée, **Then** les tables du grand livre existent (comptes, écritures journalisées avec lignes débit/crédit, référence métier, horodatage serveur — AD-11) avec les invariants garantis **en base** : montants strictement positifs par ligne, équilibre débit=crédit par écriture, devise validée contre la liste ISO-4217 des devises réglables (FR-P6, seedée USD au MVP — FR-P26), écritures immuables (aucun UPDATE/DELETE applicatif, trigger ou privilège l'interdisant), **And** un wallet est provisionné par entreprise (unicité entreprise+devise) sans table superflue au-delà du besoin de cette story.

**Given** le service comptable interne, **When** une écriture est enregistrée via l'API de service (jamais par manipulation directe du solde), **Then** le solde du wallet reflète exactement la somme de ses lignes de grand livre, **And** l'enregistrement est idempotent par clé de référence métier (la même référence rejouée ne produit aucune seconde écriture), **And** un événement d'audit est écrit via `AuditService` dans la même transaction (AD-5).

**Given** un wallet avec un solde donné, **When** deux débits concurrents dont la somme excède le solde sont soumis simultanément, **Then** un seul aboutit et l'autre est rejeté avec une erreur explicite « solde insuffisant », **And** le solde ne devient jamais négatif (preuve par test de concurrence sur base réelle, Testcontainers).

**Given** l'invariant NFR-P25, **When** le contrôle de rapprochement s'exécute (planifié et déclenchable à la demande), **Then** il vérifie que la somme des soldes wallets et des fonds séquestrés égale le solde du compte miroir de cantonnement, **And** tout écart déclenche une alerte bloquante journalisée (log structuré + événement d'audit, branchable sur l'alerting NFR-P13) et le résultat de chaque exécution est historisé.

**Given** l'exigence d'auditabilité par transaction (FR-P3), **When** un auditeur interroge le grand livre avec la référence d'une transaction escrow, **Then** il obtient la chaîne complète des écritures qui la concernent, chacune équilibrée, horodatée et rattachée à son événement d'audit.

### Story 4.4: Dépôt de fonds via PSP hébergé

As a acheteur (Amina),
I want déposer des fonds sur le wallet de mon entreprise via le parcours de paiement hébergé du PSP (carte, mobile money, virement instantané),
So that je peux alimenter mon solde en argent réel sans jamais saisir de données carte sur la plateforme (FR-P1, NFR-P22, FR-P26).

**Acceptance Criteria:**

**Given** un utilisateur authentifié dont l'entreprise possède un wallet, **When** il initie un dépôt de fonds (montant + méthode), **Then** les frais de dépôt de la méthode choisie (FR-P24, barème seedé) et le montant estimé crédité en USD sont affichés **avant** confirmation, **And** il est redirigé vers le parcours **hébergé** du PSP — aucune donnée carte ne transite ni n'est stockée par la plateforme (NFR-P22, UX-DR36), **And** une intention de dépôt est créée à l'état `PENDING` avec une référence unique.

**Given** une intention de dépôt `PENDING`, **When** le webhook de confirmation du PSP est reçu avec une signature valide, **Then** le wallet est crédité en USD, converti au taux courant du PSP (FR-P26 — risque FX porté par l'utilisateur), frais de dépôt déduits et tracés en compte de produits, via une écriture de grand livre équilibrée référençant l'identifiant PSP, **And** l'audit est écrit dans la même transaction, **And** l'utilisateur voit son dépôt passer à l'état confirmé avec le montant USD effectivement crédité.

**Given** un webhook déjà traité, **When** le PSP le rejoue (retry ou doublon), **Then** aucun second crédit n'est produit (idempotence par référence PSP — Story 4.3), **And** le rejeu est journalisé.

**Given** un webhook à signature invalide ou portant une référence inconnue, **When** il est reçu, **Then** il est rejeté sans aucune écriture comptable, avec réponse uniformisée (NFR-P9) et journalisation, **And** un paiement échoué ou abandonné côté PSP marque l'intention `FAILED` avec un motif affichable et aucun crédit (UX-DR28 : motif + action de reprise).

### Story 4.5: Dépôt de fonds par virement bancaire manuel + file d'approbation opérateur

As a acheteur (Amina),
I want déclarer un virement bancaire effectué vers le compte de cantonnement en joignant ma preuve de paiement, puis être créditée après validation humaine,
So that je peux alimenter mon wallet même sans moyen de paiement PSP — sans qu'aucun crédit ne soit possible sans contrôle opérateur (FR-P23).

**Acceptance Criteria:**

**Given** un utilisateur authentifié, **When** il déclare un virement manuel (montant, devise d'origine, référence bancaire, preuve jointe obligatoire — via la brique d'upload existante, port `EvidenceStorage`, validation stricte à l'ingestion AD-7), **Then** une déclaration à l'état `PENDING_RECONCILIATION` (valeur stockée anglaise — libellé i18n « en rapprochement », convention du spine production) est créée, visible dans son historique **hors solde disponible** (UX-DR29), **And** aucune écriture de crédit n'existe à ce stade, **And** l'audit trace la déclaration.

**Given** des déclarations en attente, **When** l'opérateur (rôle ADMIN) ouvre la file minimale des dépôts manuels, **Then** il voit la liste triée par ancienneté et, pour chaque déclaration, les informations saisies et la preuve jointe côte à côte, **And** cette file est volontairement minimale (la console complète relève de l'Epic 7).

**Given** une déclaration en rapprochement, **When** l'opérateur approuve en confirmant le montant reçu et le taux de conversion vers USD (FR-P26), **Then** le wallet est crédité par écriture de grand livre équilibrée (frais de dépôt par méthode déduits et tracés — FR-P24), **And** la décision (opérateur, horodatage, montant, taux) est auditée dans la même transaction, **And** le déclarant voit le crédit et sa référence dans son wallet.

**Given** une déclaration en rapprochement, **When** l'opérateur rejette, **Then** un motif est **obligatoire** (UX-DR16), aucune écriture comptable n'est produite, la déclaration passe à `REJECTED` (libellé i18n « rejetée ») avec le motif visible par le déclarant et une action de reprise (UX-DR28), **And** le rejet est audité.

**Given** une déclaration déjà approuvée ou rejetée, **When** un opérateur tente de la traiter à nouveau (y compris en concurrence avec un autre opérateur), **Then** la seconde décision est refusée avec une erreur explicite et aucun double crédit n'est possible (preuve par test de concurrence).

### Story 4.6: Financement d'une transaction depuis le wallet + frais répartis

As a acheteur (Amina),
I want financer une transaction escrow en débitant le wallet de mon entreprise, frais affichés et répartis selon les conditions de la transaction,
So that les fonds sont réellement immobilisés en faveur du vendeur avant expédition — c'est le passage à `FUNDS_LOCKED` (FR-P1, FR-P4, FR-P24, FR-P5, UX-DR17, UX-DR34).

**Acceptance Criteria:**

**Given** une transaction escrow existante prête à être financée (machine à états `EscrowStateMachine` héritée) et une répartition de frais portée par la transaction (acheteur / vendeur / 50-50 ; défaut 50-50 si absente), **When** l'acheteur demande le financement, **Then** le barème configurable (pourcentage dégressif par tranche + minimum fixe — FR-P24, table seedée, les conditions applicables étant figées sur la transaction à cet instant) calcule la commission et sa répartition, **And** une modale de confirmation financière (UX-DR17) affiche le récapitulatif : montant séquestré, frais par partie, solde après opération, le bouton de confirmation portant le montant exact — jamais d'engagement en un clic (UX-DR34).

**Given** la confirmation de l'acheteur avec une clé d'idempotence, **When** le financement s'exécute, **Then** en **une seule transaction** : le wallet acheteur est débité du montant + part acheteur des frais, le montant est porté au compte de séquestre de la transaction, la part acheteur des frais est tracée en compte de produits (FR-P4), la transaction passe à `FUNDS_LOCKED` via la machine à états, et l'audit est écrit (AD-5), **And** le taux et la devise de règlement sont figés à l'engagement — USD pivot au MVP, affichage indicatif en devise locale (FR-P5, FR-P26).

**Given** un solde disponible insuffisant, **When** l'acheteur tente de financer, **Then** l'opération est refusée avant toute écriture avec un message explicite indiquant le manquant et un CTA vers le dépôt de fonds (UX-DR28), **And** l'état de la transaction est inchangé.

**Given** une double soumission (double clic, rejeu réseau) avec la même clé d'idempotence, **When** la seconde requête arrive, **Then** aucun second débit ni seconde transition n'est produit et la réponse renvoie le résultat de la première (NFR-P10 étendu au financier), **And** la modale est protégée contre la double soumission côté client (UX-DR17).

**Given** l'échec d'une des écritures comptables pendant le financement, **When** la transaction technique échoue, **Then** tout est annulé — ni débit, ni transition d'état, ni audit partiel (atomicité prouvée par test sur base réelle).

### Story 4.7: Libération et remboursement = crédits de wallet effectifs

As a vendeur (Thabo) ou acheteur (Amina),
I want que la libération crédite réellement le wallet vendeur (commission déduite) et que le remboursement recrédite le wallet acheteur au montant figé,
So that la fin d'une transaction escrow produit un mouvement d'argent réel et traçable, pas un simple changement d'état (FR-P2, FR-P4, FR-P26).

**Acceptance Criteria:**

**Given** une transaction `FUNDS_LOCKED` (ou aval) arrivant à libération — confirmation de réception, auto-libération ou décision d'arbitrage via les transitions existantes de `EscrowStateMachine`, **When** la transition vers `RELEASED` s'exécute, **Then** en une seule transaction : le compte de séquestre est soldé, le wallet vendeur est crédité du montant **moins la part vendeur de la commission** (déduite du crédit de libération — FR-P4), la commission est portée en compte de produits avec sa décomposition tracée, et l'audit est écrit, **And** le vendeur voit le crédit dans son wallet avec la référence de la transaction.

**Given** une transaction arrivant à remboursement (rejet, expiration, décision d'arbitrage), **When** la transition vers `REFUNDED` s'exécute, **Then** le wallet acheteur est recrédité **en USD au montant figé à l'engagement** — aucun risque FX interne (FR-P26), selon la politique de frais figée à l'engagement pour ce cas, **And** le compte de séquestre de la transaction est soldé à zéro, **And** l'audit est écrit dans la même transaction.

**Given** une transition de libération ou de remboursement déjà exécutée, **When** elle est rejouée (rejeu technique, concurrence entre auto-libération et confirmation manuelle), **Then** aucun double crédit n'est produit (idempotence par référence de transition, garde de la machine à états), preuve par test de concurrence sur base réelle.

**Given** l'échec de l'écriture comptable pendant la transition, **When** la transaction technique échoue, **Then** la transition d'état est annulée avec elle — jamais d'état `RELEASED`/`REFUNDED` sans les crédits correspondants, et réciproquement, **And** le rapprochement NFR-P25 (Story 4.3) reste vert après une campagne complète financement→libération et financement→remboursement.

### Story 4.8: Ledger utilisateur + carte wallet (UI)

As a utilisateur d'une entreprise (Amina ou Thabo),
I want voir le solde de mon wallet et l'historique complet de ses mouvements dans l'application,
So that je comprends d'où vient et où va chaque centime, avec le solde après chaque opération (FR-P42, UX-DR9, UX-DR11, UX-DR29).

**Acceptance Criteria:**

**Given** un utilisateur authentifié sur l'espace client, **When** il ouvre la surface Wallet, **Then** la carte wallet (UX-DR9) affiche : fond navy inverse, solde en `amount-hero` avec `tabular-nums` (UX-DR3), mention « adossé au compte cantonné », actions Déposer / Retirer, **And** hors-ligne la carte montre la dernière valeur connue horodatée « données au… » avec les actions financières désactivées et expliquées (UX-DR42/43 — jamais d'optimisme financier hors-ligne).

**Given** des mouvements sur le wallet, **When** l'utilisateur consulte le ledger, **Then** chaque ligne (UX-DR11) montre le libellé typé (dépôt de fonds, financement, crédit de libération, remboursement, frais, retrait — vocabulaire du glossaire PRD, UX-DR37), la référence, l'horodatage, le montant signé en chiffres tabulaires et le **solde après opération** en caption, le signe et le libellé portant l'information — jamais la couleur seule (UX-DR38), **And** un clic ouvre le détail du mouvement avec sa référence métier.

**Given** un historique fourni, **When** l'utilisateur filtre par type et par période, **Then** la liste est paginée — jamais de scroll infini sur données financières (UX-DR36) — et le filtre vide propose une réinitialisation (UX-DR27), **And** les données servies proviennent du grand livre (aucune source de solde parallèle).

**Given** les états métier du wallet (UX-DR29), **When** ils existent, **Then** un dépôt manuel en rapprochement apparaît distinctement **hors solde disponible**, un montant réservé pour retrait est visible séparément du disponible, et des fonds détenus par une entreprise non encore approuvée KYB sont explicitement présentés « immobilisés jusqu'à approbation » (FR-P31), **And** tous les libellés passent par les clés i18n EN/FR (UX-DR40).

### Story 4.9: Retraits avec approbation opérateur, réservation et restitution

As a vendeur (Thabo) d'une entreprise KYB approuvée,
I want demander un retrait de mon wallet vers mon compte bancaire ou mobile money et être versé en devise locale après approbation opérateur,
So that l'argent gagné sur la plateforme finit réellement sur mon compte (FR-P43, FR-P26, FR-P24).

**Acceptance Criteria:**

**Given** un utilisateur dont l'entreprise est KYB approuvée (FR-P31), **When** il demande un retrait (méthode : compte bancaire ou mobile money, coordonnées saisies et conservées, montant), **Then** les limites min/max et les frais de la méthode (FR-P24) ainsi que l'estimation en devise locale (FR-P26, taux indicatif) sont affichés **avant** confirmation dans une modale UX-DR17 (récapitulatif, bouton portant le montant, anti double-soumission — UX-DR34), **And** à la confirmation le montant est immédiatement **réservé** par écriture de grand livre (le solde disponible diminue, le montant réservé reste visible — UX-DR29), **And** la demande entre à l'état `PENDING_APPROVAL` (libellé i18n « en attente d'approbation ») avec le SLA affiché (≤ 1 j ouvré `[ASSUMPTION]`), le tout audité. Une entreprise non approuvée KYB voit l'action désactivée avec explication (UX-DR33) et le serveur la refuse.

**Given** des demandes de retrait en attente, **When** l'opérateur ouvre la file minimale des retraits, **Then** il voit pour chaque demande le montant réservé, les coordonnées du bénéficiaire et l'historique wallet de l'entreprise, triés par ancienneté, **And** un solde réservé n'est jamais dépensable par un financement concurrent (preuve par test : financement + retrait concurrents ne peuvent pas engager deux fois les mêmes fonds).

**Given** une demande en attente, **When** l'opérateur approuve, **Then** le payout est exécuté via le PSP retenu (Story 4.2) **en devise locale du bénéficiaire au taux du jour d'exécution** (FR-P26, risque FX porté par l'utilisateur), la réservation est transformée en débit définitif avec frais de retrait tracés en produits, la référence de payout PSP est conservée, et la décision est auditée, **And** l'utilisateur voit le retrait `EXECUTED` (libellé i18n « exécuté ») avec montant local versé et référence.

**Given** une demande en attente, **When** l'opérateur rejette (motif **obligatoire**), **Then** la réservation est **restituée intégralement** au solde disponible par écriture inverse référencée, le demandeur voit le motif et une action de reprise (UX-DR28), et le rejet est audité, **And** une demande déjà traitée ne peut être ni ré-approuvée ni ré-rejetée (idempotence, test de concurrence entre deux opérateurs).

**Given** un échec du payout PSP après approbation, **When** l'échec est notifié ou constaté, **Then** aucun débit définitif n'est comptabilisé tant que le payout n'est pas confirmé (la réservation persiste, la demande passe à `PAYOUT_FAILED` (libellé i18n « échec du versement ») visible de l'opérateur pour reprise ou restitution motivée), **And** le rapprochement NFR-P25 reste vert dans tous les scénarios de cette story.

## Epic 5 : Transaction escrow de bout en bout

**Goal :** Livrer le fil rouge UJ-2 — un acheteur et un vendeur mènent une transaction escrow complète, de l'invitation (y compris vers une contrepartie non inscrite — FR-P29) jusqu'à l'état terminal verrouillé, en passant par le financement, l'expédition documentée CoO ZLECAf (FR-P32), la livraison et la libération automatique ou confirmée sous SLA (FR-P12) — en s'appuyant sur la machine à états existante (INITIATED→FUNDS_LOCKED→SHIPPED→RELEASED/DISPUTED, FR-1..16, AD-1..12) et sur les Epics 2 (comptes), 3 (KYB) et 4 (wallet).

### Story 5.1: Wizard de création de transaction et invitation de la contrepartie

As a acheteur ou vendeur KYB approuvé,
I want créer une transaction via un wizard en 2 étapes et inviter ma contrepartie par email, qu'elle soit inscrite ou non,
So that je peux engager une transaction sécurisée même avec un partenaire commercial qui ne connaît pas encore la plateforme.

**Acceptance Criteria:**

**Given** un utilisateur KYB approuvé (FR-P31) **When** il lance « Nouvelle transaction » **Then** un wizard en 2 étapes (UX-DR23) collecte : email de la contrepartie, rôle (acheteur/vendeur), description de la marchandise, montant en USD (FR-P26), conditions et délai de livraison, répartition des frais acheteur/vendeur/50-50 (FR-P24) **And** l'étape finale affiche un récapitulatif incluant le détail des frais par partie avant confirmation, le wizard étant réversible avec saisie conservée (UX-DR35).

**Given** un email de contrepartie correspondant à une entreprise inscrite et KYB approuvée **When** la création est confirmée **Then** la transaction est créée en `INITIATED` avec statut d'invitation « créée » (extension amont du cycle de vie, PRD §6.A), la contrepartie est notifiée, et l'audit est écrit dans la même transaction via `AuditService` (AD-5).

**Given** un email de contrepartie ne correspondant à aucun compte **When** la création est confirmée **Then** une invitation par email est émise et la transaction reste « en attente d'acceptation » jusqu'à inscription + KYB approuvé de la contrepartie (FR-P29) **And** le créateur voit l'état « Invitation en attente » avec date d'expiration affichée et action « Renvoyer l'invitation » (UX-DR30).

**Given** un utilisateur sans KYB approuvé **When** il tente d'accéder au wizard **Then** l'action est visible mais désactivée avec explication (FR-P31, UX-DR33) et le serveur rejette toute tentative directe d'appel API (AD-3).

**Given** deux emails de contrepartie, l'un inscrit et l'autre non **When** la création est confirmée dans les deux cas **Then** les deux réponses sont INDISTINGUABLES par un appelant — même statut, même corps, mêmes en-têtes (horodatage normalisé) — et la transaction est créée dans les deux cas, l'une notifiée et l'autre en attente d'acceptation **And** un test jumeau au niveau HTTP le prouve, sur le gabarit d'`AntiEnumerationIntegrationTest` (NFR-P9, AD-10).

> **CONTRAINTE DURE héritée de la décision D1 (2026-07-28) — ne pas la perdre en route.** L'endpoint POC `POST /api/v1/escrow` porte aujourd'hui un oracle d'énumération d'e-mails : `EscrowService.java:69` lève `BadRequestException("No seller registered with that email")`, réponse qui se distingue d'une création réussie. N'importe quel porteur de JWT peut donc tester l'appartenance d'une adresse au fichier des utilisateurs — la même classe de défaut que tout l'Epic 1, sur une autre surface. Il a été décidé de NE PAS le corriger sur place, précisément parce que les AC ci-dessus le suppriment par construction : un email inconnu ne produit plus un refus mais une invitation, donc les deux branches convergent. Cette story est le seul endroit où le trou se referme — **si le chemin « invitation » était reporté ou découpé, l'oracle devrait être fermé séparément avant toute mise en production.** Aucune production ne doit ouvrir avec le message de refus actuel en place.

### Story 5.2: Acceptation, demande de financement et expirations SLA amont

As a contrepartie invitée,
I want examiner et accepter la transaction puis, si je suis l'acheteur, la financer depuis mon wallet — les invitations et transactions abandonnées expirant automatiquement,
So that la transaction ne démarre qu'avec mon consentement éclairé et des fonds réellement immobilisés, sans zombies dans le système.

**Acceptance Criteria:**

**Given** une invitation en attente **When** la contrepartie (inscrite et KYB approuvée — FR-P29, FR-P31) consulte la transaction **Then** elle voit l'intégralité des conditions, dont la répartition des frais visible avant acceptation (FR-P24) **And** son acceptation fait passer l'invitation au statut « acceptée » et déclenche la demande de financement auprès de l'acheteur.

**Given** une transaction acceptée et un acheteur au solde wallet suffisant (Epic 4, FR-P25) **When** l'acheteur confirme le financement via la modale de confirmation financière (récapitulatif montant/frais/solde après, montant dans le bouton, anti double-soumission — UX-DR17, UX-DR34) **Then** le wallet est débité (FR-P1), la part de frais acheteur est prélevée (FR-P4) et la machine à états existante transitionne vers `FUNDS_LOCKED` **And** solde insuffisant → renvoi vers le dépôt de fonds (Epic 4) sans perte du contexte.

**Given** une invitation non acceptée à l'échéance du délai configuré (FR-P12, FR-P37) **When** le job de SLA s'exécute **Then** l'invitation passe au statut « expirée » (statut amont `EXPIRED` à effet de verrou — AD-19), la transaction est close en état neutre (UX-DR2), un événement de notification est persisté pour les deux parties (outbox AD-22 — les canaux de livraison arrivent en Epic 8) et l'audit est tracé (AD-5).

**Given** une transaction acceptée mais non financée à l'échéance du délai configuré **When** le job de SLA s'exécute **Then** la transaction expire sans aucun mouvement de fonds (FR-P12) **And** l'expiration est idempotente : une exécution concurrente du job ne produit ni double transition ni double notification.

### Story 5.3: Expédition et dossier documentaire avec Certificat d'Origine ZLECAf

As a vendeur d'une transaction financée,
I want déclarer l'expédition en versant au dossier les documents de transport et un Certificat d'Origine ZLECAf typé avec ses métadonnées,
So that ma marchandise voyage avec un dossier documentaire exploitable au dédouanement et opposable en cas d'arbitrage.

**Acceptance Criteria:**

**Given** une transaction en `FUNDS_LOCKED` **When** le vendeur déclare l'expédition **Then** la machine à états existante transitionne vers `SHIPPED` et la fenêtre de dépôt de preuves fondée sur l'état s'ouvre (AD-2), réutilisant la brique de versement livrée (FR-1..16, zone UX-DR15 : multi-fichiers plafond 20, caméra mobile, drag-drop desktop).

**Given** le versement d'une preuve de type « Certificat d'Origine ZLECAf » (FR-P32) **When** le vendeur sélectionne ce type de premier rang **Then** la variante CoO de la zone de versement (UX-DR15) exige les métadonnées dédiées : n° de certificat, autorité émettrice, date d'émission, n° « Approved Exporter » le cas échéant, et état DUPLICATE / ISSUED RETROSPECTIVELY / REPLACEMENT **And** la validation serveur stricte rejette un versement CoO aux métadonnées incomplètes (AD-7).

**Given** un CoO versé dont la date d'émission remonte à plus de 12 mois — ou s'en approche **When** le dossier est affiché **Then** la validité de 12 mois est signalée visuellement (FR-P32, alerte UX-DR15), sans validation automatique de conformité ni blocage au MVP.

**Given** un envoi ≤ 5 000 USD, ou un vendeur « Approved Exporter » identifié par son numéro d'autorisation (sans plafond) **When** le vendeur choisit l'alternative « déclaration d'origine sur facture » (FR-P32) **Then** ce type de preuve est accepté avec le numéro d'autorisation en métadonnée le cas échéant **And** hors de ces deux conditions, l'alternative est refusée côté serveur avec un motif explicite.

### Story 5.4: Livraison, confirmation acheteur et automatismes SLA de dénouement

As a acheteur d'une transaction expédiée,
I want confirmer la réception — ou laisser la libération se faire automatiquement après le délai configuré, la plateforme me remboursant d'office en cas de non-expédition,
So that le vendeur est payé sans friction dans le cas nominal et mes fonds ne restent jamais bloqués si la marchandise ne part pas.

**Acceptance Criteria:**

**Given** une transaction en `SHIPPED` avec livraison attestée (preuves du transporteur partenaire, brique HMAC livrée — AD-8) **When** l'acheteur confirme la réception via l'événement `DELIVERY_CONFIRMED` (terminologie du cœur existant, PRD §6.A) avec confirmation « à deux vitesses » (UX-DR34) **Then** la transaction passe à `RELEASED` et le wallet vendeur est crédité du montant, commission déduite selon la répartition choisie (FR-P2, FR-P4).

**Given** une livraison attestée sans confirmation ni litige de l'acheteur dans le délai configuré `[ASSUMPTION : 7 jours]` (FR-P12, configurable FR-P37) **When** le job d'auto-libération s'exécute **Then** la transaction passe à `RELEASED` avec crédit wallet vendeur identique au chemin manuel **And** l'audit distingue explicitement libération automatique et confirmation manuelle (AD-5), un événement de notification étant persisté pour les deux parties (outbox AD-22 — livraison en Epic 8) ; les transitions automatiques sont déclenchées par l'acteur `SYSTEM` de la machine à états (AD-19).

**Given** une transaction en `FUNDS_LOCKED` non expédiée à l'échéance du délai de livraison convenu (FR-P12) **When** le job de SLA s'exécute **Then** l'auto-remboursement est déclenché : le wallet acheteur est crédité (FR-P2 — `REFUNDED`) et l'échéance était visible des deux parties sur la timeline en amont (UX-DR14).

**Given** un litige ouvert avant l'échéance d'auto-libération (branche `DISPUTED` existante, AD-1) **When** le job d'auto-libération s'exécute **Then** aucune libération automatique n'a lieu — le litige suspend les automatismes de dénouement (FR-P12) **And** les jobs SLA sont idempotents face aux exécutions concurrentes avec une transition d'état (aucun double crédit).

### Story 5.5: Timeline de transaction, récapitulatif téléchargeable et verrouillage terminal

As a partie à une transaction,
I want suivre l'avancement sur une timeline claire avec échéances SLA, puis obtenir un récapitulatif téléchargeable une fois le dossier verrouillé,
So that je sais toujours qui doit agir et pour quand, et je conserve une trace complète et figée de la transaction close.

**Acceptance Criteria:**

**Given** une transaction à n'importe quel stade du cycle de vie **When** une partie ouvre le détail **Then** la timeline verticale (UX-DR14) affiche l'état courant en plein, les états futurs en muted, la prochaine action nommée avec son responsable et les échéances SLA datées (FR-P12, microcopie UX-DR37) **And** le code couleur sémantique des états est celui des tokens (UX-DR2), jamais réinventé.

**Given** une transaction atteignant un état terminal (`RELEASED`, `REFUNDED`, expirée) **When** une partie consulte le dossier **Then** tout est en lecture seule avec la mention « Dossier verrouillé le… » (UX-DR31) : preuves (verrou terminal FR-1..16, AD-2/AD-4), actions et métadonnées **And** toute tentative d'écriture par API est rejetée par le serveur.

**Given** une transaction en état terminal **When** une partie demande le récapitulatif téléchargeable (UX-DR31, UJ-2) **Then** un document est généré reprenant : parties, conditions, montants et frais par partie (FR-P4/P24), chronologie complète des transitions horodatées côté serveur (AD-11) et inventaire du dossier de preuves avec métadonnées CoO (FR-P32) **And** le téléchargement est servi en `attachment` (AD-7) et réservé aux parties de la transaction (AD-3).

**Given** un utilisateur hors-ligne ayant déjà consulté la transaction **When** il rouvre le détail **Then** la timeline s'affiche depuis le cache horodaté « données au… » (FR-P20, UX-DR43) **And** les actions engageant des fonds sont désactivées avec explication (FR-P40, UX-DR42).

## Epic 6 : Litige, arbitrage & messagerie
Les parties dialoguent par transaction ; un litige documenté est instruit et tranché par l'arbitre dans sa console dédiée. Le socle litige+preuves est livré (ouverture atomique AD-1, contradictoire, transitions `RESOLVE_DISPUTE_RELEASE`/`RESOLVE_DISPUTE_REFUND` existantes) : cet epic outille l'arbitre (FR-P11, UX-DR24) et ajoute la messagerie par transaction (FR-P34, UX-DR18).

### Story 6.1: File des litiges de la console d'arbitrage

As a arbitre de la plateforme,
I want une file dédiée des litiges ouverts, triée par ancienneté et assignable,
So that aucun litige ne reste sans instructeur et que le SLA de résolution soit tenu.

**Acceptance Criteria:**

**Given** un utilisateur authentifié porteur du rôle arbitre (octroyé par la plateforme uniquement, cohérent FR-P16), **When** il ouvre la console d'arbitrage (espace desktop-first sous la même auth JWT, UX-DR20), **Then** il voit la file des transactions en état `DISPUTED` triée par ancienneté avec badge SLA tricolore et statut d'assignation (FR-P11, UX-DR16, UX-DR24), **And** la liste est paginée, jamais en scroll infini (UX-DR36).

**Given** un litige non assigné, **When** l'arbitre se l'assigne (ou qu'un ADMIN le lui assigne), **Then** l'assignation est persistée et écrite via `AuditService` dans la même transaction (AD-5), **And** le litige apparaît comme « assigné » pour tous les autres arbitres, sans double assignation possible.

**Given** un utilisateur sans rôle arbitre ni ADMIN, **When** il appelle les endpoints de la file ou d'assignation, **Then** la réponse est un 403/404 uniformisé anti-énumération (NFR-P9), **And** aucune donnée de litige ne fuit.

### Story 6.2: Dossier de litige et décision motivée verrouillante

As a arbitre assigné à un litige,
I want consulter le contradictoire complet et rendre une décision RELEASE ou REFUND motivée,
So that le litige soit tranché de façon traçable et le dossier définitivement verrouillé.

**Acceptance Criteria:**

**Given** un litige assigné à l'arbitre, **When** il ouvre le dossier depuis la file (Story 6.1), **Then** il voit le contradictoire complet : motif d'ouverture, preuves des deux parties horodatées, chronologie de la transaction (FR-P11, UX-DR24), **And** les fichiers sont restitués via le mécanisme de preuves existant en `attachment` (AD-7).

**Given** l'arbitre choisit une décision RELEASE ou REFUND, **When** il tente de valider sans motif, **Then** le serveur rejette la décision — le motif est obligatoire (FR-P11, UX-DR36), **And** la saisie est conservée à l'écran (UX-DR28).

**Given** une décision motivée confirmée (modale de confirmation, anti double-soumission UX-DR17/34), **When** elle est soumise, **Then** la transition existante `RESOLVE_DISPUTE_RELEASE` ou `RESOLVE_DISPUTE_REFUND` de `EscrowStateMachine` est déclenchée — aucune transition nouvelle, **And** décision, motif et identité de l'arbitre sont tracés via `AuditService` dans la même transaction (AD-5), **And** le dossier passe en lecture seule « Dossier verrouillé le… » (UX-DR31).

**Given** une transaction déjà en état terminal, **When** une décision est tentée (rejeu, double onglet), **Then** la machine à états la rejette et aucune écriture n'a lieu, **And** la réponse d'erreur respecte le contrat d'erreurs existant.

### Story 6.3: Messagerie par transaction — fil, droits et rétention (backend)

As a acheteur ou vendeur partie à une transaction,
I want un fil de messages texte unique attaché à la transaction, ouvert à l'arbitre dès litige,
So that les échanges restent dans le dossier, horodatés et conservés comme les preuves.

**Acceptance Criteria:**

**Given** une transaction non terminale dont l'appelant est acheteur ou vendeur (contrôle d'appartenance centralisé AD-3), **When** il poste un message, **Then** le message texte est persisté dans le fil unique de la transaction avec horodatage serveur source de vérité (AD-11) et rôle de l'auteur (FR-P34), **And** tout contenu non textuel ou pièce jointe est rejeté — texte seul au MVP, les fichiers passent par le dépôt de preuves (FR-P34).

**Given** un litige ouvert sur la transaction, **When** l'arbitre consulte ou poste dans le fil, **Then** il accède à l'historique complet et ses messages sont attribués au rôle arbitre (FR-P34, UX-DR18), **And** avant l'ouverture du litige, l'arbitre n'a aucun accès au fil (403/404 uniformisé, NFR-P9).

**Given** une transaction passée en état terminal (`RELEASED`, `REFUNDED`, expirée), **When** quiconque tente de poster, **Then** le serveur rejette : le fil est verrouillé avec le dossier (FR-P34), **And** les messages sont conservés selon la rétention alignée sur les preuves ≥ 5 ans, sans suppression possible (FR-P33).

**Given** un utilisateur tiers non partie à la transaction, **When** il tente de lire ou poster, **Then** la réponse est un 403/404 uniformisé (AD-3, NFR-P9).

### Story 6.4: Messagerie — interface dans le détail de transaction et la console

As a partie à une transaction (ou arbitre en litige),
I want lire et écrire les messages directement dans le détail de la transaction,
So that tout l'échange se fasse au même endroit que la timeline et les preuves.

**Acceptance Criteria:**

**Given** le détail d'une transaction (surface livrée, UX-DR23), **When** l'utilisateur ouvre le volet messagerie, **Then** le composant UX-DR18 affiche les messages horodatés avec rôles identifiés (acheteur / vendeur / arbitre), champ de saisie texte seul, **And** un renvoi explicite vers le dossier de preuves remplace tout envoi de fichier (FR-P34), **And** tous les libellés passent par les clés i18n EN/FR (UX-DR40).

**Given** un litige ouvert, **When** l'arbitre consulte le dossier dans sa console (Story 6.2), **Then** le même fil s'affiche intégré au dossier de litige avec les mêmes règles d'affichage (UX-DR18, UX-DR24) — un seul composant réutilisé, jamais deux implémentations.

**Given** une transaction en état terminal, **When** le fil est affiché dans n'importe quel espace, **Then** il est en lecture seule avec la mention de verrouillage datée (FR-P34, UX-DR31), **And** le champ de saisie est désactivé avec explication — jamais masqué sans explication (UX-DR33 pattern).

## Epic 7 : Back-office opérateur
L'opérateur gère utilisateurs et entreprises, supervise les transactions, configure le produit (frais, catégories, SLA, textes légaux, clés HMAC) et traite les tickets support (FR-P15, FR-P35..P38 ; UJ-3, UX-DR16, UX-DR25). Accès via l'authentification et le rôle ADMIN existants (FR-P16) — pas de portail distinct ; garde-fous absolus : aucune impersonation, aucun ajustement de solde en saisie libre.

### Story 7.1: UI de provisioning des clés HMAC partenaire

As a opérateur de la plateforme,
I want générer, révoquer et faire tourner les clés HMAC partenaires depuis le back-office,
So that l'intégration des transporteurs soit gérée sans intervention en base et reste auditable.

**Acceptance Criteria:**

**Given** un opérateur ADMIN dans la surface configuration du back-office, **When** il génère une clé pour un partenaire, **Then** le secret (≥ 32 octets, contrainte V5 existante) est affiché UNE seule fois avec avertissement de copie avant fermeture (FR-P15, UX-DR25), **And** il n'est plus jamais restituable ensuite par aucun endpoint (`@JsonProperty WRITE_ONLY` conservé), **And** la génération est tracée via `AuditService` (AD-5).

**Given** une clé active utilisée par l'endpoint partenaire livré (Story 3.2), **When** l'opérateur la révoque avec motif obligatoire (UX-DR36), **Then** tout appel signé avec cette clé est refusé immédiatement, **And** la révocation motivée est auditée.

**Given** un besoin de rotation, **When** l'opérateur génère une clé de remplacement pour le même partenaire, **Then** la nouvelle clé coexiste avec l'ancienne jusqu'à révocation explicite de celle-ci — rotation sans coupure de service (FR-P15), **And** chaque clé garde son key-id et son cycle de vie propres (modèle `partner_hmac_keys` V4 existant).

**Given** la liste des clés d'un partenaire, **When** l'opérateur la consulte, **Then** seules les métadonnées sont visibles (key-id, partenaire, dates de création/révocation, statut) — jamais le secret (FR-P15).

### Story 7.2: Gestion des utilisateurs et entreprises

As a opérateur de la plateforme,
I want rechercher un utilisateur ou une entreprise, consulter sa fiche et le suspendre de façon motivée,
So that je puisse intervenir sur les comptes sans jamais pouvoir usurper une identité ni toucher un solde.

**Acceptance Criteria:**

**Given** un opérateur ADMIN, **When** il recherche par nom, email ou entreprise, **Then** il obtient une liste paginée (jamais de scroll infini, UX-DR36) menant à une fiche détaillée : statuts de vérification, solde du wallet, transactions associées (FR-P35, UX-DR25).

**Given** une fiche utilisateur ou entreprise, **When** l'opérateur suspend sans motif, **Then** l'action est rejetée ; **When** il suspend avec motif, **Then** la suspension est effective (connexion et actions engageantes bloquées), auditée via `AuditService` (AD-5), **And** la réactivation suit la même règle de motif obligatoire audité (FR-P35).

**Given** la fiche affiche le solde, **When** l'opérateur cherche à le corriger, **Then** AUCUN champ de solde n'est éditable et aucune fonction « Login as » n'existe (FR-P35, UX-DR25), **And** la seule voie de correction est une écriture compensatoire motivée passée au grand livre existant (Epic 4, FR-P3), auditée et visible dans le ledger de l'entreprise (FR-P42).

**Given** la fiche d'un utilisateur, **When** un ADMIN lui octroie ou révoque le rôle plateforme `ARBITRATOR` (AD-21), **Then** l'action exige un motif, est auditée via `AuditService` (AD-5), et prend effet sans reconnexion forcée de l'ADMIN, **And** ni ce parcours ni aucun autre ne permet d'octroyer `ADMIN` ou `ARBITRATOR` à l'inscription (FR-P16 étendu — tests d'API à l'appui) ; cet AC est le **prérequis de la Story 6.1** (console d'arbitrage) et se tire en avance si l'Epic 6 démarre avant l'Epic 7.

**Given** un utilisateur sans rôle ADMIN, **When** il appelle ces endpoints, **Then** 403/404 uniformisé (NFR-P9).

### Story 7.3: Configuration produit versionnée et non rétroactive

As a opérateur de la plateforme,
I want modifier le barème de frais, les catégories, les SLA et les textes légaux avec versionnage,
So that le produit s'ajuste sans jamais changer les conditions des transactions déjà engagées.

**Acceptance Criteria:**

**Given** un opérateur ADMIN dans la surface configuration, **When** il enregistre une modification (barème FR-P24, catégories, délais/SLA FR-P12, textes légaux), **Then** une nouvelle version horodatée avec auteur est créée, les versions antérieures restent consultables, et le changement est audité (FR-P37, UX-DR25).

**Given** des transactions en cours créées sous une version antérieure, **When** une nouvelle version entre en vigueur, **Then** ces transactions conservent leurs conditions d'origine — non-rétroactivité garantie côté serveur (FR-P37), **And** seules les transactions créées après la mise en vigueur appliquent la nouvelle version.

**Given** une saisie invalide (tranches de barème qui se chevauchent, SLA négatif, texte légal vide), **When** l'opérateur enregistre, **Then** le serveur rejette avec erreurs restituées sous les champs concernés et saisie conservée (UX-DR28, UX-DR35).

### Story 7.4: Tickets support avec pièces jointes

As a utilisateur client de la plateforme,
I want ouvrir un ticket support avec pièces jointes et suivre les réponses de l'opérateur,
So that mes problèmes soient traités et tracés sans passer par un canal externe.

**Acceptance Criteria:**

**Given** un utilisateur authentifié, **When** il crée un ticket depuis l'espace Support (UX-DR22, UX-DR23) avec sujet, priorité, description et pièces jointes, **Then** le ticket est créé au statut « ouvert », **And** les pièces jointes réutilisent la brique upload existante avec sa validation serveur stricte et sa restitution en `attachment` (FR-P38, AD-7).

**Given** un ticket ouvert, **When** l'opérateur répond depuis le back-office, **Then** la réponse s'ajoute au fil horodaté, le statut passe à « répondu » et l'utilisateur voit la réponse dans son espace, **And** la clôture passe le ticket à « fermé » (FR-P38).

**Given** la file des tickets côté back-office, **When** l'opérateur l'ouvre, **Then** elle suit le pattern file opérateur : tableau desktop trié par ancienneté avec priorité visible et panneau latéral de traitement (UX-DR16, UX-DR25).

**Given** un utilisateur d'une autre entreprise, **When** il tente d'accéder à un ticket qui n'est pas le sien, **Then** 403/404 uniformisé (AD-3, NFR-P9).

### Story 7.5: Supervision des transactions et vue synthétique d'accueil

As a opérateur de la plateforme,
I want une vue d'accueil des files en attente et une liste de transactions filtrable et exportable,
So that je voie d'un coup d'œil où agir et puisse investiguer n'importe quelle transaction.

**Acceptance Criteria:**

**Given** un opérateur ADMIN, **When** il ouvre l'accueil du back-office `/admin`, **Then** la vue synthétique affiche les compteurs des files en attente — KYB (Epic 3), dépôts de fonds manuels et retraits (Epic 4), litiges (`DISPUTED`, cœur livré), tickets (Story 7.4) — avec badge SLA tricolore et volumes clés (FR-P36, UX-DR16, UX-DR25), **And** chaque compteur navigue vers la file correspondante.

**Given** la surface transactions, **When** l'opérateur filtre par état, corridor ou période, **Then** la liste paginée reflète les filtres avec le code couleur d'état stable (UX-DR2), **And** le détail d'une transaction présente en lecture seule le dossier complet : état, mouvements de fonds, preuves, journal d'audit (FR-P36).

**Given** une liste filtrée, **When** l'opérateur demande l'export, **Then** un CSV des transactions filtrées est produit (FR-P36 `[ASSUMPTION : CSV]`), **And** l'export est tracé en audit (AD-5).

**Given** un utilisateur sans rôle ADMIN, **When** il tente d'accéder à ces surfaces ou endpoints, **Then** 403/404 uniformisé (FR-P36, NFR-P9).

## Epic 8 : Notifications & webhooks fiables

Chaque partie est prévenue au bon moment de ce qui arrive à son argent et à ses transactions : email/SMS sur les transitions clés, notifications in-app (cloche), webhooks partenaires fiabilisés (retry + DLQ + journal), scopés tenant.

### Story 8.1: Spike AR-P6 — choix du fournisseur email/SMS et port d'envoi

As a équipe,
I want trancher le fournisseur de notifications email/SMS adapté au contexte africain et poser le port d'envoi,
So that les stories de notification s'appuient sur une décision documentée et une abstraction stable, sans coupler le domaine à un fournisseur.

**Acceptance Criteria:**

**Given** les candidats fournisseurs email/SMS évalués sur les corridors cibles ZLECAf
**When** le spike AR-P6 se conclut
**Then** une décision est documentée (délivrabilité SMS par corridor, couverture des indicatifs, coûts, API, conformité)
**And** les critères et alternatives écartées sont tracés dans l'artefact de décision.

**Given** la décision rendue
**When** le port d'envoi est implémenté
**Then** une interface unique (type `NotificationSender`) isole le fournisseur derrière un adaptateur, avec un mode sandbox pour les environnements hors production
**And** un envoi de test (email et SMS) est prouvé via l'adaptateur, identifiants externalisés conformément à NFR-P1.

**Given** un échec du fournisseur (timeout, 5xx)
**When** un envoi est tenté via le port
**Then** l'échec est journalisé avec corrélation et n'interrompt jamais le flux métier appelant.

### Story 8.2: Notifications email/SMS sur transitions clés (FR-P17)

As a partie prenante d'une transaction escrow,
I want être prévenu par email (et SMS pour les événements critiques) à chaque transition clé,
So that je sache sans ouvrir l'application que mes fonds ou mes obligations ont changé d'état.

**Acceptance Criteria:**

**Given** une transition clé du cycle de vie (`FUNDS_LOCKED`, `SHIPPED`, `RELEASED`, `DISPUTED`, `REFUNDED`, expiration)
**When** la machine à états l'exécute
**Then** un email est envoyé aux destinataires concernés via le port de la Story 8.1, dans la langue de l'utilisateur (clés i18n EN/FR, NFR-P24)
**And** les transitions critiques (litige, libération, remboursement) déclenchent aussi un SMS.

**Given** un fournisseur indisponible au moment de la transition
**When** l'envoi échoue
**Then** la transition métier est déjà commise (envoi asynchrone découplé) et l'envoi est retenté
**And** l'échec définitif est journalisé sans bloquer la transaction.

**Given** un envoi effectué
**When** on consulte le journal des notifications
**Then** chaque envoi porte destinataire, canal, événement déclencheur, horodatage et statut, sans exposer de secret ni de contenu sensible en clair.

### Story 8.3: Webhooks sortants fiables — retry, DLQ, journal, abonnement self-service (FR-P18)

As a partenaire intégré au tenant,
I want m'abonner en self-service à des webhooks fiables sur les événements de mes transactions,
So that mon système d'information reste synchronisé même en cas de panne transitoire de mon endpoint.

**Acceptance Criteria:**

**Given** un utilisateur habilité d'une entreprise
**When** il crée, consulte ou révoque un abonnement webhook (URL + événements souscrits)
**Then** l'abonnement est scopé à son tenant et le secret de signature n'est affiché qu'une seule fois à la création
**And** les livraisons sont signées via le `HmacSigner` constant-time existant.

**Given** un événement souscrit émis pour un tenant
**When** la livraison est tentée
**Then** seuls les abonnements de CE tenant reçoivent l'événement (aucune fuite inter-tenant)
**And** un échec déclenche des retries avec backoff exponentiel.

**Given** l'épuisement des retries
**When** la livraison échoue définitivement
**Then** l'événement est déposé en DLQ avec son contexte, sans perte
**And** un opérateur peut le consulter et le rejouer.

**Given** un abonné
**When** il consulte le journal de livraison de son abonnement
**Then** il voit chaque tentative (horodatage, code réponse, statut final), limité aux événements de son propre tenant.

### Story 8.4: Notifications in-app — cloche et liste (FR-P39, UX-DR19)

As a utilisateur connecté,
I want une cloche avec badge de non-lus et une liste de mes notifications,
So that je retrouve dans l'application les événements qui me concernent et navigue directement vers la surface visée.

**Acceptance Criteria:**

**Given** une transition clé ou un nouveau message sur une de mes transactions
**When** l'événement survient
**Then** une notification in-app est créée pour moi et le badge de non-lus de la cloche s'incrémente (UX-DR19).

**Given** la cloche ouverte
**When** la liste s'affiche
**Then** les notifications apparaissent en ordre antéchronologique avec libellé i18n et horodatage
**And** un clic navigue vers la surface concernée (détail transaction, litige, wallet) et marque la notification lue.

**Given** le périmètre MVP
**When** l'utilisateur n'a pas l'application ouverte
**Then** aucun push navigateur n'est émis (hors MVP, FR-P39) — les canaux email/SMS de la Story 8.2 couvrent ce cas
**And** la cloche est accessible clavier avec contrastes conformes (UX-DR38).

## Epic 9 : Offline complet

La PWA tient ses promesses en réseau instable : rejeu idempotent comme fondation, file déclenchée sur échec réseau réel sans perte de binaire, cache de consultation horodaté, versement de preuve hors-ligne, et frontière financière explicite.

### Story 9.1: Idempotence du rejeu de la file offline (NFR-P10)

As a plateforme,
I want que le rejeu de toute action mise en file soit idempotent via une `Idempotency-Key`,
So that une réponse perdue ou un rejeu concurrent ne crée jamais de doublon — la fondation qui rend sûre la mise en file sur échec réseau.

**Acceptance Criteria:**

**Given** une entrée de file offline créée côté client
**When** elle est enregistrée dans IndexedDB
**Then** elle porte une `Idempotency-Key` unique générée à la création, persistée avec l'entrée (survit au redémarrage du navigateur, AD-9).

**Given** une requête rejouée portant une `Idempotency-Key` déjà traitée
**When** le serveur la reçoit
**Then** il renvoie le même résultat que le premier traitement, sans créer de doublon (versement de preuve ou litige)
**And** l'audit ne trace qu'une seule opération métier.

**Given** deux rejeux concurrents de la même entrée
**When** ils atteignent le serveur simultanément
**Then** une seule création aboutit, prouvé par un test d'intégration concurrent sur base réelle (pattern Testcontainers existant).

### Story 9.2: File sur échec réseau réel, sans perte de binaire (FR-P19)

As a utilisateur sur réseau instable,
I want que mes actions soient mises en file quand la requête échoue réellement — même si `navigator.onLine` ment,
So that aucun fichier ne soit perdu sur portail captif ou réseau dégradé, et que tout reparte à la reconnexion.

**Acceptance Criteria:**

**Given** un envoi parti « en ligne » qui échoue faute de réseau réel (portail captif, timeout, coupure en cours de transfert)
**When** l'échec est détecté
**Then** l'action et ses binaires sont enregistrés en UNE entrée IndexedDB atomique (AD-9), sans perte d'aucun octet
**And** aucune double-soumission n'est possible grâce à l'`Idempotency-Key` posée en Story 9.1.

**Given** au moins un élément en file
**When** l'utilisateur navigue dans l'application
**Then** la bannière offline pleine largeur affiche le compteur d'éléments en file — seul indicateur global offline (UX-DR13).

**Given** le retour du réseau
**When** le rejeu démarre
**Then** la progression est visible élément par élément, un conflit (état devenu terminal, transition côté serveur) est notifié sans écrasement silencieux (UX-DR45)
**And** l'écran de récupération offline-reject existant est conservé pour les rejets permanents (AD-10).

### Story 9.3: Cache local du détail de transaction (FR-P20, UX-DR43)

As a utilisateur hors-ligne,
I want revoir le détail d'une transaction déjà consultée, avec mes actions en attente,
So that un rechargement sans réseau ne remplace pas mon dossier par un panneau d'erreur.

**Acceptance Criteria:**

**Given** un détail de transaction consulté en ligne
**When** la vue est rechargée sans réseau
**Then** le détail (timeline, preuves, litige) est restitué depuis le cache local avec l'horodatage « données au… » (UX-DR43).

**Given** des éléments en file concernant cette transaction (preuve, litige)
**When** le détail se monte hors-ligne
**Then** ils apparaissent dans le dossier avec leur statut « en file » — l'action optimiste ne disparaît pas au rechargement.

**Given** des données financières dans la vue en cache (montants, soldes, frais)
**When** elles sont affichées hors-ligne
**Then** seule la dernière valeur connue horodatée est montrée, sans aucune projection optimiste financière (UX-DR43, UX-DR9).

### Story 9.4: Versement de preuve simple hors-ligne (FR-P21)

As a utilisateur hors-ligne,
I want verser une preuve simple (hors litige) sans réseau,
So that le hors-ligne couvre tout le versement de preuves, promesse centrale de la PWA.

**Acceptance Criteria:**

**Given** une transaction dont l'état autorise le versement (fenêtre fondée sur l'état, AD-2) et l'absence de réseau
**When** l'utilisateur verse une preuve simple
**Then** elle est mise en file (entrée atomique portant le binaire) avec affichage optimiste dans le dossier, sans message « échec de versement ».

**Given** la reconnexion
**When** la file est rejouée
**Then** la preuve est créée exactement une fois (Idempotency-Key, Story 9.1), son état visible progresse « en file → envoi → confirmé/rejeté » (UX-DR44)
**And** l'horodatage serveur fait foi, l'heure client étant conservée en audit (AD-11).

**Given** un rejet permanent au rejeu (état devenu terminal, fichier refusé par la validation serveur)
**When** la réconciliation s'exécute
**Then** l'élément est marqué rejeté avec motif et action de reprise, sans bloquer le rejeu des autres éléments (AD-10, UX-DR45).

### Story 9.5: Plafond 20 fichiers miroité et frontière financière explicite (FR-P22, FR-P40, UX-DR42)

As a utilisateur hors-ligne,
I want connaître immédiatement les limites — plafond de fichiers et actions impossibles sans connexion,
So that je ne mette jamais en file une action vouée au rejet ni ne croie avoir engagé de l'argent hors-ligne.

**Acceptance Criteria:**

**Given** une sélection de plus de 20 fichiers pour un versement
**When** l'utilisateur valide
**Then** le front rejette immédiatement avec un message miroir du plafond serveur (FR-P22), sans mise en file
**And** le comportement est identique en ligne et hors-ligne.

**Given** l'application hors-ligne
**When** l'utilisateur atteint une action financière (financer, libérer, déposer des fonds, retirer)
**Then** l'action est visible mais désactivée avec l'explication in situ que les actions financières exigent une connexion (FR-P40, UX-DR42)
**And** rien de financier n'est jamais mis en file.

**Given** l'application hors-ligne
**When** l'utilisateur consulte, verse une preuve ou ouvre un litige
**Then** ces parcours restent pleinement fonctionnels — la frontière offline est rappelée sur les surfaces concernées, pas seulement dans la bannière globale (UX-DR42).

## Epic 10 : Site public

Un prospect comprend la proposition de valeur sans être connecté et s'inscrit ; les pages légales sont publiées, versionnées et éditables par l'opérateur — sans blog, simulateur, contact ni newsletter (hors MVP).

### Story 10.1: Landing publique et portes d'entrée (FR-P41, UX-DR21)

As a prospect,
I want une landing simple qui explique le séquestre et me mène à l'inscription,
So that je comprenne la proposition de valeur et démarre sans friction.

**Acceptance Criteria:**

**Given** un visiteur non authentifié
**When** il ouvre la racine du site
**Then** une landing s'affiche sans authentification : proposition de valeur, étapes du séquestre, appels à l'action « S'inscrire » et « Se connecter » menant aux parcours existants de l'espace public (inscription + OTP, connexion, réinitialisation — UX-DR21).

**Given** le périmètre MVP de FR-P41
**When** on parcourt le site public
**Then** aucun blog, simulateur de frais, formulaire de contact ni inscription newsletter n'existe — ni page, ni lien mort.

**Given** la landing rendue
**When** elle est affichée en EN ou FR, sur mobile bas débit
**Then** les textes viennent des clés i18n (NFR-P24, UX-DR40), les tokens de design de l'app sont réutilisés (UX-DR1..6)
**And** le budget de poids bas débit est respecté (UX-DR41), contrastes WCAG 2.2 AA (UX-DR38).

### Story 10.2: Pages légales éditables via la configuration produit (FR-P41, FR-P37)

As a opérateur de la plateforme,
I want publier et mettre à jour les pages légales depuis la configuration produit,
So that les textes légaux évoluent sans redéploiement, avec versionnage et traçabilité.

**Acceptance Criteria:**

**Given** les textes légaux saisis dans la configuration produit versionnée (Epic 7, FR-P37)
**When** un visiteur non authentifié ouvre une page légale (CGU, confidentialité, mentions légales)
**Then** la version actuellement publiée est rendue, accessible depuis la landing et le pied de page public.

**Given** un opérateur modifiant un texte légal dans le back-office
**When** il publie la nouvelle version
**Then** la page publique la reflète sans redéploiement
**And** l'édition est versionnée et auditée, les versions antérieures restant consultables (FR-P37).

**Given** le parcours d'inscription (UX-DR21)
**When** l'utilisateur donne son consentement légal horodaté (FR-P27)
**Then** les documents référencés sont les versions publiées au moment du consentement, et cette version est tracée avec le consentement.

## Epic 11 : Opérabilité, qualité & déploiement

La plateforme se déploie, s'observe, se sauvegarde et se teste — condition du lancement commercial. Piste parallèle à démarrer dès maintenant : la CI ouvre le chantier (le gate est le prérequis de la résorption continue de la dette de tests), puis la configuration et la cible de déploiement (spikes AR-P4, AR-P5) débloquent la stack durcie, l'observabilité, la sauvegarde et les campagnes de tests. Porte NFR-P11..P21 et NFR-P23 ; aucun FR. Fusionne les découpages éprouvés des anciens Epics 5 (opérabilité) et 10 (qualité) du backlog `epics-production.md`.

### Story 11.1: Pipeline CI/CD avec gate de tests et scan de vulnérabilités

As a équipe,
I want que chaque pull request soit buildée, testée et scannée avec un gate bloquant avant merge,
So that aucune régression ni vulnérabilité connue n'atteigne la branche principale, et que la dette de tests se résorbe en continu sous protection du gate (NFR-P11, NFR-P12).

**Acceptance Criteria:**

**Given** une pull request vers la branche principale
**When** la CI s'exécute
**Then** build + tests backend (Maven/Testcontainers) + tests frontend (Vitest) tournent automatiquement
**And** tout échec bloque le merge (gate obligatoire, non contournable par défaut).

**Given** le pipeline CI
**When** il s'exécute
**Then** un SBOM des dépendances (backend + frontend) et des images Docker est produit et archivé comme artefact
**And** le scan de vulnérabilités échoue le pipeline au-delà du seuil de sévérité convenu (CRITICAL/HIGH sans exemption documentée et datée).

**Given** les suites de tests existantes du dépôt (backend Testcontainers + frontend Vitest/fake-indexeddb)
**When** la CI tourne pour la première fois sur `develop`
**Then** la suite complète passe verte dans l'environnement CI (Docker disponible pour Testcontainers)
**And** la preuve d'un merge bloqué par un test rouge est apportée sur une PR de démonstration.

### Story 11.2: Configuration externalisée, profils multi-environnements et gestion des secrets

As a exploitant,
I want des profils dev/staging/prod avec configuration et secrets injectés à l'exécution via une solution de gestion de secrets tranchée,
So that une même image serve tous les environnements sans rebuild et sans secret embarqué (NFR-P16, spike AR-P4).

**Acceptance Criteria:**

**Given** le spike AR-P4 (gestion des secrets), timeboxé en ouverture de story
**When** il se conclut
**Then** un addendum d'architecture documente la solution retenue (outil, mécanisme d'injection, procédure de rotation)
**And** la décision est appliquée dans cette même story, pas différée.

**Given** une image unique du backend et du frontend
**When** elle démarre avec le profil dev, staging ou prod
**Then** toute la configuration (URLs, credentials, URL d'API base du frontend, endpoints stockage/broker) est injectée à l'exécution
**And** changer d'environnement n'exige aucun rebuild d'image.

**Given** un déploiement staging ou prod
**When** on inspecte images, compose/manifestes et dépôt git
**Then** aucun secret n'y figure en clair — tous proviennent de la solution AR-P4
**And** la rotation d'un secret (ex. JWT) est exécutée une fois en staging et documentée, sans interruption de service non planifiée.

### Story 11.3: Stack de production durcie et hébergement décidé par corridor

As a exploitant,
I want une stack de production durcie derrière un reverse-proxy TLS, déployée sur une cible tranchée dans des régions conformes,
So that le déploiement soit robuste, non exploitable par ses consoles d'administration, et licite au regard de la localisation des données financières (NFR-P15, NFR-P23, spike AR-P5).

**Acceptance Criteria:**

**Given** le spike AR-P5 (cible de déploiement), timeboxé en ouverture de story
**When** il se conclut
**Then** un addendum d'architecture documente la cible retenue (orchestration, ingress/reverse-proxy TLS, registre d'images versionnées)
**And** la décision d'hébergement désigne la ou les régions par corridor visé au regard des principes UA/Malabo et de la localisation des données financières (NFR-P23), régions où résident toutes les données persistées (Postgres, stockage objet, sauvegardes).

**Given** la configuration de production issue du spike
**When** la stack complète est déployée (backend, frontend, Postgres, stockage objet, broker)
**Then** politiques de redémarrage et limites CPU/mémoire sont posées sur chaque service
**And** les images sont versionnées (tag immuable, pas de `latest`) et publiées dans le registre.

**Given** l'environnement déployé
**When** on le scanne depuis l'extérieur
**Then** seuls le frontend et l'API sont joignables via le reverse-proxy TLS
**And** aucune console d'administration (Adminer, pgAdmin, consoles MinIO/RabbitMQ, Swagger — cohérent NFR-P4) n'est exposée publiquement.

### Story 11.4: Observabilité — métriques, logs corrélés, tracing et alerting

As a exploitant,
I want des métriques, des logs corrélés, du tracing et des alertes actionnables,
So that je détecte et diagnostique les incidents — y compris tout écart de ségrégation des fonds — sans dépendre des signalements clients (NFR-P13, alerte de l'invariant NFR-P25).

**Acceptance Criteria:**

**Given** l'application en fonctionnement
**When** on interroge la pile d'observabilité
**Then** des métriques (JVM, HTTP, pool Hikari, files du broker) sont exposées et scrapées, restituées dans un dashboard
**And** les logs sont structurés (JSON) avec un identifiant de corrélation propagé — la recherche par cet identifiant restitue toutes les lignes d'une même requête, écriture d'audit comprise.

**Given** un parcours chaud (versement de preuve, du contrôleur au stockage objet)
**When** une requête le traverse
**Then** une trace de bout en bout est consultable dans l'outil de tracing, corrélée aux logs par le même identifiant.

**Given** le canal d'alerting configuré
**When** une alerte de test est déclenchée
**Then** elle est délivrée sur le canal opérateur (preuve de bout en bout)
**And** les règles minimales sont actives : taux de 5xx, DLQ non vide, saturation disque/pool.

**Given** la règle d'alerte de l'invariant de ségrégation des fonds (NFR-P25 : écart grand livre ↔ compte cantonné)
**When** une valeur d'écart non nulle est injectée sur la métrique de rapprochement (valeur factice de test)
**Then** une alerte de sévérité critique est délivrée immédiatement
**And** la règle est en place avant la mise en service du circuit financier (Epic 4), qui n'aura qu'à émettre la métrique.

### Story 11.5: Sauvegarde et restauration testées

As a exploitant,
I want des sauvegardes automatiques de Postgres et du stockage objet, et une restauration prouvée par l'exercice,
So that aucune donnée financière ou légale ne soit perdue et que le retour à la normale soit borné dans le temps (NFR-P14).

**Acceptance Criteria:**

**Given** le planning de sauvegarde
**When** il s'exécute
**Then** des sauvegardes quotidiennes Postgres + stockage objet sont produites, chiffrées et répliquées hors du serveur d'origine (dans les régions décidées en NFR-P23)
**And** leur intégrité est vérifiée automatiquement après chaque exécution (échec = alerte via la Story 11.4).

**Given** un environnement vierge et la procédure de restauration documentée
**When** on restaure base + objets depuis la dernière sauvegarde
**Then** la plateforme redémarre avec un état cohérent (transactions, preuves, écritures d'audit alignées ; les preuves restaurées se téléchargent avec checksum identique)
**And** l'exercice, répété au moins deux fois, aboutit à chaque fois en < 30 minutes (RTO), avec perte maximale ≤ 24 h (RPO) `[ASSUMPTION]`.

**Given** la procédure validée
**When** on la consulte
**Then** elle est versionnée dans le dépôt, exécutable par un opérateur qui ne l'a pas écrite
**And** un exercice de restore est planifié à échéance récurrente (au minimum trimestrielle) `[ASSUMPTION]`.

### Story 11.6: Résilience des dépendances et maîtrise de la croissance de l'audit

As a exploitant,
I want des appels résilients vers le stockage objet et le broker, des pools dimensionnés, et une table d'audit partitionnée,
So that un blip transitoire ne casse pas les requêtes et que `audit_logs` reste performante sans jamais violer la rétention légale (NFR-P17, NFR-P18).

**Acceptance Criteria:**

**Given** un blip transitoire du stockage objet ou du broker (simulé en test)
**When** un appel échoue
**Then** un retry avec timeout et back-off est appliqué avant d'échouer proprement (5xx contrôlé, sans fuite de détail interne)
**And** le pool Hikari est dimensionné explicitement et sa saturation est observable (métrique Story 11.4).

**Given** une indisponibilité prolongée d'une dépendance
**When** les requêtes continuent d'arriver
**Then** le health check passe DOWN et les requêtes échouent vite (timeout borné)
**And** aucun épuisement de threads n'est observé (prouvé par test de panne).

**Given** la table `audit_logs`
**When** la migration de partitionnement est appliquée
**Then** la table est partitionnée (par période) avec une politique d'archivage automatisée des partitions anciennes
**And** aucune purge n'est possible avant l'échéance de rétention légale (≥ 5 ans — cohérent FR-P33/WORM), l'archive restant restaurable.

### Story 11.7: Tests E2E offline→online et résorption de la dette de tests

As a équipe,
I want le parcours PWA offline→online automatisé de bout en bout et les fragilités de tests connues éliminées,
So that le cœur métier hors-ligne soit protégé par la CI et que la suite soit fiable, pas trompeuse (NFR-P20, NFR-P21).

**Acceptance Criteria:**

**Given** un scénario E2E (Playwright) couvrant versement de preuve et ouverture de litige hors-ligne puis reconnexion
**When** il s'exécute
**Then** il vérifie mise en file IndexedDB, rejeu idempotent, réconciliation et affichage final, sans perte ni doublon
**And** il tourne dans le gate CI de la Story 11.1 (échec = merge bloqué).

**Given** la dette de tests héritée du ledger
**When** la story se termine
**Then** la garde dépendant du chemin `backend/` relatif skip proprement avec message explicite dans un checkout CI sans backend, la clé `TOKEN_STORAGE_KEY` n'est plus recopiée en dur dans les mocks, et le commentaire trompeur de `SyncFailureNotice.spec.js` est corrigé
**And** la couverture des composants identifiés comme non couverts est comblée.

**Given** la suite complète (unitaires + E2E)
**When** elle tourne trois fois consécutives en CI
**Then** elle est verte trois fois sans re-run manuel (zéro test flaky toléré dans le gate).

### Story 11.8: Tests de charge sur les parcours chauds

As a équipe,
I want mesurer débit et latence des parcours chauds contre des objectifs chiffrés,
So that le dimensionnement (pools, JVM, ressources) repose sur des mesures et non sur l'aveugle (NFR-P19).

**Acceptance Criteria:**

**Given** des scénarios de charge versionnés dans le dépôt (versement de preuve multipart, ouverture de litige, téléchargement, endpoint partenaire HMAC)
**When** ils s'exécutent contre l'environnement staging (stack de la Story 11.3)
**Then** débit et latences p95/p99 sont mesurés et comparés à des objectifs documentés (ex. p95 < 500 ms sur le versement de preuve à 50 utilisateurs concurrents `[ASSUMPTION]`)
**And** tout écart aux objectifs est consigné avec une action de dimensionnement décidée.

**Given** la campagne de charge
**When** elle tourne
**Then** les métriques d'observabilité (Story 11.4) permettent d'identifier le goulot (pool, CPU, stockage objet, broker)
**And** les réglages retenus (taille de pool Hikari, mémoire JVM, limites conteneurs) sont appliqués et documentés.

**Given** les scripts de charge
**When** un membre de l'équipe les relance
**Then** la campagne est reproductible en une commande documentée, avec rapport de sortie comparable d'une exécution à l'autre.

### Story 11.9: Migrations de runtime — Spring Boot 4.1, RabbitMQ 4.2 LTS, backend objet définitif

*(Ajoutée le 2026-07-24 — sortie du gate d'architecture : trois composants du socle sont en fin de vie, vérifié web. **Exigence de lancement** : planifiable en parallèle, bloquante avant production.)*

As a exploitant,
I want un socle d'exécution entièrement supporté (framework, broker, stockage objet),
So that la plateforme ne parte pas en production sur des lignes EOL sans correctifs de sécurité.

**Acceptance Criteria:**

**Given** le backend sur Spring Boot 3.3.5 (ligne 3.x EOL au 30/06/2026)
**When** la migration vers Spring Boot 4.1.x est menée (Framework 7, springdoc, hypersistence-utils inclus)
**Then** l'application démarre et l'intégralité de la suite de tests (Testcontainers compris) passe verte sans régression
**And** les invariants hérités sont re-vérifiés (machine à états, audit MANDATORY, enveloppe d'erreur + `code`).

**Given** RabbitMQ 3.13 (ligne 3.x hors support depuis 07/2025)
**When** le broker est migré vers RabbitMQ 4.2 LTS
**Then** webhooks sortants, DLQ et relais outbox (AD-22) fonctionnent à l'identique, prouvé par les tests d'intégration
**And** la procédure de migration (compose + données) est documentée et rejouable.

**Given** le pin MinIO actuel (binaire orphelin — dépôt OSS archivé en 04/2026)
**When** le backend objet définitif est choisi et déployé (AWS S3 managé, Garage, SeaweedFS… — décision documentée en ADR)
**Then** la bascule s'effectue par le seul adaptateur du port `EvidenceStorage` (AD-6) sans toucher aux endpoints
**And** les objets existants sont migrés avec vérification de checksum, restitution et cleanup-rollback re-testés.

**Given** la fenêtre de migration ouverte
**When** le frontend est traité
**Then** le rattrapage Vite 7 / Pinia 3 est tenté dans la même fenêtre (non bloquant : en cas d'obstacle, il est consigné et découplé)
**And** la CI (Story 11.1) reste verte sur l'ensemble.
