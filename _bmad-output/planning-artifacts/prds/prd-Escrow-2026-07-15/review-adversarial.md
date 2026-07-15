# Revue adversariale — PRD « Dépôt de preuves de transaction & de litige »

> Rôle : avocat du diable. Objectif : attaquer le PRD et l'addendum pour exposer contradictions, hypothèses cachées, exigences non testables et trous de sécurité **avant** que l'implémentation ne les fige.
> Contexte : POC d'apprentissage, enjeux modestes — mais la rigueur reste utile pédagogiquement.
> Documents attaqués : `prd.md`, `addendum.md`. Documents de contexte (référence) : `Docs/backend_schema_escrow.md`, `Docs/prd_escrow_platform.md`.

Verdict : le PRD est cohérent en surface mais s'effondre sur trois points structurants — (1) l'authentification partenaire n'associe aucun transporteur à aucune transaction, (2) l'invariant « preuve obligatoire à l'ouverture » entre en collision avec la transition `FUNDS_LOCKED → DISPUTED` du socle et avec le retrait logique, (3) le comportement hors-ligne optimiste n'a aucune stratégie de réconciliation en cas de conflit. Plusieurs NFR de sécurité sont énoncées mais non testables (« type MIME réel », « anti-rejeu ») faute de mécanisme spécifié.

---

## CRITIQUES

### C-1 — Le partenaire HMAC peut attacher une preuve à *n'importe quelle* transaction
**Sévérité : CRITIQUE.** Zone : carrier / HMAC.
**Exigences :** FR-5, UJ-4, addendum §3 (`POST /api/v1/partner/escrow/{id}/evidence`), schéma `webhook_subscriptions`.

Le socle ne contient **aucun lien entre un transporteur et une transaction**. `webhook_subscriptions` associe un `secret_key` à un `company_id` + un `event_type`, rien de plus. L'endpoint partenaire prend `{id}` de transaction en paramètre d'URL, sans contrainte d'appartenance.

