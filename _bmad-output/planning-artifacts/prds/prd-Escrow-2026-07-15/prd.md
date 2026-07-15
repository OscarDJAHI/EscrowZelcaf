---
title: "PRD — Dépôt de preuves de transaction & de litige (Evidence Upload)"
status: final
created: 2026-07-15
updated: 2026-07-15
---

# PRD — Dépôt de preuves de transaction & de litige

> **Fonctionnalité additionnelle** à la plateforme d'Escrow B2B ZLECAf.
> Approfondit et concrétise la section §4.2 « Système de Gestion des Litiges » du PRD plateforme (`Docs/prd_escrow_platform.md`).
> **Contexte POC d'apprentissage** : l'objectif est autant de livrer la fonctionnalité que d'approfondir le métier de l'escrow et la méthode BMad. La rigueur est modérée et le document se veut pédagogique.

---

## 1. Contexte & objectif

Dans un séquestre B2B transfrontalier, la **preuve** est le pivot de la confiance : c'est elle qui permet à un arbitre de trancher équitablement quand acheteur et vendeur sont en désaccord. Le PRD plateforme mentionne le « dépôt de preuves » mais n'en définit ni le cycle de vie, ni les règles, ni le modèle de données (aucune table de pièces jointes n'existe aujourd'hui).

Cette fonctionnalité comble ce vide. Elle permet aux parties d'une transaction — et à un partenaire logistique — d'**attacher des pièces justificatives** (photos, documents) à une transaction, et fait de la présence d'au moins une preuve la **condition d'ouverture d'un litige**.

## 2. Périmètre

