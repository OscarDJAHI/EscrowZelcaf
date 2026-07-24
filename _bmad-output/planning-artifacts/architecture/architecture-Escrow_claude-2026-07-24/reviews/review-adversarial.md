# Revue adversariale — ARCHITECTURE-SPINE Escrow ZLECAf (2026-07-24)

- **Cible** : `architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md` (AD-13..27 + AD-1..12 hérités)
- **Corpus confronté** : spine parent Evidence Upload (2026-07-15), backlog `epics.md` (11 epics / 63 stories)
- **Méthode** : pour chaque zone, construire une **paire d'unités** (deux epics ou deux stories d'epics différents) dont chaque membre respecte **à la lettre** tous les AD, et montrer qu'elles se construisent de façon incompatible. Chaque paire = un trou à fermer par un AD nouveau ou resserré.
- **Verdict global** : le spine est solide sur ses invariants financiers profonds (AD-13..18 se verrouillent mutuellement), mais **3 paires critiques** et **5 majeures** laissent des unités conformes se contredire — dont une contradiction frontale entre la machine à états « inchangée » (AD-19) et les auto-remboursements SLA (Story 5.4), qui ne peut pas être résolue par les développeurs sans violer soit AD-19, soit AD-1.

---

## CRITIQUE

### C1 — Auto-remboursement SLA (Story 5.4, Epic 5) × arbitrage (Story 6.2, Epic 6) : la transition `FUNDS_LOCKED → REFUNDED` n'existe pas dans la machine « inchangée »

**La paire.** Story 5.4 : « une transaction en `FUNDS_LOCKED` non expédiée à l'échéance → le job SLA déclenche l'auto-remboursement, le wallet acheteur est crédité (`REFUNDED`) ». Story 6.2 : l'arbitre déclenche `RESOLVE_DISPUTE_REFUND` — « aucune transition nouvelle ».

**Le clash.** La machine héritée (spine parent, invariant read-only) est `INITIATED→FUNDS_LOCKED→SHIPPED→RELEASED` + branches `DISPUTED→RELEASED/REFUNDED`. **`REFUNDED` n'est atteignable que depuis `DISPUTED`.** AD-19 interdit tout nouvel état ET impose que « les jobs SLA passent par les transitions existantes ». L'équipe de 5.4 a exactement deux constructions conformes à la lettre :

1. **Ajouter une transition `FUNDS_LOCKED→REFUNDED`** (événement `EXPIRE_REFUND`). AD-19 dit « machine inchangée » — mais une transition est-elle un « état ad hoc » ? La lettre n'interdit que les *états*. L'équipe 5.4 peut se croire autorisée ; l'équipe 6.2, qui lit « aucune transition nouvelle » dans sa propre story, construira ses gardes en supposant que `REFUNDED` implique un litige tranché (motif, arbitre, audit de décision) — hypothèse fausse dès que 5.4 livre.
2. **Ouvrir un pseudo-litige système puis `RESOLVE_DISPUTE_REFUND`** — passe par les transitions existantes, à la lettre. Mais AD-1 (hérité, read-only) impose que `OPEN_DISPUTE` soit **composite avec 1..N preuves et un commentaire ≥ 10 caractères** : le job SLA devrait fabriquer une fausse preuve. Et AD-26 ouvrirait le fil de messagerie à l'arbitre sur une transaction sans litige réel.

Les deux voies conformes sont toutes deux toxiques : la première fissure silencieusement l'invariant « REFUNDED = décision motivée » sur lequel Epic 6 bâtit ; la seconde viole AD-1 ou le contourne par des données synthétiques.

**Corollaire (même trou)** : l'**expiration d'une transaction non financée** (Story 5.2 : « la transaction est close en état neutre ») n'a AUCUN état machine cible — `INITIATED` n'est pas terminal, « expirée » n'existe pas, et UX-DR2/UX-DR31 exigent pourtant une couleur « expirée » et un « Dossier verrouillé le… ». Deux équipes conformes divergent : (a) champ `expired_at` sur la transaction, (b) statut `expirée` sur l'invitation seule. Le verrou en lecture seule (5.5) et le verrou du fil de messagerie (6.3 : « état terminal ») ne sauront pas lequel lire.