**Scénario d'échec :** le transporteur T (ou un agrégateur de paiement, ou tout `company` disposant d'un abonnement webhook actif donc d'un `secret_key` valide) signe correctement un POST vers `/api/v1/partner/escrow/{id_d'une_transaction_qui_ne_le_concerne_pas}/evidence` avec une photo fabriquée « marchandise intacte à la livraison ». Le système l'accepte (signature valide) et la pièce devient une **preuve neutre horodatée** visible de l'arbitre. Un partenaire compromis ou malveillant peut ainsi empoisonner l'instruction de litiges arbitraires.

**Correctif :** définir explicitement quelle(s) transaction(s) un partenaire peut alimenter (p. ex. table de liaison `transaction ↔ carrier company`, renseignée à l'expédition ; ou jeton/scope signé côté plateforme lié à `{id}`). Rejeter tout POST dont le `company_id` authentifié n'est pas le transporteur désigné de `{id}`. Rendre l'exigence testable : « un partenaire ne peut déposer que sur les transactions où il est enregistré comme transporteur ».

### C-2 — `FUNDS_LOCKED → DISPUTED` rend l'invariant « preuve obligatoire » impossible à satisfaire
**Sévérité : CRITIQUE (correction fonctionnelle).** Zone : contradiction inter-exigences / machine à états.
**Exigences :** FR-1 (« dès l'état `SHIPPED` »), FR-6 (« ≥ 1 pièce à l'ouverture »), matrice du socle (ligne `FUNDS_LOCKED | OPEN_DISPUTE | DISPUTED | Acheteur/Vendeur`).

La machine à états du socle autorise `OPEN_DISPUTE` **depuis `FUNDS_LOCKED`** (« blocage de sécurité en cas d'anomalie détectée en amont »). Mais FR-1 interdit tout dépôt de pièce avant `SHIPPED`, et FR-6 exige au moins une pièce pour ouvrir un litige. Les deux ne peuvent être vraies simultanément sur le chemin `FUNDS_LOCKED → DISPUTED`.

**Scénario d'échec :** une partie détecte une anomalie en `FUNDS_LOCKED` (avant expédition) et tente `OPEN_DISPUTE`. FR-6 réclame une pièce ; FR-1 refuse tout dépôt car l'état n'est pas `SHIPPED`. La transition documentée du socle devient inexécutable — soit on viole FR-1, soit on viole FR-6, soit on casse une transition existante sans le dire.

**Correctif :** trancher explicitement : (a) supprimer/désactiver `FUNDS_LOCKED → DISPUTED` dans le périmètre de cette feature, ou (b) autoriser le dépôt dès `FUNDS_LOCKED` quand il accompagne un `OPEN_DISPUTE`, ou (c) exempter le chemin amont de FR-6. Le PRD doit nommer la transition du socle qu'il modifie.

---

## ÉLEVÉES

### H-1 — IDOR sur le téléchargement : `{eid}` non vérifié contre `{id}`
**Sévérité : ÉLEVÉE.** Zone : contrôle d'accès / download.
**Exigences :** FR-9, FR-11, addendum §3 (`GET /api/v1/escrow/{id}/evidence/{eid}/download`).

FR-9 définit la visibilité comme « partie **de la transaction** ». Mais l'URL porte deux identifiants indépendants. Si le contrôle se limite à « l'appelant est-il partie de `{id}` ? » sans vérifier « `{eid}` appartient-il bien à `{id}` ? », on a un IDOR classique.

**Scénario d'échec :** Amina est partie de la transaction A. Elle appelle `GET /api/v1/escrow/A/evidence/{eid_d'une_pièce_de_la_transaction_B}/download`. Le back voit qu'elle est autorisée sur A, sert le binaire de B. Fuite transverse de preuves confidentielles entre transactions.

**Correctif :** exiger conjointement `evidence.transaction_id == {id}` **et** appelant autorisé sur `{id}` ; renvoyer 404 (pas 403) si l'`eid` n'appartient pas à `{id}`. Idem pour `/withdraw`. Ajouter un test négatif explicite.

### H-2 — HMAC entrant : mécanisme non spécifié (corps non signé, identification de clé, fenêtre de rejeu)
**Sévérité : ÉLEVÉE.** Zone : carrier / HMAC / rejeu.
**Exigences :** FR-5, NFR-5, addendum §3.

« Réutilise le mécanisme webhook » est trompeur : le webhook existant est **sortant** (la plateforme signe, le partenaire vérifie). Ici le flux est **entrant** et pose trois questions non traitées : (1) comment l'endpoint sait-il **quel `secret_key`** utiliser pour vérifier, avant d'avoir authentifié l'appelant ? (aucun en-tête d'identité/clé spécifié) ; (2) la signature couvre-t-elle **le corps multipart (le fichier)** ou seulement des en-têtes ? ; (3) quelle est la **fenêtre d'horodatage** et y a-t-il un **nonce** ?

**Scénario d'échec (tamper) :** si la signature ne couvre pas le corps, un intermédiaire capture un POST légitime et **remplace le fichier** en conservant en-têtes/signature → preuve falsifiée acceptée.
**Scénario d'échec (rejeu) :** sans nonce, tout POST capturé est rejouable tant qu'il est dans la fenêtre horaire ; « un horodatage pour limiter le rejeu » ne suffit pas à l'empêcher.

**Correctif :** spécifier — en-tête d'identifiant de clé/partenaire ; HMAC calculé sur `timestamp + corps` (hash du fichier inclus) ; fenêtre bornée (p. ex. ±5 min) **et** nonce à usage unique persisté ; rejet 401 sinon. Rendre chaque clause testable.

### H-3 — Hors-ligne optimiste : aucune stratégie de réconciliation en cas de conflit à la synchro
**Sévérité : ÉLEVÉE.** Zone : offline / atomicité / conflit sur synchro.
**Exigences :** FR-15, UJ-1, addendum §5.

