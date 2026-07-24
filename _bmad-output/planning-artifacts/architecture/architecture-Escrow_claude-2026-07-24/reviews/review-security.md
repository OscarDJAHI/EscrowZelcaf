# Revue indépendante — Sécurité & Conformité

**Artefact revu :** `ARCHITECTURE-SPINE.md` (architecture-Escrow_claude-2026-07-24, statut draft)
**Contexte :** PRD §7 (NFR-P22..P25) et §8 (conformité & dépendances), définitions NFR-P1..P25 du backlog (`epics.md`)
**Lens :** détention de fonds de tiers, PCI-DSS, AML, rétention légale ZLECAf, ségrégation des fonds
**Relecteur :** revue d'architecture indépendante (sécurité/conformité)
**Date :** 2026-07-24

---

## Verdict global

Le spine est solide sur son axe central — le circuit financier (AD-13..18) est remarquablement bien verrouillé pour un draft : partie double, writer unique, solde dérivé, réservation comptable, atomicité écritures+transition+audit, idempotence multicouche, rapprochement bloquant en critère d'entrée. Les reports aux spikes sont pour la plupart sains (le port est fixé, seul l'adaptateur est déféré).

**Mais le spine n'est pas encore prêt à gouverner un backlog production dans un contexte régulé**, pour trois raisons : (1) la garde KYB (AD-20) omet le **dépôt de fonds**, ce qui ouvre la porte d'entrée AML de la plateforme aux entreprises non vérifiées ; (2) le **chiffrement au repos** n'est porté par aucun invariant et le périmètre de NFR-P6 laisse à nu les données les plus sensibles du système (coordonnées bancaires des retraits, dossiers et résultats de screening KYB/AML) ; (3) le modèle de privilèges (AD-21) n'impose **ni MFA ni séparation des tâches** sur un rôle ADMIN qui cumule crédit manuel de dépôts, exécution de retraits, approbation KYB, configuration et octroi de rôles. Deux reports (AR-P5 localisation, AR-P4 secrets) sont tenables **sous condition** — conditions que le spine doit énoncer lui-même.

---

## Findings — CRITIQUE

### SEC-C1 — AD-20 : le dépôt de fonds n'est pas dans la liste des actions gardées par le KYB (trou AML frontal)

AD-20 énumère les actions engageantes protégées par la garde serveur : « créer, accepter, financer, retirer ». **Déposer des fonds n'y figure pas.** Or c'est précisément le point d'entrée AML de la plateforme : une entreprise en `DRAFT`/`SUBMITTED`/`REJECTED` pourrait alimenter son wallet (via PSP ou dépôt manuel FR-P23) et faire détenir par la plateforme des fonds d'une entité non vérifiée — le scénario exact que le KYB existe pour empêcher. Le gating du seul « retirer » transforme la plateforme en lieu de parcage de fonds non screenés, ce qui est pire que de ne rien garder du tout (les fonds sont entrés, on ne sait plus les rendre proprement à une entité rejetée).

**Attendu :** ajouter explicitement « déposer » (PSP et manuel) à la liste AD-20, avec la question dérivée à trancher : que fait-on d'un webhook PSP de collecte aboutissant pour une entreprise devenue non-APPROVED entre l'initiation et le règlement (compte d'attente comptable + procédure de restitution, pas de crédit wallet) ?

### SEC-C2 — Chiffrement au repos : aucun invariant ne le porte, et le périmètre hérité de NFR-P6 laisse à nu les données les plus sensibles

NFR-P6 (backlog) couvre « preuves (stockage objet) et secrets (HMAC partenaire, webhook) ». Aucun AD du spine ne porte le chiffrement au repos, et le périmètre production introduit des données que ni NFR-P6 ni aucun invariant ne couvre :

- **Coordonnées bancaires des retraits** (`withdrawals`, FR-P43) — IBAN/comptes bancaires d'entreprises, stockés en clair dans Postgres selon le modèle actuel ;
- **Dossiers KYB et résultats de screening AML** (`kyb_dossiers`) — données réglementées par nature, conservées ≥ 5 ans (AD-25), soit une surface d'exposition longue durée ;
- **Contenu de la messagerie** (`messages`) — échanges commerciaux confidentiels, conservés ≥ 5 ans ;
- **Secrets TOTP** — voir SEC-H1 : le MFA n'existe pas encore, mais s'il est ajouté, rien ne gouvernera le stockage de ses secrets.

L'invariant ZLECAf « rétention ≥ 5 ans » (AD-25) **aggrave** ce trou : on impose de garder longtemps ce qu'on n'impose pas de protéger. NFR-P23 (Malabo : minimisation, notification d'incidents) rend une fuite de ces tables directement notifiable.