**AD proposé — AD-28 « Dénouements et expirations SLA ratifiés dans la machine » :**
> Amendement contrôlé (et unique) d'AD-19 : `EscrowStateMachine` gagne exactement deux événements — `EXPIRE` (`INITIATED → EXPIRED`, nouvel état terminal neutre, uniquement si jamais financée) et `AUTO_REFUND_TIMEOUT` (`FUNDS_LOCKED → REFUNDED`, uniquement par le scheduler, audité avec cause `SLA_NON_SHIPMENT`). Interdiction absolue de litige synthétique : aucun `OPEN_DISPUTE` sans acteur humain et preuve réelle (AD-1 inviolé). `REFUNDED` porte une **cause** (`ARBITRATION` | `SLA`) dans l'audit ; tout code qui infère « REFUNDED ⇒ arbitrage » est non conforme. « État terminal » est défini une seule fois = `{RELEASED, REFUNDED, EXPIRED}` et cette définition est le prédicat unique des verrous (AD-2, AD-26, UX-DR31).

---

### C2 — Copie des conditions (AD-24) : Story 5.1 / 7.3 copient **à la création**, Story 4.6 fige **au financement**

**La paire.** Story 7.3 (Epic 7) : « seules les transactions **créées après** la mise en vigueur appliquent la nouvelle version » — la version applicable est déterminée à la **création**. Story 4.6 (Epic 4) : « le barème […] **les conditions applicables étant figées sur la transaction à cet instant** » — cet instant = la demande de **financement**. Story 5.2 exige en outre que la répartition des frais soit « visible **avant acceptation** » (le vendeur s'engage sur ces chiffres).

**Le clash.** AD-24 dit « copie des conditions applicables sur la transaction **à l'engagement** » sans définir « engagement ». Trois lectures conformes coexistent dans le backlog : création (5.1/7.3), acceptation (5.2, implicite — c'est là que le vendeur consent), financement (4.6, explicite). Scénario concret : barème modifié entre la création et le financement → le wizard 5.1 a affiché et le vendeur 5.2 a accepté une commission X ; 4.6, à la lettre, fige Y au financement. Deux équipes, chacune irréprochable au regard d'AD-24, livrent un système où les frais prélevés ne sont pas ceux consentis — défaut juridiquement grave sur une plateforme d'escrow. Le récapitulatif téléchargeable (5.5) et l'écriture de commission (4.6/4.7) liront des sources différentes.

**AD proposé — AD-29 « Un seul instant de copie : la création » :**
> Les conditions (barème applicable, répartition, SLA de cycle de vie, version des textes légaux) sont **copiées sur la transaction à sa création** (Story 5.1) — colonnes dénormalisées + FK vers `config_versions`. Acceptation (5.2) et financement (4.6) **lisent exclusivement la copie** et ne recalculent jamais depuis `ConfigService`. Le terme « engagement » dans AD-24 est redéfini = création de la transaction. Une transaction expirée non financée conserve sa copie (auditabilité). Seul cas de re-copie : néant — toute renégociation = nouvelle transaction.

---

### C3 — Catalogues i18n frontend (Story 2.1, Epic 2) × emails/SMS localisés (Story 8.2, Epic 8) : AD-23 interdit au backend le texte que 8.2 lui impose de produire