**Dans le périmètre**
- Dépôt de pièces sur une transaction **à partir de l'état `FUNDS_LOCKED`** (couvre les litiges ouverts avant *ou* après expédition, la plateforme autorisant `OPEN_DISPUTE` depuis `FUNDS_LOCKED` comme depuis `SHIPPED`).
- Preuve **obligatoire** à l'ouverture d'un litige (`OPEN_DISPUTE`), quel que soit l'état de départ.
- Trois contributeurs humains : **acheteur, vendeur, arbitre**.
- Un contributeur **machine** : le **livreur/transporteur**, intégré comme **partenaire API** (pas d'écran ni de compte interactif).
- **Visibilité contradictoire** : chaque partie et l'arbitre voient toutes les pièces.
- **Consultation, téléchargement, retrait logique** (« marquée retirée »).
- **Dépôt hors-ligne** via la PWA (file d'attente + synchro).
- **Stockage objet MinIO (S3-compatible)** dès le POC, derrière une couche d'accès isolée `EvidenceStorage` (voir architecture).

**Hors périmètre (POC) — voir §9 Évolutions**
- Le livreur comme acteur interactif complet (rôle `CARRIER` avec écrans dédiés).
- Scan antivirus / anti-malware des fichiers.
- **Chiffrement par fichier au repos** (le stockage objet MinIO/S3-compatible est, lui, **dans** le périmètre — voir architecture).
- Purge / rétention automatique.
- OCR / analyse automatique des documents douaniers.

## 3. Acteurs

| Rôle | Protagoniste | Enjeu propre |
|---|---|---|
| 🛒 Acheteur | **Amina**, grossiste à Nairobi (KEN) | Prouver la non-conformité / non-livraison pour être remboursée. |
| 📦 Vendeur | **Thabo**, producteur à Johannesburg (ZAF) | Prouver la conformité et l'expédition pour être payé. |
| ⚖️ Arbitre | **Koffi**, arbitre plateforme | Disposer d'un dossier complet et neutre pour trancher, et y verser ses propres pièces. |
| 🚚 Livreur | **Partenaire logistique** (machine) | Fournir une preuve neutre et horodatée de l'état de la marchandise à la livraison. |

## 3bis. Glossaire

| Terme | Définition |
|---|---|
| **Pièce / preuve** | Fichier (JPG, PNG ou PDF, ≤ 10 Mo) attaché à une transaction pour documenter son état ou étayer un litige. |
| **Contradictoire** | Principe où **toutes les parties** (et l'arbitre) voient **toutes** les pièces, comme dans un procès. |
| **Retrait logique** | Marquage d'une pièce comme « retirée » (`status = WITHDRAWN`) sans suppression physique ; elle reste tracée. |
| **`CARRIER_PARTNER`** | Type de déposant correspondant au **livreur/transporteur** intégré comme **partenaire machine** (API + HMAC), et non comme utilisateur interactif. |
| **File d'attente hors-ligne** | Mécanisme de la PWA (`OfflineQueueStore`) qui met en attente les actions faites sans réseau et les rejoue à la reconnexion. |
| **Rejeu atomique** | Rejeu, en une seule unité indivisible, de l'ouverture de litige **et** de sa/ses pièce(s) : tout réussit ou tout échoue. |
| **États escrow** | `INITIATED` → `FUNDS_LOCKED` → `SHIPPED` → `RELEASED` ; branches `DISPUTED` → `RELEASED`/`REFUNDED`. |

## 4. Parcours utilisateurs

**UJ-1 — Amina ouvre un litige (déclencheur principal).**
Transaction à l'état `SHIPPED` ; la marchandise reçue est abîmée. Depuis le détail de la transaction (PWA), Amina lance « Ouvrir un litige ». Le système **exige au moins une pièce** : elle joint des **photos** des dommages, ajoute un **commentaire** décrivant le problème, puis valide. L'état passe à `DISPUTED` ; Thabo et Koffi sont notifiés. *Cas hors-ligne :* si Amina n'a pas de réseau, l'ouverture + la ou les pièce(s) sont **mises en file d'attente atomiquement** et synchronisées à la reconnexion ; l'app affiche `DISPUTED` de façon optimiste en attendant.

**UJ-2 — Thabo se défend.**
Notifié, Thabo consulte les preuves d'Amina (contradictoire), puis **dépose ses contre-preuves** (bordereau d'expédition, photos d'emballage avant envoi) avec un commentaire. Il peut déposer **tant que le litige n'est pas tranché**.

**UJ-3 — Koffi arbitre.**
Koffi consulte **toutes les pièces des deux parties**, dans l'ordre chronologique. Il **verse ses propres pièces** (rapport d'arbitrage, document douanier obtenu), puis rend sa décision (`RESOLVE_RELEASE` → `RELEASED`, ou `RESOLVE_REFUND` → `REFUNDED`). Après décision, **le dossier est verrouillé** : plus aucun dépôt possible.

**UJ-4 — Le livreur verse une preuve (machine).**
À la livraison (état `SHIPPED`), le partenaire logistique **pousse des photos** de l'état de la marchandise via un **appel API signé (HMAC-SHA256)**. La pièce est rattachée à la transaction et devient visible de toutes les parties — potentiellement **avant même** qu'un litige soit ouvert, où elle servira de preuve neutre.

## 5. Exigences fonctionnelles (FR)

**A. Dépôt de pièces**
- **FR-1** — Un utilisateur autorisé (acheteur, vendeur ou arbitre **de la transaction concernée**) peut attacher 1..N pièces à une transaction dès l'état `FUNDS_LOCKED` (et jusqu'au verrou de FR-8).
- **FR-2** — Chaque pièce porte un **commentaire** du déposant. Le commentaire est **obligatoire** lors de l'ouverture d'un litige (UJ-1), avec une **longueur minimale de 10 caractères**, et **optionnel** pour les dépôts ultérieurs.
- **FR-3** — Seuls les types **JPG, PNG et PDF** sont acceptés ; tout autre type est rejeté avec un message explicite. Le contrôle porte sur le **type réel** du contenu (nombre magique / *content sniffing*), pas seulement l'extension ou l'en-tête déclaré (voir NFR-2).
- **FR-4** — La **taille maximale** est de **10 Mo par fichier** ; un fichier **vide (0 octet)** ou au-delà de la limite est rejeté.
- **FR-5** — Un **partenaire logistique** peut déposer des pièces via un endpoint API dédié, authentifié par **signature HMAC-SHA256**. Le partenaire ne peut déposer que sur les transactions **impliquant sa propre société** (`companies`) ; toute tentative sur une transaction tierce est rejetée (403).

**B. Litige**
- **FR-6** — L'événement `OPEN_DISPUTE` **exige au moins une pièce** : impossible d'ouvrir un litige « à vide ».
- **FR-7** — Tant que le litige n'est pas tranché, acheteur, vendeur et arbitre peuvent déposer des pièces.
- **FR-8** — Le dépôt et le retrait sont **verrouillés dès que la transaction est dans un état terminal** : `state ∈ {RELEASED, REFUNDED}`. Le verrou est **basé sur l'état** (et non sur l'événement) : il s'applique aussi bien après un arbitrage (`RESOLVE_*`) qu'après une libération normale (`DELIVERY_CONFIRMED` → `RELEASED`).

**C. Consultation & visibilité**
- **FR-9** — **Visibilité contradictoire** : acheteur, vendeur et arbitre voient **toutes** les pièces de la transaction.
- **FR-10** — Les pièces sont listées par **ordre chronologique**, avec déposant, date/heure, type, taille, commentaire et statut (active / retirée).
- **FR-11** — Un utilisateur autorisé peut **télécharger** le fichier original. Le système vérifie que la pièce demandée **appartient bien à la transaction** de l'URL **et** que le demandeur est partie prenante (acheteur, vendeur ou arbitre) de cette transaction — pas de référence directe d'objet non contrôlée (anti-IDOR, voir NFR-2).

**D. Cycle de vie & intégrité**
- **FR-12** — Le déposant peut marquer **sa propre** pièce « retirée » (retrait logique) ; **aucune suppression physique**, et **on ne peut jamais retirer la pièce d'un tiers** (cohérent avec le contradictoire). La pièce retirée reste visible comme telle dans l'historique. Tant que la transaction est `DISPUTED`, un retrait est **refusé s'il ferait passer le nombre de pièces actives sous le plancher obligatoire** de FR-6 (pas de litige « vidé » de ses preuves).
- **FR-13** — Chaque dépôt et chaque retrait génèrent une entrée **immuable** dans la piste d'audit (`audit_logs`).
- **FR-14** — **Rétention illimitée** des pièces (aucune purge automatique) pour le POC.