L'UI affiche `DISPUTED` de façon optimiste, mais la transaction peut avoir bougé côté serveur pendant que l'utilisateur était hors-ligne. Le socle prévoit `DELIVERY_CONFIRMED → RELEASED` (validation acheteur **ou système**, « libération par preuve logistique automatique »). FR-8 verrouille `RELEASED`/`REFUNDED`.

**Scénario d'échec :** Amina ouvre un litige hors-ligne (UI = `DISPUTED`). Pendant ce temps, le système auto-libère les fonds (`RELEASED`) ou l'arbitre tranche (`REFUNDED`). À la reconnexion, le `OPEN_DISPUTE` en file est rejeté (état terminal, FR-8). L'app a montré `DISPUTED` à tort, les fonds sont déjà partis, et **aucun comportement n'est défini** : rollback ? notification ? conservation de la pièce orpheline ?

**Correctif :** spécifier la réconciliation : détection de conflit à la synchro (version/état attendu vs réel), rollback visible de l'affichage optimiste, message à l'utilisateur, sort des pièces déjà mises en file. Ajouter un test « OPEN_DISPUTE en file rejoué contre transaction devenue terminale ».

### H-4 — Autorisation de retrait non définie : une partie peut supprimer les preuves adverses
**Sévérité : ÉLEVÉE.** Zone : contrôle d'accès / intégrité.
**Exigences :** FR-12, addendum §3 (`/withdraw`).

FR-12 dit qu'une pièce « déposée par erreur » peut être marquée retirée, **sans dire par qui**. Dans un contexte contradictoire, c'est un trou béant.

**Scénario d'échec :** Thabo (vendeur) appelle `/withdraw` sur une photo de dommages déposée par Amina. Si l'endpoint ne restreint pas au déposant, il **neutralise la preuve à charge** (elle reste « visible comme retirée » mais son poids probatoire chute). Autre variante : qui peut retirer une pièce **partenaire** (`uploaded_by_user_id` null) ?

**Correctif :** restreindre le retrait au déposant de la pièce (et éventuellement l'arbitre, à documenter) ; interdire à une partie de retirer la pièce d'une autre ; définir qui retire une pièce partenaire. Journaliser l'acteur (déjà prévu via `withdrawn_by_user_id`, mais la règle d'autorisation manque).

### H-5 — « Type MIME réel » : non testable et fenêtre XSS au téléchargement des PDF
**Sévérité : ÉLEVÉE.** Zone : validation MIME / sécurité fichiers.
**Exigences :** FR-3, NFR-2, NFR-6 (pas de scan).

« Validation du type MIME **réel** côté serveur » ne dit pas **comment** (sniffing de magic bytes ? en-tête `Content-Type` ? extension ?). Sans mécanisme, l'exigence n'est pas testable et laisse passer des polyglots. De plus, un PDF accepté peut contenir du JavaScript ; servi **inline** avec `Content-Type: application/pdf` il s'exécute dans le contexte du domaine.

**Scénario d'échec :** un fichier avec en-tête PDF valide mais charge active (PDF/JS, ou polyglot HTML) passe la validation « MIME réel » basée sur les magic bytes. À `GET .../download` sans `Content-Disposition: attachment` ni `X-Content-Type-Options: nosniff`, le navigateur l'ouvre inline → exécution/XSS. NFR-6 assume explicitement l'absence de scan, ce qui aggrave.

**Correctif :** spécifier la détection (magic bytes **+** cohérence extension/`Content-Type`) ; forcer `Content-Disposition: attachment` et `X-Content-Type-Options: nosniff` sur le download ; servir depuis un domaine/chemin sans cookies de session. Rendre FR-3 testable avec des fichiers de test (renommé, polyglot, MIME mensonger).

### H-6 — Journal d'audit inapplicable au dépôt partenaire (schéma incompatible)
**Sévérité : ÉLEVÉE.** Zone : audit / intégrité.
**Exigences :** FR-13, NFR-3, schéma `audit_logs`, addendum §6.

FR-13 exige une entrée d'audit **immuable pour chaque dépôt et chaque retrait**. Or `audit_logs` a `action_by` (FK `users`) et `previous_state`/`next_state` orientés transition financière. Un dépôt partenaire n'a **pas d'utilisateur** (`uploaded_by_user_id` null) et un dépôt/retrait n'est **pas une transition d'état**. L'addendum §6 reconnaît que la solution n'est « pas tranchée ».