**Attendu :** un AD dédié « données sensibles au repos » qui (a) dresse la classification (coordonnées bancaires, dossiers/résultats KYB-AML, secrets d'authentification, contenu messagerie), (b) impose chiffrement au niveau champ ou à défaut au niveau volume/base avec justification, (c) se coordonne avec AR-P4 pour la gestion des clés. Le choix d'implémentation peut être déféré ; l'obligation ne peut pas l'être.

---

## Findings — ÉLEVÉ

### SEC-H1 — AD-21 : pas de MFA, pas de séparation des tâches — un compte ADMIN unique peut mouvementer des fonds de bout en bout

AD-21 verrouille correctement l'**octroi** des rôles (whitelist, bootstrap serveur, octroi ARBITRATOR motivé et audité). Mais pour une plateforme détenant des fonds de tiers :

1. **Aucune exigence MFA** nulle part (ni NFR-P1..P10, ni spine) — même pas pour ADMIN/ARBITRATOR. Un JWT volé (phishing d'un opérateur) suffit à contrôler le back-office. C'est en dessous du standard de n'importe quel établissement adossé (le partenaire bancaire l'exigera à l'audit).
2. **Un seul rôle ADMIN** cumule : crédit manuel des dépôts par virement (FR-P23), traitement de la file des retraits (FR-P43), approbation KYB (FR-P30/31), modification du barème (AD-24), octroi de rôles. Un ADMIN compromis — ou malveillant — peut créditer un wallet fictif par « dépôt manuel », approuver le KYB de l'entité, et exécuter le retrait. AD-13 garantira que la fraude est *bien comptabilisée*, pas qu'elle est empêchée.
3. **Aucune règle de double contrôle** (quatre yeux) sur les actions admin qui créent ou libèrent de la valeur (crédit de dépôt manuel, exécution de retrait au-dessus d'un seuil).

**Attendu :** étendre AD-21 (ou créer un AD-28) : MFA obligatoire pour ADMIN/ARBITRATOR (souhaitable pour tous à terme) ; séparation des rôles back-office (au minimum : opérateur finance ≠ approbateur, ou double validation sur crédit manuel et exécution de retrait) ; l'auteur d'une demande ne peut pas être son propre approbateur. Le mécanisme peut être simple au MVP (double validation par un second ADMIN), mais l'invariant doit exister avant le premier encaissement réel.

### SEC-H2 — AD-13 : l'immuabilité « trigger ou privilèges » est contournable par le canal Flyway, et le writer unique n'est protégé que par convention

Deux chemins de contournement du writer unique ne sont pas fermés :

1. **Le canal migrations.** Si l'utilisateur DB des migrations Flyway est le même que l'utilisateur runtime (cas par défaut Spring Boot), toute migration peut `DROP TRIGGER`, `UPDATE ledger_entry_lines`, ou réattribuer des privilèges — et les migrations sont exactement le genre de code qu'on écrit sous pression pour « corriger une écriture fausse ». L'alternative « privilèges » d'AD-13 n'est effective que si le spine impose la **séparation des rôles PostgreSQL** (rôle migration ≠ rôle runtime, le rôle runtime sans UPDATE/DELETE sur les tables ledger, et une règle interdisant aux migrations de toucher aux données du grand livre après genèse).
2. **L'unicité du writer est purement conventionnelle.** AD-13 exige un test de concurrence pour le solde négatif, mais rien n'impose de prouver qu'aucun autre service n'écrit dans `ledger_entries` (test d'architecture type ArchUnit, ou privilège d'écriture réservé). La note sous le diagramme (« aucun service métier n'écrit une ligne comptable sans passer par le writer unique ») est un vœu, pas un mécanisme.
3. **Corollaire : aucun chemin de correction défini.** En production réelle il y aura des écritures erronées. Sans invariant « toute correction est une contre-passation référencée passée par `LedgerService` (jamais de modification, compte d'ajustement audité) », la pression opérationnelle produira du SQL direct — le contournement que AD-13 veut empêcher. AD-15 définit l'écriture inverse pour les retraits seulement ; il faut la règle générale.

**Attendu :** durcir AD-13 : séparation des rôles DB (migration/runtime), privilèges runtime sans UPDATE/DELETE sur les tables ledger, preuve mécanique du writer unique, et règle de contre-passation comme unique voie de correction.

### SEC-H3 — AR-P5 (localisation des données) : le report est tenable *sous condition* — et la condition contredit potentiellement le paradigme

Le spine pose la bonne contrainte (« toutes les données persistées résident dans les régions décidées par corridor ») et défère la décision à la Story 11.3. Mais le paradigme choisi est **un monolithe + une instance PostgreSQL** : si les corridors de lancement (KEN↔ZAF, NGA↔GHA — PRD §9.1) imposent des localisations **incompatibles entre elles** (le Nigeria est cité comme exigeant pour les données financières, l'Afrique du Sud a ses propres règles), un mono-déploiement ne peut pas satisfaire la contrainte — et la réponse (déploiement par corridor, partitionnement géographique des données) n'est pas une décision d'hébergement mais un changement de paradigme, exactement ce qu'un spine doit trancher.

**Le report est acceptable si et seulement si** le spine énonce la condition de validité : « l'architecture mono-déploiement suppose que les corridors de lancement admettent une région d'hébergement commune ; si le spike AR-P5 l'infirme, c'est un conflit de spine (déploiement par corridor), pas un paramètre de la Story 11.3 ». En l'état, la contrainte posée peut être silencieusement insatisfiable. À noter : la Story 11.3 arrive en Epic 11 (fin de backlog) alors que le PRD §7 exige la décision documentée par corridor **avant lancement** — le séquencement doit faire de AR-P5 un prérequis du premier encaissement réel, pas une story d'opérabilité tardive.

---

## Findings — MOYEN

### SEC-M1 — AD-17 : le secret de vérification des webhooks PSP n'est gouverné par aucune règle de stockage, de rotation ou de journalisation d'échec

Le spine a durci en profondeur le canal HMAC partenaire (AD-8 : nonce, anti-rejeu, contrainte de taille de secret en base, WRITE_ONLY — V4/V5). Le canal webhook PSP, qui porte des **événements financiers** (règlement de collecte, confirmation de payout), n'a aucun équivalent :

- NFR-P6 liste « secrets (HMAC partenaire, webhook) » — « webhook » y désigne le secret **sortant** existant ; le secret de vérification **entrant** PSP n'est nommé nulle part ;
- aucune règle de **rotation** (les PSP imposent ou permettent la rotation des signing secrets ; sans règle, la rotation sera un incident) ;
- « signature invalide = rejet sans écriture » : le rejet sans écriture comptable est correct, mais l'absence de **journalisation de sécurité** des signatures invalides prive l'alerting (NFR-P13) du signal d'une attaque sur l'endpoint ;
- « rejeu = aucun effet, journalisé » ne dit rien du cas *même référence PSP, payload différent* (montant modifié) — qui doit être détecté et alerté comme anomalie, pas absorbé silencieusement comme un rejeu.

**Attendu :** étendre AD-17 : le secret webhook PSP entre dans le périmètre NFR-P6/AR-P4 (stockage, rotation sans interruption), signature invalide journalisée et métriquée, rejeu à payload divergent = alerte. Le niveau d'exigence d'AD-8 est le bon étalon — l'appliquer au canal qui transporte l'argent.

### SEC-M2 — AD-16 : sans la jambe externe (relevés du compte cantonné, AR-P2), le rapprochement risque d'être circulaire

L'équation d'AD-16 (`somme wallets + séquestres + réservations = compte miroir du cantonnement`) n'a de valeur de ségrégation que si le « compte miroir » est alimenté par une **source externe** (relevés/API du partenaire bancaire). Or le format des relevés est déféré à AR-P2 et le spine ne dit pas d'où vient le miroir. Un miroir alimenté par nos propres écritures vérifierait la cohérence interne du grand livre (utile) mais **pas** la ségrégation exigée par NFR-P25 — dérive possible entre nos livres et la banque sans aucun écart détecté. Le report à AR-P2 est légitime (dépend du contrat) ; ce qui manque est l'exigence que le critère d'entrée d'AD-16 inclue **l'ingestion effective de la position bancaire externe**, pas seulement la règle d'alerte. Sinon le « critère d'entrée » peut être satisfait par un rapprochement vide de sens.

### SEC-M3 — AR-P4 (secrets) : report sûr sur l'outil, pas sur le séquencement

Déférer le **choix de l'outil** est sain (NFR-P1 impose déjà l'externalisation et l'échec au démarrage). Mais la Story 11.2 est en fin de backlog alors que les secrets à protéger (clés API PSP de production, secret webhook PSP, clés HMAC partenaires, credentials KYB) apparaissent dès les Epics 2–4. Le spine a su poser un gate pour AD-16 (« avant la mise en service du circuit financier — critère d'entrée, pas d'epic ») ; il faut le même gate ici : *variables d'environnement acceptables en sandbox ; gestion outillée des secrets (AR-P4) = critère d'entrée du premier encaissement réel.* Sans cela, la production démarrera mécaniquement avec des secrets PSP réels en variables d'env non rotées.

### SEC-M4 — AD-25 : le WORM est applicatif là où AD-13 est tenu en base ; le stockage objet et les sauvegardes échappent à la règle

Trois incohérences de niveau d'enforcement :

1. AD-13 exige l'immuabilité « **tenue en base** (trigger ou privilèges) » ; AD-25 se contente d'« aucun DELETE **applicatif** » pour les preuves, dossiers KYB, screening, messages et `audit_logs`. Pour des enregistrements sous rétention légale, la règle doit être du même métal que le ledger — sinon le WORM tombe au premier accès DB direct ou à la première migration « de nettoyage ».
2. Le **stockage objet** n'est pas couvert : la ligne `evidence_files` est protégée, mais l'objet MinIO/S3 sous-jacent est supprimable au niveau storage (aucune exigence d'object-lock/versioning, alors que le backend objet définitif est justement déféré — c'est le moment de poser l'exigence dans le critère de choix).
3. **NFR-P14 (backup/restore)** n'est pas relié à AD-25 : la rétention ≥ 5 ans doit survivre à un restore, et la politique de rotation des sauvegardes ne doit pas devenir le canal de purge involontaire (ni, inversement, conserver indéfiniment ce que l'échéance de purge autorise à supprimer).

À l'inverse, noter la **tension Malabo** (NFR-P23 : minimisation) : une rétention ≥ 5 ans appliquée en bloc aux messages et aux dossiers KYB d'entreprises **rejetées n'ayant jamais transacté** est difficile à justifier au titre de la finalité ZLECAf. L'échéance « calculée » d'AD-25 est le bon mécanisme — le spine devrait dire explicitement que le calcul dépend de la catégorie ET du fait générateur (transaction réelle vs prospect rejeté), pas un plancher uniforme.

### SEC-M5 — Trois vocabulaires d'idempotence sans règle d'articulation

AD-13 (« référence métier unique »), AD-17 (« idempotence par référence PSP »), AD-18 (« clé d'idempotence obligatoire ») décrivent trois mécanismes à trois niveaux (écriture comptable, webhook entrant, API exposée) — c'est architecturalement juste, mais rien ne dit comment ils se composent (la clé d'idempotence AD-18 devient-elle la référence métier AD-13 ? la référence PSP est-elle la référence métier du crédit de dépôt ?). Sans règle d'articulation, trois stories différentes implémenteront trois tables de déduplication aux sémantiques divergentes (portée, durée de conservation des clés, comportement sur payload différent à clé identique — ce dernier point devant être un rejet, pas un rejeu). Une phrase de mapping dans AD-18 suffit.

---

## Findings — FAIBLE

### SEC-L1 — Résultats de screening AML : aucun contrôle d'accès spécifique
AD-25 impose leur rétention, SEC-C2 réclame leur chiffrement, mais rien ne dit **qui** peut les lire. Un ARBITRATOR n'a aucune raison d'accéder aux résultats de screening ; même côté ADMIN, l'accès mérite d'être journalisé (lecture de données AML = événement auditable dans la plupart des référentiels).

### SEC-L2 — Anti-énumération (NFR-P9) : bonne couverture API, angle mort sur les canaux machine
La réponse uniformisée est exigée pour les webhooks PSP (AD-17) — bien. Vérifier que le même principe couvre le canal partenaire HMAC pour les identifiants de transaction inconnus (l'existant AD-8/Story 3.4 semble le faire ; le spine gagnerait à le dire au rang de convention plutôt que de l'hériter implicitement).

### SEC-L3 — AD-22 : le contenu des notifications n'est pas contraint
L'outbox est saine (non-perte, découplage). Mais rien n'interdit de mettre des données sensibles (montants, coordonnées, statuts KYB) dans le payload d'un email/SMS transitant par un fournisseur tiers non encore choisi (AR-P6). Une convention « notification = référence + code d'événement, le détail se consulte dans l'app » fermerait le point et simplifierait d'ailleurs l'i18n (AD-23).

### SEC-L4 — `[ASSUMPTION : 2 décimales]` d'AD-14 : vérifier contre les payouts en devise locale
Le PRD §8 annonce des payouts « en devise locale » via PSP. Certaines devises africaines pertinentes n'ont pas 2 décimales dans les usages PSP. Le risque est contenu tant que wallets et contrats sont USD, mais le taux et le montant réglés côté PSP devront être stockés — l'assumption doit être re-testée au spike AR-P1.

---

## Reports (Deferred) — jugement de sûreté

| Report | Verdict | Condition |
| --- | --- | --- |
| AR-P1 (adaptateur PSP) | **Sûr** | Port fixé, PCI-DSS verrouillé par AD-17/NFR-P22 indépendamment du fournisseur ; ajouter SEC-M1 (secret webhook) et SEC-L4 (décimales) aux critères du spike |
| AR-P2 (cantonnement opérationnel) | **Sûr sous condition** | SEC-M2 : le critère d'entrée AD-16 doit inclure l'ingestion de la position bancaire externe |
| AR-P3 (KYB/AML) | **Sûr** | Le port et la machine à états AD-20 tiennent quel que soit le fournisseur ; le critère « localisation des données » est déjà dans le spike — bien |
| AR-P4 (secrets) | **Sûr sur l'outil, pas sur le séquencement** | SEC-M3 : en faire un critère d'entrée du premier encaissement réel |
| AR-P5 (déploiement & régions) | **Tenable sous condition explicite** | SEC-H3 : énoncer l'hypothèse « région commune aux corridors de lancement » comme hypothèse de validité du paradigme mono-déploiement, et avancer la décision avant tout encaissement réel |
| AR-P6 (email/SMS) | **Sûr** | Ajouter SEC-L3 (minimisation du contenu) aux critères du spike |

---

## Points forts (à préserver tels quels)

- **AD-13/15/18** : la chaîne partie double → réservation comptable → atomicité écritures+transition+audit est le cœur juste ; la réservation de retrait *en écriture* (AD-15) élimine élégamment toute une classe de doubles-engagements.
- **AD-16 en critère d'entrée** (« avant la mise en service du circuit financier ») : le bon pattern de gate — c'est précisément celui qu'il faut répliquer pour AR-P4 (SEC-M3) et AR-P5 (SEC-H3).
- **AD-17/NFR-P22** : le rejet explicite du pattern « saisie carte » des maquettes est la bonne décision PCI (SAQ A) et elle est tracée jusqu'à l'UX (UX-DR36).
- **AD-27** (whitelist offline non financière) : ferme proprement le vecteur « rejeu financier hors contexte » — cohérent avec NFR-P10 et AD-9.
- **Héritage AD-1..12 en read-only** avec remontée de conflit : discipline de gouvernance rare et précieuse.

---

## Récapitulatif

| ID | Sévérité | Finding | AD concerné |
| --- | --- | --- | --- |
| SEC-C1 | CRITIQUE | Dépôt de fonds absent de la garde KYB — entrée AML non gardée | AD-20 |
| SEC-C2 | CRITIQUE | Chiffrement au repos non porté par un invariant ; coordonnées bancaires, dossiers/screening KYB, messagerie à nu | (absent) / NFR-P6 |
| SEC-H1 | ÉLEVÉ | Ni MFA ni séparation des tâches ; un ADMIN seul peut créditer, approuver et retirer | AD-21 |
| SEC-H2 | ÉLEVÉ | Immuabilité ledger contournable via Flyway/privilèges DB ; writer unique conventionnel ; pas de règle de contre-passation | AD-13 |
| SEC-H3 | ÉLEVÉ | AR-P5 : mono-déploiement vs localisation par corridor — condition de validité non énoncée, décision trop tardive | Deferred AR-P5 |
| SEC-M1 | MOYEN | Secret webhook PSP sans règle de stockage/rotation ; échecs de signature non journalisés ; rejeu à payload divergent non traité | AD-17 |
| SEC-M2 | MOYEN | Rapprochement AD-16 circulaire sans ingestion de la position bancaire externe | AD-16 / AR-P2 |
| SEC-M3 | MOYEN | AR-P4 trop tardif — gate manquant avant le premier encaissement réel | Deferred AR-P4 |
| SEC-M4 | MOYEN | WORM applicatif (vs base), stockage objet et backups hors périmètre ; tension minimisation Malabo | AD-25 |
| SEC-M5 | MOYEN | Trois mécanismes d'idempotence sans règle d'articulation | AD-13/17/18 |
| SEC-L1 | FAIBLE | Accès aux résultats de screening non contraint/journalisé | AD-25 |
| SEC-L2 | FAIBLE | Anti-énumération non énoncée pour le canal partenaire | Conventions |
| SEC-L3 | FAIBLE | Contenu des notifications non minimisé | AD-22 |
| SEC-L4 | FAIBLE | Assumption 2 décimales à re-tester contre les payouts devise locale | AD-14 |
