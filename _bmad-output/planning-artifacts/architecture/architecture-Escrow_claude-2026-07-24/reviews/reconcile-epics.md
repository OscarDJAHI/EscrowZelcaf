# Réconciliation epics.md ↔ ARCHITECTURE-SPINE (2026-07-24)

**Entrée :** `_bmad-output/planning-artifacts/epics.md` (11 epics / 63 stories, AD-1..12 hérités, spikes AR-P1..P6)
**Spine :** `architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md` (AD-13..27, conventions, deferred)
**Méthode :** lecture intégrale des deux documents ; ne sont rapportés que les manques (hypothèse technique d'une story sans couverture AD/convention alors qu'elle devrait être un invariant), les contradictions avec un AD, et les incompatibilités de séquencement. Ce qui est correctement couvert ou explicitement déféré n'est pas listé.

---

## Constats majeurs

### M1 — Auto-remboursement non-expédition : transition absente de l'énumération AD-19
Story 5.4 (AC3) exige, sur échéance FR-P12, le passage d'une transaction `FUNDS_LOCKED` non expédiée vers `REFUNDED` (auto-remboursement). AD-19 fige la machine à `INITIATED→FUNDS_LOCKED→SHIPPED→RELEASED` + branches `DISPUTED→RELEASED/REFUNDED` et affirme que les jobs SLA « passent par les transitions existantes ». La transition `FUNDS_LOCKED→REFUNDED` hors litige n'existe pas dans cette énumération : soit AD-19 doit l'admettre explicitement, soit la story contredit le spine.

### M2 — État terminal « expirée » sans représentation ratifiée
Stories 5.2 (transaction acceptée non financée « expire », invitation expirée ⇒ transaction « close en état neutre »), 5.5 et 6.3 (« état terminal (`RELEASED`, `REFUNDED`, expirée) », verrouillage du dossier et du fil) supposent un état terminal « expirée » porteur des mêmes effets de verrou que les états machine. AD-19 ne couvre que les statuts d'invitation en amont (« jamais de nouveaux états machine ») et ne dit pas comment une transaction `INITIATED` expirée devient terminale-verrouillée (état machine ? statut porté par la transaction avec effet de verrou AD-2/AD-4 ?). Invariant manquant.

### M3 — Story de migration Spring Boot 4.1.x absente du backlog
Le spine (Stack + Deferred) fait de la migration Spring Boot 3.3.5 → 4.1.x une **exigence de lancement** avec « story à ajouter à l'Epic 11 ». L'Epic 11 (8 stories, 11.1–11.8) ne contient aucune story de migration : l'exigence n'a pas atterri dans epics.md.

### M4 — Octroi/révocation du rôle ARBITRATOR : aucune story porteuse
AD-21 impose `ARBITRATOR` octroyé/révoqué par un ADMIN via la gestion des utilisateurs, action motivée et auditée. Story 6.1 présuppose des comptes arbitres (« rôle arbitre octroyé par la plateforme uniquement ») mais la Story 7.2 (gestion utilisateurs/entreprises) ne comporte aucun AC d'octroi de rôle. Aucune des 63 stories n'implémente le mécanisme exigé par AD-21 — l'Epic 6 n'est donc pas réellement autonome.

### M5 — Notifications promises en Epic 5 alors que l'infrastructure AD-22 arrive en Epic 8
Stories 5.2 (« les deux parties sont notifiées ») et 5.4 (« les deux parties étant notifiées ») exigent des notifications sur expiration/auto-libération. AD-22 impose l'outbox transactionnel + port `NotificationSender` (spike 8.1) pour tout événement notifiable, or le séquencement place l'Epic 8 **après** l'Epic 5 (« 6, 7, 8 après 5 ») et affirme « chaque epic n'exige aucun epic futur ». Soit les AC de 5.2/5.4 sont inexécutables au moment de l'Epic 5, soit l'Epic 5 doit poser l'outbox (non prévu dans ses stories) — dépendance non résolue.

### M6 — Idempotence du rejeu offline (NFR-P10, Story 9.1) sans AD porteur
Story 9.1 exige la déduplication serveur par `Idempotency-Key` du rejeu des **preuves et litiges** (avec preuve de concurrence sur base réelle). NFR-P10 est bindé sur AD-18, dont la règle ne régit que les opérations **financières** — précisément celles qu'AD-27 interdit de mettre en file. L'invariant serveur « même clé rejouée = même résultat, une seule création » pour les actions non financières (versement, litige) n'est couvert par aucun AD (AD-9/AD-10 hérités couvrent la file et la réconciliation, pas la déduplication serveur). Devrait être un invariant.