**Scénario d'échec :** dépôt partenaire → il faut écrire un `audit_logs` avec `action_by = null` (FK potentiellement non-nullable) et sans transition d'état → l'écriture échoue ou stocke des lignes incohérentes ; FR-13 n'est pas satisfaite pour tout le chemin carrier.

**Correctif :** décider maintenant le modèle d'audit (colonne `action_type` + acteur polymorphe user/partenaire + `evidence_id`) et rendre `action_by`/états nullables pour les événements non-financiers. Sinon FR-13 est non implémentable pour le carrier.

---

## MOYENNES

### M-1 — `original_filename` affiché : XSS stocké
**Sévérité : MOYENNE.** FR-10 liste le `original_filename`. L'assainissement (NFR-2) ne concerne que le **chemin de stockage** (UUID) ; le nom d'origine est conservé tel quel (`VARCHAR(255)`) et rendu dans la liste PWA. Un fichier nommé `<img src=x onerror=...>.jpg` déclenche un XSS stocké dans l'écran de consultation. **Correctif :** échapper/assainir le `original_filename` à l'affichage et/ou à l'enregistrement.

### M-2 — La pièce obligatoire d'ouverture peut être retirée → invariant FR-6 violé a posteriori
**Sévérité : MOYENNE.** FR-6 garantit « 100 % des litiges ont ≥ 1 preuve », mais FR-12 permet de retirer une pièce sans exception. **Scénario :** litige ouvert avec exactement une pièce, puis cette pièce est retirée → litige à 0 pièce active, la métrique de succès « garanti par conception » devient fausse. **Correctif :** interdire le retrait de la dernière pièce active d'un litige (ou de la pièce d'ouverture), ou requalifier la métrique en « ≥ 1 pièce, active ou retirée ».

### M-3 — Absence d'idempotence sur le rejeu de la file offline
**Sévérité : MOYENNE.** FR-15/addendum §5 parlent d'atomicité mais pas d'idempotence. Un rejeu qui échoue partiellement au réseau (réponse perdue) sera re-tenté → double ouverture de litige et/ou pièces dupliquées. **Correctif :** clé d'idempotence par entrée de file, dédoublonnage côté serveur.

### M-4 — Quota IndexedDB dépassé en hors-ligne (jusqu'à ~20 × 10 Mo, base64 +33 %)
**Sévérité : MOYENNE.** L'addendum §5 stocke les binaires en IndexedDB ; l'hypothèse §8 pose une limite **souple** ~20 pièces sans plafond dur. 20 fichiers de 10 Mo encodés base64 ≈ 260 Mo, au-delà du quota navigateur usuel → écriture silencieuse en échec, perte de preuves déposées hors-ligne. **Correctif :** plafond dur du nombre/taille cumulée en file offline, gestion explicite du dépassement de quota, stockage en Blob (pas base64).

### M-5 — Pas de rejet précoce à la taille : DoS disque/mémoire
**Sévérité : MOYENNE.** FR-4 / `size_bytes ≤ 10 485 760` ne dit pas **où** la limite est appliquée. Sans coupure au streaming (vérification `Content-Length` + arrêt de lecture), un attaquant envoie 2 Go, le serveur bufferise/écrit sur disque avant de rejeter. Multipart multi-fichiers aggrave. **Correctif :** rejet au `Content-Length`, plafond de flux, limite de taille de requête globale.

### M-6 — « arbitre de la transaction » n'existe pas dans le modèle
**Sévérité : MOYENNE.** FR-1 et §3 parlent d'« arbitre **de la transaction** », mais `escrow_transactions` n'a aucun champ d'arbitre assigné ; le socle n'a qu'un rôle `ADMIN` global. Donc **tout** ADMIN voit/dépose sur **toute** transaction — contradiction entre le libellé (portée à la transaction) et le modèle (portée globale). **Correctif :** soit assumer « tout ADMIN » explicitement, soit ajouter l'assignation d'arbitre et l'imposer dans le contrôle d'accès.

