# Epic 2 Context : Litige adossé à la preuve

<!-- Généré à partir des artefacts de planification. Régénérer avec compile-epic-context si les documents de planification changent. -->

## Objectif

Ancrer tout litige à une preuve vérifiable. Ouvrir un litige exige au moins une pièce jointe et un commentaire, garanti par un endpoint composite atomique (`POST /api/v1/escrow/{id}/dispute`) qui fait passer la transaction à `DISPUTED` ET attache la ou les pièces dans une seule unité transactionnelle. Tant que le litige n'est pas tranché, acheteur, vendeur et arbitre continuent de déposer des contre-preuves ; une partie peut retirer sa propre pièce par retrait logique, sans jamais faire tomber le dossier sous le plancher d'une preuve minimum. Dès que la transaction atteint un état terminal (`RELEASED`/`REFUNDED`), tout dépôt et tout retrait sont verrouillés, figeant définitivement le dossier de preuves. Cet epic dépend d'Epic 1 (table `evidence_files`, port de stockage, validation, audit, contrôle d'appartenance).

## Stories

- Story 2.1 : Ouvrir un litige avec preuve obligatoire (atomique)
- Story 2.2 : Verrouiller dépôt & retrait aux états terminaux
- Story 2.3 : Retirer sa propre pièce avec plancher de preuve
- Story 2.4 : Interface PWA — ouvrir un litige & fil contradictoire

## Exigences & Contraintes

- Aucun litige « à vide » : l'ouverture requiert au moins un fichier valide ET un commentaire ≥ 10 caractères ; à défaut, rejet `400` et aucune transition d'état.
- L'ouverture n'existe pas comme endpoint indépendant : l'événement `OPEN_DISPUTE` est toujours porté par l'endpoint composite avec pièce(s).
- Ouverture autorisée uniquement depuis `FUNDS_LOCKED` ou `SHIPPED` par une partie prenante ; toute autre transition d'origine (`DISPUTED`, `RELEASED`, `REFUNDED`) renvoie `409`.
- Dépôt et retrait autorisés ssi l'état appartient à `{FUNDS_LOCKED, SHIPPED, DISPUTED}` ; verrou dès `{RELEASED, REFUNDED}`. Le verrou s'applique identiquement après arbitrage (`RESOLVE_*`) et après libération normale (`DELIVERY_CONFIRMED`).
- Tant que l'état est `DISPUTED`, les trois parties (acheteur, vendeur, arbitre) peuvent déposer une contre-preuve.
- Retrait strictement de sa propre pièce (`403` sinon) ; en `DISPUTED`, un retrait est refusé `409` s'il ferait passer le nombre de pièces `ACTIVE` — toutes parties confondues, y compris partenaire/adverse — sous le plancher d'une preuve minimum.
- La validation d'ingestion (content-sniffing, bornes de taille, clé opaque, audit) doit être partagée avec le dépôt simple d'Epic 1 : aucune règle dupliquée, mêmes rejets `400` sur les deux endpoints.

## Décisions techniques

- **Atomicité composite** : l'ouverture s'exécute dans une seule frontière `@Transactional` du service. Si la persistance d'une pièce échoue (ex. stockage objet indisponible), tout est annulé — pas de passage à `DISPUTED`, aucune ligne `evidence_files`. Un test de rollback est exigé et non négociable.
- **Nettoyage du stockage objet au rollback** : le stockage objet n'étant pas transactionnel, tout écrivain enregistre un nettoyage best-effort `afterCompletion` qui supprime (`delete()`, idempotent) les clés déjà écrites si la transaction est annulée, pour éviter les binaires orphelins. `delete()` ne doit jamais masquer la cause du rollback.
- **Verrou fondé sur l'état, pas sur l'événement** : la garde est une vérification serveur de l'état courant, jamais dérivée du dernier événement. Elle est centralisée dans le service et couverte en étendant `EscrowStateMachineTest`.
- **Retrait logique uniquement** : jamais de suppression physique ; `status ACTIVE → WITHDRAWN` avec `withdrawn_at`/`withdrawn_by_user_id` renseignés ; la pièce reste visible comme « retirée ».
- **Plancher sous concurrence** : le compte de pièces actives doit tenir face à deux retraits concurrents (deux appareils / requêtes parallèles) — au plus un réussit, l'autre reçoit `409`. Un verrou optimiste (`@Version`) ou une vérification transactionnelle du compte est requis, avec un test de concurrence explicite.
- **Contrôle d'appartenance centralisé** : tout endpoint (ouverture, dépôt, retrait) charge d'abord la transaction et passe par le même contrôle « partie prenante ? » avant toute opération (anti-IDOR).
- **Audit dans la même transaction** : chaque dépôt/retrait, ainsi que la transition d'ouverture, écrit une entrée `audit_logs` en propagation MANDATORY (commit atomique). Le payload JSONB porte `action ∈ {EVIDENCE_ADDED, EVIDENCE_WITHDRAWN}`, `evidenceId`, `sha256`, et l'heure client de capture pour un dépôt différé. Aucune colonne `action_type` ajoutée.
- **Intégrité en base** : les invariants d'attribution et de retrait de `evidence_files` sont tenus par des contraintes DB (`CHECK` de retrait : `ACTIVE` ⟺ champs de retrait null ; `WITHDRAWN` ⟺ non-null), pas par la seule discipline applicative.
- **Contrat multipart figé** : noms de champs identiques sur tous les endpoints — `files[]` (1..N), `comment`, `clientCapturedAt` (optionnel, ISO-8601). L'endpoint composite réutilise ces noms pour rester compatible avec le rejeu offline d'Epic 4.
- **Horodatage serveur** : `created_at` = heure serveur à la réception, clé du tri chronologique ; l'heure client n'est conservée que dans le payload d'audit.
- **Découpage** : `EscrowService` étendu pour l'ouverture composite ; garde d'état et retrait dans `EvidenceService` ; controller mince ; erreurs via l'enveloppe globale existante (`400`/`403`/`409`).

## UX & Modèles d'interaction

- **Ouvrir un litige** : depuis le détail d'une transaction `FUNDS_LOCKED`/`SHIPPED`, le formulaire exige au moins un fichier ET un commentaire ≥ 10 caractères avant d'autoriser la soumission (miroir front des règles serveur, le serveur restant l'autorité). Après succès, l'état affiché passe à `DISPUTED`.
- **Fil contradictoire** : en `DISPUTED`, la vue présente le fil chronologique des pièces des deux parties et de l'arbitre, avec la possibilité de déposer une contre-preuve. Toutes les parties voient toutes les pièces, y compris celles marquées « retirées » (jamais masquées).

## Dépendances inter-stories

- Story 2.1 (endpoint composite) fournit la logique de validation partagée et le point d'entrée réutilisé par l'ouverture offline d'Epic 4.
- Story 2.2 (verrou terminal) borne les fenêtres de dépôt/retrait exploitées par les Stories 2.1 et 2.3.
- Story 2.4 (PWA) consomme l'endpoint composite (2.1) et l'action de retrait (2.3).