**La paire.** Story 2.1 : « toute chaîne visible vit dans les catalogues de clés EN/FR du **frontend** ; une clé manquante casse la CI » (AD-23 : « le backend ne parle **jamais** à l'utilisateur »). Story 8.2 : « un **email est envoyé** […] **dans la langue de l'utilisateur** (clés i18n EN/FR, NFR-P24) ; les transitions critiques déclenchent aussi un **SMS** ».

**Le clash.** Un email/SMS est du texte destiné à l'utilisateur, composé côté serveur par le relais outbox (AD-22) via `NotificationSender` — le frontend n'est pas dans la boucle, ses catalogues ne sont pas accessibles au backend. À la lettre d'AD-23, l'équipe 8.2 n'a pas le droit d'écrire une seule phrase ; à la lettre de sa story, elle doit en écrire des dizaines, en deux langues. Deux constructions conformes-en-apparence : (a) l'équipe 8.2 crée un **second catalogue** de gabarits côté backend (exactement le « double catalogue de traduction » qu'AD-23 dit prévenir), sans garde CI, avec dérive terminologique garantie face au glossaire verrouillé UX-DR37 ; (b) elle fait rendre les gabarits par le front et les poste au backend — absurde et fragile. De plus, la garde CI de 2.1 (« clé manquante casse la CI ») est **structurellement aveugle** aux clés serveur : rien ne référence statiquement un code d'événement backend dans le front.

**AD proposé — AD-30 « Canal serveur : catalogue de gabarits versionné, mêmes clés, même garde » :**
> Exception unique et encadrée à AD-23 : les canaux sortants serveur (email, SMS) disposent d'un **catalogue de gabarits EN/FR côté backend**, dont les clés sont **exactement les codes d'événements de l'outbox** (AD-22). Terminologie contrainte par le glossaire PRD (UX-DR37). Un test CI backend prouve l'exhaustivité : tout code d'événement émissible possède son gabarit dans les deux langues, sinon échec. La langue vient de la préférence persistée de l'utilisateur (Story 2.1), portée par l'événement outbox. Tout autre texte serveur reste interdit (l'API continue de ne renvoyer que des codes).

---

## MAJEUR

### M1 — Cycle de vie du wallet : Story 3.4 (Epic 3, « fonds immobilisés », activation wallet à l'approbation) × Story 4.3 (Epic 4, crée `wallets` + provisionnement)

**La paire.** Story 4.3 : « un wallet est provisionné **par entreprise** (unicité entreprise+devise) » — mais les entreprises naissent en Story 2.4, et le chemin critique est 2 → 3 → 4. Story 3.4 : « les fonds éventuellement déposés sont présentés comme *immobilisés jusqu'à approbation* » et « à l'approbation, les capacités engageantes (**wallet**, "Nouvelle transaction") sont **activées** simultanément ».

**Le clash.** Aucun AD ne dit **qui crée le wallet ni quand**. Trois constructions conformes : (a) 4.3 provisionne par migration + hook à la création d'entreprise — mais le code de création d'entreprise appartient à l'Epic 2, déjà livré : qui le modifie ? (b) 3.4, qui doit afficher des « fonds immobilisés » AVANT que l'Epic 4 existe, invente sa propre représentation des fonds pré-KYB (flag, table de dépôts en attente) — deuxième forme de données que 4.3 devra réconcilier ou écraser ; (c) le wallet n'est « activé » qu'à l'approbation KYB (lecture littérale de 3.4) — un état `ACTIVE/INACTIVE` sur `wallets` qu'aucun AD ne prévoit et que la garde AD-20 rend redondant ou contradictoire (deux gardes pour le même refus : wallet inactif vs KYB non approuvé). Note : 3.4 promet des « fonds déposés » alors que le dépôt n'arrive qu'en 4.4/4.5 — la story amont s'appuie sur une entité d'un epic aval, en tension avec « chaque epic reste autonome ».

**AD proposé — AD-31 « Naissance et propriétaire unique du wallet » :**
> Le wallet (et ses comptes de grand livre adossés) est créé par `WalletService` — **seul créateur** — au moment de la **création de l'entreprise** (le hook est ajouté par la Story 4.3, qui amende le service d'inscription). Il n'existe aucun état d'activation sur le wallet : un wallet existe toujours, peut toujours être crédité (dépôts), et **seule la garde AD-20** (KYB) bloque financement et retrait. « Fonds immobilisés jusqu'à approbation » (3.4/UX-DR29) = pur libellé UI dérivé de (solde > 0 ∧ KYB non approuvé), aucune donnée dédiée. Tant que l'Epic 4 n'est pas livré, 3.4 rend ce libellé inatteignable de fait (pas de solde) — aucune représentation transitoire des fonds n'est autorisée.