### M-7 — Verrouillage post-résolution non redit pour l'endpoint partenaire
**Sévérité : MOYENNE.** L'addendum §3 pose « refus si litige tranché » **uniquement** sur l'endpoint utilisateur. L'endpoint partenaire n'est pas gaté. **Scénario :** un carrier POST une pièce sur une transaction déjà `RELEASED`/`REFUNDED` → dépôt sur dossier verrouillé, en contradiction avec FR-8. **Correctif :** appliquer la même garde d'état (`SHIPPED..non-terminal`) à l'endpoint partenaire.

### M-8 — Ouverture « par référence » à une pièce pré-déposée : référence pendante
**Sévérité : MOYENNE.** Addendum §4 offre deux voies : pièce fournie dans la requête d'ouverture, **ou** ouverture référençant « une pièce venant d'être déposée ». La seconde crée une fenêtre : la pièce référencée peut être **retirée**, appartenir au **carrier** ou à la **partie adverse**, ou avoir été déposée sur un autre litige. **Scénario :** Amina dépose une pièce (état `SHIPPED`), la retire, puis ouvre le litige en la référençant → litige ouvert avec 0 pièce active. **Correctif :** privilégier l'endpoint composite atomique ; si référence, valider que la pièce est `ACTIVE`, appartient à la transaction et au disputant.

---

## FAIBLES

### L-1 — Métrique « réduction du délai moyen de résolution » sans base de comparaison
**Sévérité : FAIBLE.** §7 : non mesurable dans un POC sans historique/baseline. **Correctif :** définir la baseline ou requalifier en métrique instrumentée post-lancement.

### L-2 — Limite « souple ~20 » non applicable = non-exigence
**Sévérité : FAIBLE.** §8 : une limite non appliquée n'est pas testable. **Correctif :** soit un plafond dur, soit la retirer des exigences.

### L-3 — Commentaire obligatoire sans longueur minimale
**Sévérité : FAIBLE.** FR-2 + question OPEN §8 : un commentaire d'un espace satisfait « obligatoire » sans valeur probatoire → non testable utilement. **Correctif :** trancher une longueur minimale (p. ex. ≥ 10 caractères non blancs) ou assumer explicitement « présence non vide suffit ».

### L-4 — Service statique des fichiers hors endpoint autorisé
**Sévérité : FAIBLE (mais à surveiller).** NFR-2 interdit l'URL publique devinable (UUID = bien), mais rien n'interdit d'exposer par erreur le répertoire de stockage via le reverse-proxy (nginx présent dans la stack). **Correctif :** exiger explicitement que le répertoire `evidence` ne soit **jamais** servi statiquement ; tout accès passe par l'endpoint contrôlé.

---

## Synthèse des contradictions inter-exigences (récap)
- **FR-1 vs FR-6 vs socle `FUNDS_LOCKED→DISPUTED`** (C-2).
- **FR-6 vs FR-12** — retrait annule l'invariant preuve (M-2).
- **FR-9/FR-11 vs endpoint download** — visibilité par transaction vs `{eid}` non lié (H-1).
- **FR-13/NFR-3 vs schéma `audit_logs`** — audit du dépôt partenaire impossible (H-6).
- **Addendum §3 (garde d'état) — utilisateur vs partenaire** — verrouillage FR-8 non appliqué au carrier (M-7).
- **FR-1/§3 « arbitre de la transaction » vs modèle sans assignation** (M-6).

## Exigences énoncées mais non testables (récap)
- NFR-2 « type MIME réel » (mécanisme absent) — H-5.
- NFR-5/FR-5 « horodatage anti-rejeu » (fenêtre, nonce, périmètre signé absents) — H-2.
- §7 « réduction du délai de résolution » (pas de baseline) — L-1.
- §8 limite souple ~20 (non appliquée) — L-2.
- FR-2 commentaire obligatoire (longueur min. non définie) — L-3.
