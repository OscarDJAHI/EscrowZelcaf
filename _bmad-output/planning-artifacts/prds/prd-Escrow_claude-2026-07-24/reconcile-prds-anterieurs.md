# Réconciliation des PRD antérieurs — PRD production 2026-07-24

Date d'analyse : 2026-07-24.
Sources comparées :

- **Source A — PRD POC plateforme** : `Docs/prd_escrow_platform.md` (41 lignes). Le nouveau PRD déclare (§ préambule) le **remplacer intégralement**.
- **Source B — PRD Evidence Upload** : `_bmad-output/planning-artifacts/prds/prd-Escrow-2026-07-15/prd.md` (finalisé, fonctionnalité livrée). Le nouveau PRD déclare l'**absorber par référence** (FR-1..FR-16 « restent normatives », parcours « repris tels quels »).
- **Nouveau PRD production** : `_bmad-output/planning-artifacts/prds/prd-Escrow_claude-2026-07-24/prd.md` + son `addendum.md`.

Verdict global : la couverture de la Source A est **quasi complète** (chaque section du POC a un successeur explicite ou un remplacement assumé) ; l'absorption de la Source B est **globalement exacte** mais présente **une contradiction interne franche (FR-14 vs FR-P33)** et deux références « tels quels » qui sous-déclarent des changements réels. Trois points de friction sur les états/terminologie subsistent, dont un préexistant entre A et B que le nouveau PRD n'arbitre pas.

---

## 1. Couverture du PRD POC (Source A)

### 1.1 Tableau de correspondance

| Élément du POC (Source A) | Sort dans le nouveau PRD | Statut |
|---|---|---|
| §1 Vision & contexte ZLECAf | §1 (reprise enrichie : lourdeur documentaire, espace blanc concurrentiel, modèle wallet) | ✅ Couvert |
| §2 Objectifs (confiance, interopérabilité, résilience) | §2 « Objectifs stratégiques » 1-2-3, repris quasi mot pour mot | ✅ Couvert |
| §3 Personas (Acheteur, Vendeur, **Administrateur/Arbitre fusionné**) | §3 : Amina, Thabo, puis **scission explicite** Koffi (arbitre) / Nadia (opérateur) — UJ-3 précise « Elle n'arbitre pas » (FR-P11) | ✅ Remplacé explicitement (raffinement assumé) |
| §4.1 Escrow core (contrat, `FUNDS_LOCKED`, `SHIPPED`, `RELEASED`, libération par validation acheteur ou preuve logistique) | UJ-2 + FR-P1/P2/P12 (auto-libération après livraison attestée = la « preuve logistique automatique » du POC) | ✅ Couvert, étendu aux fonds réels |
| §4.2 Litiges (`DISPUTED`, dépôt de preuves, `RESOLVE_DISPUTE_RELEASE`/`RESOLVE_DISPUTE_REFUND`) | Absorbé via Source B + FR-P11/P12/P34 | ✅ Couvert (⚠️ terminologie des événements, voir §3.2) |
| §4.3 Webhooks sortants (abonnement partenaires + signature HMAC-SHA256) | FR-P18 (webhooks sortants fiables : retry + DLQ + journal, scopés tenant) | ⚠️ Couvert pour la fiabilité et implicitement la signature ; **l'« abonnement » self-service (configuration des URLs de callback par le partenaire) n'est pas restitué explicitement** — FR-P15 ne couvre que les clés HMAC *entrantes* |
| §4.4 PWA offline (consultation + « initiation des actions de validation » hors-ligne, optimisation data) | FR-P19..P22 + FR-P40 | ⚠️ Couvert mais **restreint sciemment** : FR-P40 limite l'offline à consultation / preuves / litige et exige la connexion pour les actions financières et « l'acceptation engageante ». La promesse POC d'« actions de validation » hors-ligne (ex. confirmer la réception sans réseau) est **rétrécie sans être nommée comme un remplacement**. L'« optimisation data / payload minimaliste » n'est reprise que via NFR-4 de la Source B (bande passante), pas dans le nouveau texte |
| §5 NFR (chiffrement repos/transit, audit immuable, résilience asynchrone, ACIDité) | §7 : NFR-P1..P21 (chiffrement au repos, TLS/HSTS, résilience) + FR-P10 (WORM) + FR-P3 (grand livre partie double, rapprochement) + NFR-P25 (invariant de ségrégation) | ✅ Couvert et durci |