### M2 — Composant « file opérateur » : Story 3.3 (Epic 3, le fonde pour la revue KYB) × Stories 4.5/4.9 (Epic 4) et 7.4 (Epic 7, tickets)

**La paire.** La convention frontend impose « composant file opérateur **unique** né en Story 3.3, réutilisé partout (interdiction d'implémentations parallèles) ». 3.3 le construit pour son besoin : tri ancienneté, badge SLA tricolore **à 2 j ouvrés**, panneau latéral **Approuver/Rejeter**, motif obligatoire au rejet. Or 4.5 exige un panneau d'approbation avec **champs de saisie** (montant reçu confirmé + taux de conversion USD) ; 4.9 un SLA **à 1 j ouvré** et des colonnes montant réservé + historique wallet ; 7.4 n'a **ni Approuver/Rejeter ni SLA** mais répondre/clore et une priorité.

**Le clash.** Aucun AD ne fixe le **contrat** du composant (slots, actions injectées, source du seuil SLA, colonnes). L'équipe 3.3, conforme, livre légitimement un composant taillé KYB (actions codées en dur, SLA constant). L'équipe 4.5, conforme à « réutiliser », se retrouve devant un composant inextensible : elle le forke (« ce n'est plus une implémentation parallèle, c'est une extension ») ou refactorise 3.3 sous pression de livraison — dans les deux cas, le mono-composant promis n'existe plus, et les revues ne peuvent trancher car la règle n'énonce aucun critère de conformité.

**AD proposé — AD-32 « Contrat du composant OperatorQueue » :**
> `OperatorQueue` est un composant **paramétré, jamais spécialisé par héritage/copie** : (1) colonnes déclaratives ; (2) tri ancienneté par défaut ; (3) badge SLA calculé à partir d'un **seuil passé en prop** (chaque file a le sien : KYB 2 j, retraits 1 j, tickets = priorité sans SLA — le badge est optionnel) ; (4) panneau latéral = **slot** recevant le composant de décision propre à la file ; (5) motif obligatoire = comportement du slot de rejet fourni par la bibliothèque. La Story 3.3 livre le composant SOUS CE CONTRAT (même si sa seule instance est KYB) ; toute file ultérieure est une instanciation, et un fork est une non-conformité bloquante en revue.

### M3 — Outbox (AD-22) × socle webhooks sortants existant (Story 8.3, Epic 8) : deux chemins d'émission d'événements

**La paire.** AD-22 : tout événement notifiable est « persisté dans la transaction métier (table outbox) puis relayé » vers email/SMS, in-app **et webhooks sortants (socle retry + DLQ + journal existant)** ». Story 8.3 durcit le **socle webhook existant** (abonnements, `HmacSigner`, retries, DLQ) sans une ligne sur l'outbox ; Stories 4.4–4.7 et 5.2–5.4 (Epics 4/5) produisent les transitions notifiables en écrivant, elles, dans l'outbox.

**Le clash.** Le socle existant émet aujourd'hui ses webhooks par son propre chemin (publication directe post-transition vers RabbitMQ). Deux équipes conformes : Epic 8 étend ce chemin direct (sa story ne mentionne que le socle) ; Epics 4/5 émettent via outbox en comptant sur un relais que personne ne branche sur le socle. Résultat au choix : événements financiers jamais livrés en webhook, ou double émission (chemin direct + relais outbox) quand quelqu'un branchera les deux. **Aucune story du backlog ne porte la migration du socle existant vers l'outbox** — le trou est un trou de propriété, pas seulement de convention.

**AD proposé — AD-33 (resserre AD-22) « L'outbox est l'unique producteur d'événements sortants » :**
> Tout événement sortant (email, SMS, in-app, webhook sortant) naît d'une **ligne outbox écrite dans la transaction métier** — y compris ceux du socle webhook existant, dont le point d'émission direct est **supprimé** lors de la Story 8.3 (l'AC de 8.3 doit inclure cette migration ; ajouter la phrase au backlog). Un même événement outbox alimente en éventail les trois canaux ; l'idempotence de livraison est par `(outbox_event_id, canal, abonné)`.

### M4 — Registre des codes machine : Story 3.4 (code d'erreur KYB dédié, Epic 3) × Story 2.1 (garde CI des clés i18n, Epic 2)

**La paire.** AD-23 impose des codes machine ; 3.4 crée « un code d'erreur dédié » (gating KYB), 4.3 « solde insuffisant », 4.6/9.1 les codes d'idempotence, AD-10 énumère `DISPUTE_ALREADY_RESOLVED`, `WINDOW_CLOSED`… Story 2.1 garantit « clé manquante ⇒ CI rouge » côté front.

**Le clash.** Aucun AD ne fixe le **registre** des codes ni la convention code→clé. Deux équipes backend conformes inventent `KYB_NOT_APPROVED` et `ERR_KYB_GATE` pour le même refus (ou deux formats : SCREAMING_SNAKE vs dotted). Surtout, la garde CI de 2.1 est **inopérante par construction** sur ce joint : un code backend nouveau n'est référencé par aucun fichier front, donc aucune « clé manquante » n'est détectée — l'utilisateur voit le code brut ou un fallback générique, précisément ce qu'AD-23 prétend empêcher. La réconciliation offline AD-10, qui classe les échecs **par code énuméré**, gèlera à tort en « transitoire » tout code permanent absent de son énumération front.

**AD proposé — AD-34 « Registre unique des codes machine, exhaustivité prouvée » :**
> Les codes machine (erreurs + événements de notification) vivent dans **un registre unique côté backend** (enum/constantes, format `DOMAINE_CAUSE` en SCREAMING_SNAKE), exporté en artefact (JSON) par le build. Un test CI front prouve que **chaque code du registre a sa clé EN et FR** et que la classification transitoire/permanent d'AD-10 couvre tous les codes d'erreur — l'ajout d'un code sans clé ni classification casse la CI. Aucun code littéral hors registre.

### M5 — Octroi du rôle ARBITRATOR : Story 6.1 (Epic 6, exige un arbitre existant) × Story 7.2 (Epic 7, gestion utilisateurs sans AC d'octroi)

**La paire.** AD-21 : « `ARBITRATOR` est octroyé/révoqué par un `ADMIN` via la **gestion des utilisateurs** — action motivée et auditée ». Story 6.1 suppose « un utilisateur porteur du rôle arbitre (octroyé par la plateforme uniquement) ». Story 7.2 — la gestion des utilisateurs — ne comporte **aucun AC d'octroi/révocation de rôle** (elle ne couvre que recherche, fiche, suspension).

**Le clash.** La capacité qu'AD-21 assigne à la gestion des utilisateurs n'est portée par aucune story. Deux constructions conformes : l'équipe Epic 6, bloquée sans arbitre, le **seed par bootstrap serveur** (le mécanisme qu'AD-21 réserve à ADMIN — mais 6.1 dit bien « octroyé par la plateforme », lecture défendable) ; l'équipe Epic 7 ajoutera plus tard l'octroi UI. Deux mécanismes d'octroi coexistent, le bootstrap n'étant ni motivé ni auditable comme AD-21 l'exige.

**AD proposé — resserrer AD-21 + amender le backlog :**
> L'octroi/révocation `ARBITRATOR` est une capacité de la **Story 7.2** (AC à ajouter : action motivée, auditée, jamais par bootstrap ni par migration) ; la Story 6.1 déclare une **dépendance explicite** à cette capacité (ou l'Epic 6 démarre par une story 6.0 la livrant). Le bootstrap serveur reste réservé au seul ADMIN initial.