**E. Hors-ligne (PWA)**
- **FR-15** — Une partie peut ouvrir un litige et déposer des preuves **hors-ligne** ; l'événement et sa/ses pièce(s) sont mis en file d'attente **atomiquement** et synchronisés à la reconnexion, avec affichage **optimiste** de l'état `DISPUTED`.
- **FR-16** — **Réconciliation à la synchro** : si, à la reconnexion, le serveur **rejette** le rejeu (ex. transaction déjà résolue par une autre partie, ou pièce désormais invalide), la file **conserve** l'entrée et son binaire, **annule l'affichage optimiste**, et **notifie l'utilisateur** avec le motif et l'état réel de la transaction. Aucun fichier en attente n'est perdu silencieusement.

## 6. Exigences non-fonctionnelles (NFR)

- **NFR-1 (Stockage)** — Les fichiers sont stockés dans un **stockage objet MinIO (S3-compatible)** — bucket configurable — dès le POC. L'accès passe par une couche isolée (`EvidenceStorage`) : les endpoints ne connaissent qu'une **clé opaque**, jamais le backend de stockage, pour permettre une bascule (S3 managé, chiffrement) sans changer le contrat d'API.
- **NFR-2 (Sécurité)** — Validation **côté serveur** du type réel (content sniffing) et de la taille (jamais seulement côté client) ; **assainissement** des noms de fichiers et **prévention du *path traversal*** (nom de stockage généré, jamais dérivé du nom fourni) ; accès aux binaires **contrôlé par autorisation** (pas d'URL publique devinable, contrôle d'appartenance — FR-11) ; les fichiers sont servis en **`Content-Disposition: attachment`** (jamais *inline*) pour éviter tout XSS stocké via PDF/SVG.
- **NFR-3 (Intégrité & audit)** — Immuabilité des pièces (retrait logique uniquement) et cohérence transactionnelle (ACID) avec l'écriture d'audit, dans l'esprit de la plateforme existante.
- **NFR-4 (Résilience hors-ligne / bande passante)** — Le dépôt doit fonctionner sur réseau instable/limité (contexte ZLECAf) : file d'attente, payload maîtrisé. Compression/miniatures = optionnel (voir hypothèses).
- **NFR-5 (Partenaire)** — L'endpoint partenaire d'entrée utilise une **clé HMAC-SHA256 dédiée par partenaire** (distincte des clés de webhooks *sortants*), identifiée par un **key-id** dans la requête. La signature **couvre le corps** (fichier + métadonnées) et un **horodatage** ; un **nonce anti-rejeu** (stocké et vérifié) empêche la réémission d'une requête interceptée. Cohérent avec l'esprit de la sécurité webhook existante, mais sans réutiliser le secret sortant.
- **NFR-6 (Absence de scan malveillant — risque accepté POC)** — Aucun scan antivirus n'est réalisé ; ce risque est **explicitement accepté** pour le POC et documenté en §9.

## 7. Métriques de succès & contre-métriques

**Succès**
- 100 % des litiges ouverts comportent ≥ 1 preuve (garanti par conception — FR-6).
- Réduction du **délai moyen de résolution** d'un litige (dossier plus complet pour l'arbitre).
- Nombre moyen de pièces par litige (indicateur de richesse du dossier).

**Contre-métriques (à surveiller)**
- **Taux d'échec d'upload**, en particulier sur mobile / hors-ligne.
- **Taille moyenne des fichiers** (dérive = pression sur la bande passante).
- **Taux de pièces « retirées »** élevé = signal d'une UX de dépôt confuse.

## 8. Questions ouvertes & hypothèses

- **[ASSUMPTION]** Pas de **plafond dur** du nombre de pièces par transaction pour le POC (limite souple indicative ~20).
- **[ASSUMPTION]** Miniatures / prévisualisation côté PWA : **optionnel**, non requis pour le POC.
- **[RÉSOLU]** Longueur minimale du commentaire d'ouverture = **10 caractères** (voir FR-2).
- **[RÉSOLU]** Retour au partenaire livreur : le **code HTTP synchrone** (2xx/4xx) suffit pour le POC ; une notification asynchrone est une évolution.
- **[OPEN — non bloquant]** Faut-il **horodater côté serveur** (heure de réception) *en plus* de l'heure du client pour les dépôts hors-ligne différés ? À trancher en architecture (recommandé : les deux).

## 9. Évolutions futures (post-POC)

1. **Livreur en acteur complet** (rôle `CARRIER` : compte, écran d'upload, permissions).
2. **Scan antivirus / anti-malware** à l'ingestion (lève NFR-6).
3. **Chiffrement par fichier au repos** (le stockage objet MinIO/S3-compatible est déjà en place dès le POC).
4. **Rétention / purge** conforme (durées légales par juridiction ZLECAf).
5. **OCR / extraction** automatique des documents douaniers.

---

> Détails d'implémentation (schéma de table, endpoints REST, chemins de stockage, impact sur la file offline) : voir `addendum.md`.