### 1.2 Écarts de couverture à corriger

1. **Abonnement webhooks sortants** : le POC prévoyait que « transporteurs et agrégateurs de paiement peuvent configurer des URLs de callback ». Le nouveau PRD garantit la fiabilité de la livraison (FR-P18) mais ne dit nulle part **qui configure les endpoints sortants et comment** (self-service partenaire ? provisioning opérateur dans le back-office FR-P37 ?). À trancher explicitement, sinon c'est un trou fonctionnel hérité.
2. **Offline « actions de validation »** : le rétrécissement de FR-P40 est probablement la bonne décision produit (pas d'engagement de fonds optimiste), mais comme le nouveau PRD prétend *remplacer* le POC, il devrait **assumer le retrait** (une ligne « remplace la promesse POC §4.4 » suffirait), comme il le fait proprement pour la rétention (FR-P33).
3. **État `INITIATED`** : présent dans le glossaire de la Source B (chaîne d'états) mais jamais cité dans le nouveau PRD, qui introduit par ailleurs un cycle de vie *pré-financement* (invitation en attente, acceptation, expiration — FR-P29/P12) **sans nommer les états correspondants**. Voir §3.1.

---

## 2. Exactitude des références au PRD Evidence Upload (Source B)

### 2.1 Références exactes

- **« Les FR-1 à FR-16 … restent normatives »** (§6.E) : la Source B contient bien exactement FR-1..FR-16, et la parenthèse résumé (« dépôt, types/tailles, contradictoire, retrait logique, verrou en état terminal, offline atomique, partenaire HMAC ») correspond fidèlement aux blocs A-E de la Source B. ✅
- **Verrou en état terminal** : la formulation reprend correctement FR-8 (verrou **basé sur l'état** `{RELEASED, REFUNDED}`), et FR-P34 (messagerie « verrouillée avec le dossier en état terminal ») est **cohérente** avec ce même mécanisme. ✅
- **Parcours partenaire (UJ-4)** : « le transporteur pousse des preuves signées HMAC » — conforme, et la fonctionnalité est effectivement livrée (Stories 3.1-3.4). ✅
- **Métrique « 100 % des litiges ouverts avec ≥ 1 preuve »** : correctement attribuée (« garanti par conception, hérité du PRD Evidence Upload » = FR-6). ✅
- **Plafond de pièces** : l'hypothèse Source B (« limite souple ~20 ») est reprise de façon cohérente par FR-P22 (« plafond 20 fichiers miroité côté front »). ✅
- **Évolutions §9 de la Source B correctement promues** : rétention conforme → FR-P33 ; antivirus → NFR-P (backlog) ; transporteur interactif → §10.4 ; OCR → §10.7 ; chiffrement par fichier → NFR-P « chiffrement au repos ». ✅

### 2.2 Contradiction interne : FR-14 vs FR-P33 (rétention)

C'est l'écart le plus net. Le §6.E déclare **FR-1 à FR-16 normatives sans exception**, or :

- **FR-14 (Source B)** : « **Rétention illimitée** des pièces (aucune purge automatique) pour le POC. »
- **FR-P33 (nouveau)** : rétention **≥ 5 ans**, « remplaçant la “rétention illimitée POC” par une politique explicite (purge interdite avant échéance, WORM) ».

FR-P33 dit remplacer FR-14, mais §6.E maintient FR-14 normative. Les deux règles ne sont pas équivalentes : « illimitée » interdit toute purge à jamais ; « ≥ 5 ans » **autorise** la purge après échéance. Un lecteur appliquant FR-1..16 à la lettre conclura qu'on ne purge jamais ; un lecteur appliquant FR-P33 planifiera une purge conforme à 5 ans+.
**Correctif recommandé** : au §6.E, écrire « FR-1 à FR-15 restent normatives, **à l'exception de FR-14, remplacée par FR-P33** » (et vérifier FR-16, voir 2.4).

### 2.3 « Parcours repris tels quels » : sous-déclaration de deux changements

Le §5 affirme que les parcours litige (UJ-1/2/3 de B) sont « repris **tels quels** … et ne sont pas re-décrits ici ». Deux évolutions réelles contredisent le « tels quels » :

1. **La messagerie (FR-P34) n'existait pas dans la Source B.** Le parcours litige production inclut désormais un fil de messages (visible de l'arbitre dès l'ouverture, conservé et verrouillé avec le dossier) ; UJ-2 (Thabo « se défend ») et UJ-3 (Koffi arbitre sur « toutes les pièces ») de la Source B ignorent ce canal. Le dossier d'arbitrage n'est plus seulement « les pièces » : c'est pièces **+ messages**. Le renvoi devrait dire « repris et **enrichis de la messagerie FR-P34** ».
2. **NFR-6 de la Source B (absence d'antivirus = risque accepté POC) est silencieusement caduque.** Le §7 impose l'« antivirus à l'ingestion » (NFR-P), ce qui inverse NFR-6, mais le PRD ne statue que sur les **FR** de la Source B, jamais sur ses **NFR** (NFR-1..NFR-6). Le statut des NFR de B est indéterminé ; au minimum NFR-6 devrait être explicitement annulée (cohérent avec la logique du pivot production : « re-triage des risques POC-acceptés »).

### 2.4 Points de vigilance mineurs côté Source B

- **FR-16 (réconciliation offline)** reste pleinement valable, mais FR-P19..P21 (idempotence du rejeu, file sur échec réseau réel) la recouvrent partiellement — pas de contradiction détectée, simple redondance à surveiller.
- **FR-P32 (CoO ZLECAf « type de premier rang »)** étend FR-3 (types JPG/PNG/PDF) avec des **métadonnées** : compatible tant que le CoO reste un PDF/JPG typé, mais FR-3 devra être amendée si le typage impose de nouveaux champs obligatoires au dépôt — à noter pour l'architecture.
- **FR-P38 (tickets support, pièces jointes)** « réutilise la brique upload existante » : hors périmètre de B (qui n'attache qu'à une *transaction*) — extension, pas contradiction, mais l'anti-IDOR de FR-11 devra être transposé au contexte ticket.

---

## 3. États escrow & terminologie entre les trois documents

### 3.1 Machine à états : un modèle jamais restitué, et étendu implicitement

- **Source A** : `FUNDS_LOCKED` → `SHIPPED` → `RELEASED` ; `DISPUTED` ; remboursement implicite.
- **Source B (glossaire)** : `INITIATED` → `FUNDS_LOCKED` → `SHIPPED` → `RELEASED` ; branches `DISPUTED` → `RELEASED`/`REFUNDED`. C'est la seule énumération complète existante.
- **Nouveau PRD** : ne redonne **aucune** section « états » ; il cite `FUNDS_LOCKED`, `SHIPPED`, `RELEASED`, `REFUNDED`, `DISPUTED` au fil du texte, mais :
  - `INITIATED` a disparu du texte ;
  - le cycle de vie **pré-financement** introduit (transaction créée par invitation, « en attente d'acceptation », acceptée non financée, **expirée** — FR-P29, FR-P12) implique de **nouveaux états non nommés** (proposition : `PENDING_ACCEPTANCE`, `ACCEPTED`/`AWAITING_FUNDING`, `EXPIRED`) ;
  - `DELIVERY_CONFIRMED` : la Source B le traite en **événement** (« `DELIVERY_CONFIRMED` → `RELEASED` », FR-8) ; UJ-2 du nouveau PRD le note entre parenthèses comme s'il pouvait être un **état** (« Amina confirme la réception (`DELIVERY_CONFIRMED`) »). Ambiguïté à lever — d'autant que l'auto-libération FR-P12 crée un second chemin vers `RELEASED` sans cet événement.
- La question ouverte §9.7 (statut « en douane » distinct de `SHIPPED`) confirme que le modèle d'états est un point chaud : **le PRD production devrait posséder sa propre section machine à états** (états + événements + verrous), au lieu de dépendre du glossaire d'un PRD « absorbé ».

### 3.2 Terminologie des événements d'arbitrage : divergence A/B non arbitrée

- Source A : `RESOLVE_DISPUTE_RELEASE` / `RESOLVE_DISPUTE_REFUND`.
- Source B : `RESOLVE_RELEASE` / `RESOLVE_REFUND` (UJ-3).
- Nouveau PRD : ne nomme **aucun** des deux jeux (FR-P11 dit seulement « décision motivée et tracée »).

La divergence préexistante A/B n'est donc pas tranchée. Puisque le code livré suit vraisemblablement la Source B, le PRD production devrait fixer `RESOLVE_RELEASE`/`RESOLVE_REFUND` comme canoniques et déclarer les noms du POC obsolètes.

### 3.3 Sémantique de `FUNDS_LOCKED` déplacée par le wallet (FR-P1 vs FR-P25)

- **FR-P1** (résumé du backlog, antérieur au revirement wallet du 2026-07-24) : « Encaissement et immobilisation réels des fonds acheteur **via PSP** avant `FUNDS_LOCKED` » — modèle « paiement direct par transaction ».
- **FR-P25 / UJ-2** (modèle wallet réintégré) : le financement **débite le solde du wallet** (lequel a pu être alimenté bien avant, par PSP *ou* virement manuel FR-P23) ; `FUNDS_LOCKED` = débit wallet réussi, pas encaissement PSP synchrone.

Les deux lectures coexistent dans le document. Ce n'est pas une contradiction frontale (le wallet est lui-même alimenté par PSP), mais FR-P1, « une ligne » figée du backlog, décrit l'ancien modèle : elle devrait être re-libellée « financement effectif de la transaction (débit du wallet, fonds cantonnés) avant `FUNDS_LOCKED` » pour coller au modèle §1/§8 et à l'addendum §1.

### 3.4 Personas : fusion POC → scission production (cohérente)

Le POC fusionnait « Administrateur / Arbitre » ; la Source B avait déjà isolé l'arbitre (Koffi) ; le nouveau PRD ajoute l'opérateur (Nadia) et **interdit** à l'opérateur d'arbitrer (UJ-3). La chaîne est cohérente et le remplacement est assumé — aucun correctif requis, mais la matrice de rôles (ADMIN plateforme FR-P16 vs rôle arbitre vs rôles internes entreprise FR-P14) mériterait d'être posée une fois pour toutes en architecture.

---

## 4. Synthèse des correctifs recommandés (par priorité)

| # | Sévérité | Correctif |
|---|---|---|
| 1 | **Haute** | §6.E : exclure explicitement **FR-14** de la reprise normative (remplacée par FR-P33) — contradiction rétention illimitée vs ≥ 5 ans. |
| 2 | **Haute** | Ajouter au PRD production une **section machine à états** canonique : sort d'`INITIATED`, états pré-financement (invitation/acceptation/expiration FR-P29/P12), statut événement (pas état) de `DELIVERY_CONFIRMED`, événements d'arbitrage canoniques (`RESOLVE_RELEASE`/`RESOLVE_REFUND`, noms POC obsolètes). |
| 3 | Moyenne | Statuer sur les **NFR-1..NFR-6** de la Source B (au minimum : NFR-6 « pas d'antivirus, risque accepté » explicitement annulée par NFR-P antivirus à l'ingestion). |
| 4 | Moyenne | Re-libeller **FR-P1** au modèle wallet (débit du solde/cantonnement, pas « encaissement PSP avant FUNDS_LOCKED »). |
| 5 | Moyenne | §5 : remplacer « repris tels quels » par « repris et enrichis de la messagerie FR-P34 » (le dossier d'arbitrage = pièces + messages). |
| 6 | Basse | Expliciter la **configuration des webhooks sortants** (qui provisionne les URLs de callback : self-service partenaire ou back-office FR-P37) — seul élément du POC §4.3 sans successeur clair. |
| 7 | Basse | Assumer explicitement le **rétrécissement offline** (FR-P40 remplace la promesse POC §4.4 d'« actions de validation » hors-ligne). |

Aucun élément du POC n'est perdu sans successeur identifiable ; aucune contradiction n'est détectée entre le nouveau PRD et son propre addendum (l'historique wallet §1 de l'addendum est fidèle au §8/§9.6 du PRD).