### M7 — AD-16 « critère d'entrée » non séquencé
AD-16 exige que la règle d'alerte de ségrégation soit en place **avant la mise en service du circuit financier** (« critère d'entrée, pas d'epic ») ; Story 11.4 (AC4) la porte. Or la piste zéro ne tire que 11.1-lite, l'Epic 11 est « piste parallèle » sans jalon, et l'Epic 4 est sur le chemin critique : rien dans le séquencement n'ordonne 11.4 (ou au minimum sa règle d'alerte) avant la fin de l'Epic 4. Le critère d'entrée du spine n'est pas traduit en contrainte d'ordonnancement.

---

## Constats mineurs

### m1 — Chiffrement au repos : périmètre insuffisant pour les nouvelles données sensibles
NFR-P6/Story 1.7 couvrent preuves + secrets HMAC/webhook. Les stories introduisent d'autres données sensibles au repos sans invariant : secrets TOTP et codes de récupération (2.6), coordonnées bancaires/mobile money des bénéficiaires « saisies et conservées » (4.9). Aucun AD ni convention ne les protège (chiffrement, restitution masquée).

### m2 — Révocation JWT/refresh côté serveur : mécanisme absent du spine
Stories 1.6 et 2.6 exigent une révocation serveur effective des JWT/refresh tokens (y compris révocation de toutes les sessions au reset). C'est un choix d'architecture transverse (état de session, liste de révocation, modèle refresh) que le spine ne fixe nulle part.

### m3 — Rate-limiting : localisation non conventionnée
Stories 1.3 (login/register), 2.4 (renvoi OTP, tentatives), 2.6 (tentatives TOTP) supposent un mécanisme de limitation ; le spine ne dit pas où il vit (reverse-proxy vs applicatif) ni s'il est partagé — risque d'implémentations parallèles.

### m4 — Langue des nouveaux statuts non tranchée
Les stories nomment des états en français accentué (`EN_RAPPROCHEMENT`, `REJETÉE`, `EN_ATTENTE_APPROBATION`, `EXÉCUTÉ`, `ECHEC_PAYOUT` — 4.5/4.9) alors que les enums existants sont en anglais et qu'AD-23 impose des codes machine traduits côté frontend. La convention de nommage (« enums VARCHAR + CHECK ») ne fixe pas la langue ; à ratifier (codes machine anglais attendus).

### m5 — Story 4.1 (spike AR-P2) partiellement périmée par le spine
L'AC4 de 4.1 demande d'« amender l'ARCHITECTURE-SPINE avec les nouveaux invariants comptables » — invariants désormais déjà posés (AD-13..16) ; à reformuler en validation contre le partenaire retenu (cohérent avec le Deferred AR-P2). Par ailleurs le plan de comptes exigé par 4.1 inclut un « compte de fonds en transit PSP » absent de la formule de rapprochement d'AD-16 (`wallets + séquestres + réservations = miroir`) — si ce compte porte un solde, la formule est fausse ou incomplète.

### m6 — `scheduler/` et jobs planifiés hors cycle escrow
La Capability Map référence `scheduler/` (cycle escrow & SLA) mais l'arbre source « ajouts production » ne le contient pas. Les autres jobs planifiés supposés par les stories — re-screening AML périodique (3.5), contrôle de rapprochement planifié (4.3/AD-16) — n'ont pas de rattachement structurel explicite.

### m7 — Site public absent de la Capability Map
FR-P41 (Epic 10) n'a aucune ligne dans la Capability Map ; le rendu **non authentifié** des textes légaux depuis la configuration versionnée (10.2) suppose un endpoint public de lecture de `config_versions` qu'aucune convention ne prévoit (les endpoints admin sont cadrés, pas la lecture publique).

### m8 — Récapitulatif téléchargeable (5.5) : brique de génération inexistante
La génération d'un document récapitulatif (parties, frais, chronologie, inventaire CoO) n'a ni composant, ni format, ni emplacement dans l'arbre source du spine. Seules la restitution `attachment` (AD-7) et l'autorisation (AD-3) sont couvertes — le générateur lui-même est laissé au code sans l'avoir dit.

### m9 — Invariants prouvés par tests dans les stories mais laissés au code sans AD
À confirmer comme « sciemment laissés au code » : usage unique des jetons sous soumissions concurrentes (2.5 invitation, 2.6 reset), non-double-assignation d'un litige (6.1), secret d'abonnement webhook affiché une seule fois (8.3 — le spine ne l'élève que pour les clés HMAC partenaires en 7.1). L'ERD « cœur » ne les esquisse pas, ce qui est admis (« détail aux migrations »), mais aucun de ces invariants n'est normatif.

### m10 — Story 1.4 (TLS/HSTS, piste P0) devance la décision d'infra AR-P5
Story 1.4 présuppose un reverse-proxy de production alors que l'ingress/TLS n'est tranché qu'en 11.3 (spike AR-P5). Risque de double travail ou de livraison provisoire ; le séquencement ne dit pas laquelle des deux stories possède le reverse-proxy.

---

## Séquencement (vérification demandée)

- **Piste zéro** (11.1-lite + spikes AR-P2/AR-P1/AR-P3 + conversation bancaire) : compatible avec le spine (ports fixés, spikes = décisions d'adaptateurs, Deferred aligné sur les stories 4.1/4.2/3.1/11.2/11.3/8.1) — sous réserve de m5 (AC de 4.1 à rafraîchir) et de M7 (AD-16 non ordonné).
- **Composant file opérateur unique (3.3 → 4.5, 4.9, 7.4, 7.5)** : ratifié à l'identique dans les conventions frontend du spine — conforme (6.1 réutilise aussi le pattern, cohérent avec « réutilisé partout »).
- **Borne démo pilote sandbox (post-5.4)** : couverte par AD-17 (« mode sandbox par profil, la démo pilote tourne en sandbox ») — conforme ; seule réserve : M5 (notifications) et M7 (alerte AD-16 si la « mise en service » est interprétée comme incluant le pilote).