### M6 — Moteur de frais : Stories 4.4 (dépôt) / 4.6 (commission) / 4.9 (retrait) — trois calculateurs conformes, un rapprochement qui casse au centime

**La paire (triple).** 4.4 calcule « frais de dépôt de la méthode » ; 4.6 « pourcentage dégressif par tranche + minimum fixe » réparti par partie ; 4.9 « frais de la méthode » de retrait. Toutes trois conformes à AD-24 (versionnage) et AD-14 (NUMERIC 19,2).

**Le clash.** Aucun AD ne désigne un composant de calcul unique ni la règle d'arrondi. Trois implémentations conformes divergent sur : l'arrondi (half-up vs half-even vs troncature à 2 décimales), l'assiette (frais sur montant brut ou net), l'application du minimum fixe par partie ou sur le total, l'arrondi de la répartition 50-50 d'un montant impair. Un écart d'un centime entre la commission calculée en 4.6 et sa décomposition tracée en 4.7 suffit à faire sonner en permanence l'alerte bloquante du rapprochement AD-16 — ou pire, à la faire tarer par l'équipe.

**AD proposé — AD-35 « FeeService unique, arithmétique figée » :**
> Tout calcul de frais/commission passe par un **`FeeService` unique** : arrondi `HALF_EVEN` à 2 décimales appliqué en dernier ; assiette = montant brut ; minimum fixe comparé après le dégressif ; répartition 50-50 = moitié arrondie à l'acheteur, **reste** au vendeur (somme des parts ≡ total, prouvé par test de propriété). Consommé par 4.4, 4.6, 4.7, 4.9 et par l'affichage 5.1/5.2 (le front n'affiche que des montants calculés serveur, jamais recalculés).

---

## MINEUR

### m1 — AD-19 « statuts d'invitation portés par la transaction » × ERD `escrow_transactions ||--o| invitations` × invitations de collègues (Story 2.5)

AD-19 dit les statuts d'invitation « **portés par la transaction** » ; l'ERD du même spine montre une **table `invitations` séparée**. Deux équipes conformes : 5.1 pose `invitation_status` en colonne sur la transaction (lettre d'AD-19), le job SLA de 5.2 requête la table `invitations` (lettre de l'ERD). S'ajoute une **collision de nommage** : la Story 2.5 (Epic 2) crée déjà des invitations *de collègues* (jeton, expiration, statut) — deux entités homonymes aux cycles différents, risque de table/DTO/clé i18n partagés à tort. **Resserrement proposé** : dans AD-19, remplacer « portés par la transaction » par « portés par l'entité `transaction_invitations` (1-à-0..1 avec la transaction, CHECK sur le statut) ; l'entité `member_invitations` (Story 2.5) est distincte et le nom nu `invitations` est interdit ».

### m2 — Statut KYB : AD-20 « machine à états **sur `Company`** » × ERD `companies ||--o| kyb_dossiers`

Deux formes conformes : statut en colonne sur `companies` (lettre d'AD-20 — c'est ce que lira la garde 3.4) vs statut sur `kyb_dossiers` (lecture naturelle de l'ERD — c'est ce qu'affiche la file 3.3). La re-soumission après rejet (pièces conservées, FR-P30) pose la question : nouveau dossier `SUBMITTED` pendant que `companies` reste `REJECTED` ? **Resserrement proposé** : le statut est stocké **une seule fois, sur `kyb_dossiers`** (au plus un dossier non terminal par entreprise, contrainte partielle unique) ; `Company` n'a pas de colonne statut ; la garde AD-20 et la bannière UX-DR12 dérivent du dossier courant ; la re-soumission ré-ouvre le même dossier (`REJECTED → SUBMITTED`), jamais un second.

### m3 — Trois mécanismes d'idempotence sans carte de recouvrement (AD-13 référence métier × AD-18 clé d'idempotence × NFR-P10/Story 9.1 `Idempotency-Key`)

Le financement 4.6 (clé d'idempotence AD-18), le rejeu offline 9.1 (header `Idempotency-Key` + table serveur) et le ledger 4.3 (référence métier unique) sont trois dispositifs conformes qui se recouvrent sur les mêmes endpoints (ex. litige composite rejoué offline). Deux équipes peuvent stocker les clés dans deux tables différentes avec deux sémantiques de réponse (résultat rejoué vs 409). **Resserrement proposé** : un AD court énonçant la pile — (1) header `Idempotency-Key` = déduplication HTTP (table unique, réponse rejouée), (2) référence métier = déduplication comptable dans `LedgerService`, (3) les gardes de la machine = filet final ; toute opération financière exposée porte (1) ET produit (2).

### m4 — Brouillons de messages offline (UX-DR44) × whitelist AD-27

UX-DR44 liste « brouillons de messages `[ASSUMPTION]` » dans la file étendue ; AD-27 whitelist « preuve, litige, consultation » — le message n'y est pas. Déjà signalé en Deferred (« aucune story porteuse »), donc simple rappel : **ratifier l'abandon** (retirer la mention d'UX-DR44) ou amender AD-27 explicitement avant qu'une story Epic 9 ne l'implémente « parce que c'est dans l'UX ».

### m5 — Récapitulatif téléchargeable (Story 5.5) : générateur de document côté serveur face à AD-23

Le récapitulatif (parties, conditions, chronologie, inventaire CoO) est un document généré côté serveur, en langue utilisateur — même angle mort qu'en C3, en plus bénin (peut être rendu côté front depuis les données). **Proposition** : trancher dans l'AD-30 proposé (le récapitulatif est soit rendu par le front, soit couvert par le catalogue de gabarits serveur).

---

## Synthèse des trous → AD à créer ou resserrer

| # | Paire (unités) | Sévérité | Remède |
| --- | --- | --- | --- |
| C1 | 5.4 (job SLA refund) × 6.2 (arbitrage) | CRITIQUE | **AD-28** : transitions SLA ratifiées (`EXPIRED`, `AUTO_REFUND_TIMEOUT`), litige synthétique interdit, « terminal » défini une fois |
| C2 | 5.1/7.3 (copie à la création) × 4.6 (figé au financement) | CRITIQUE | **AD-29** : copie unique à la création, « engagement » redéfini |
| C3 | 2.1 (catalogues front) × 8.2 (emails/SMS serveur) | CRITIQUE | **AD-30** : catalogue de gabarits serveur clé=code d'événement, garde CI d'exhaustivité |
| M1 | 3.4 (fonds immobilisés/activation) × 4.3 (création wallets) | MAJEUR | **AD-31** : wallet créé à la création d'entreprise par `WalletService`, aucun état d'activation |
| M2 | 3.3 (file KYB) × 4.5/4.9/7.4 (autres files) | MAJEUR | **AD-32** : contrat paramétré d'`OperatorQueue` (slots, SLA en prop) |
| M3 | AD-22 outbox × 8.3 socle webhook existant | MAJEUR | **AD-33** : outbox producteur unique ; migration du socle portée par 8.3 |
| M4 | 3.4 (codes d'erreur) × 2.1 (garde CI i18n) | MAJEUR | **AD-34** : registre unique des codes + test d'exhaustivité codes↔clés↔classification AD-10 |
| M5 | 6.1 (arbitre requis) × 7.2 (gestion users sans octroi) | MAJEUR | Resserrer AD-21 + AC d'octroi dans 7.2, dépendance déclarée de 6.1 |
| M6 | 4.4 × 4.6 × 4.9 (trois calculs de frais) | MAJEUR | **AD-35** : `FeeService` unique, arrondi et répartition figés |
| m1 | AD-19 « sur la transaction » × ERD `invitations` × 2.5 | MINEUR | Nommer `transaction_invitations` vs `member_invitations` |
| m2 | AD-20 statut sur `Company` × ERD `kyb_dossiers` | MINEUR | Statut stocké une fois sur `kyb_dossiers` |
| m3 | AD-13 × AD-18 × NFR-P10 (idempotences) | MINEUR | AD court : pile d'idempotence à 3 niveaux |
| m4 | UX-DR44 brouillons × AD-27 whitelist | MINEUR | Ratifier l'abandon ou amender AD-27 |
| m5 | 5.5 récapitulatif serveur × AD-23 | MINEUR | Couvrir dans AD-30 |

*Revue produite le 2026-07-24. Aucun AD hérité (AD-1..12) n'a besoin d'être modifié ; C1 exige en revanche un amendement contrôlé et explicite du périmètre d'AD-19 — mieux vaut le trancher au spine qu'en escalation au milieu de l'Epic 5, comme l'a déjà coûté l'escalation 4.3 du cycle POC.*
