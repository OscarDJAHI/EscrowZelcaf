# Balayage du ledger `deferred-work.md` — 2026-07-27

Action item #4 de la rétrospective Epic 1. Chaque entrée a été confrontée au **code réel**, pas à la mémoire de la story qui l'a écrite.

---

## 1. Le compte annoncé était faux

Le ledger portait « 10 entrées ouvertes ». Le vrai chiffre était **59**.

La cause n'est pas un oubli de saisie mais un **schisme de format** : les entrées POC utilisent `- source_spec / summary / evidence / status`, tandis que les sections ajoutées pendant la chaîne production sont pour partie de simples puces sans champ `status:`. Tout comptage par `grep "status: OUVERT"` ne voyait donc qu'un tiers du ledger, et les 17 reports POC des lignes 140-209 — dont les « 14 reports offline » que le sprint-status suit comme action item — étaient invisibles depuis le début.

**Corrigé** : les 53 entrées sans statut en ont désormais un, avec leur routage. La modification est purement additive (55 insertions, 0 ligne supprimée, 73 entrées `source_spec` préservées).

| État | Avant | Après |
| --- | --- | --- |
| Entrées portant un statut | 39 | **92** |
| OUVERT | 10 *(annoncé)* | **55** |
| RÉSOLU | 26 | **30** |
| ACCEPTÉ | 4 | 5 |
| MITIGÉ / SKIP | 0 | 2 |

---

## 2. Entrées périmées — closes au balayage

Six entrées décrivaient un état du code déjà dépassé. Toutes vérifiées par lecture du code, aucune close sur la foi d'un commentaire de story.

| Entrée | Ce que le code dit |
| --- | --- |
| **File offline non clefée par utilisateur** *(POC)* | `offlineQueue.idb.js` expose `getAllForUser(userId)` / `clearForUser` ; une entrée sans `meta.userId` n'appartient à personne. **Story 1.9.** |
| **403 nu non traité comme session expirée** *(POC)* | `api/client.js` émet `escrow:session-expired` sur tout 401/403 **nu** avec jeton présent, en laissant délibérément passer les 4xx **codés**. **Story 1.9.** |
| **`logout()` ne vide ni la file ni IndexedDB** *(POC)* | `stores/session.js` appelle `idb.clearForUser(userId)` + `clearMemory()` avant l'appel réseau. **Story 1.9.** |
| **Store escrow non réinitialisé à la déconnexion** *(POC)* | `useEscrowStore().$reset()` et `useEvidenceStore().$reset()` dans la séquence de démontage. **Story 1.9.** |
| **Download non streamé sans plafond mémoire** *(1.7)* | `MinioEvidenceStorage.MAX_OBJECT_BYTES` contrôlé **deux fois** — `contentLength` déclaré, puis `readNBytes(MAX+1)`, ce qui couvre un `contentLength` menteur. **Revue de suivi 1.7, le jour même.** |
| **Test ClamAV inexécutable sur arm64** *(1.8)* | `assumeTrue(imagePresent, …)` : le test **saute** au lieu de casser le build. Statut ramené à MITIGÉ — le résiduel n'est plus un échec mais une absence de couverture locale sur arm64, routée en 11-1. |

**Ce que cela dit du processus :** quatre entrées POC ont été résolues par la Story 1.9 sans que personne ne referme le ledger, et une entrée 1.7 a été résolue par la revue de suivi de sa propre story quelques heures après avoir été écrite. Un ledger qu'on n'ouvre jamais accumule surtout du bruit — et le bruit masque le signal.

---

## 3. Défaut trouvé **pendant** le balayage, et corrigé

L'entrée « encodage hétérogène » de la Story 1.10 était **mal caractérisée**, et sa vraie forme est sérieuse.

`backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` (1 200 lignes) est en UTF-8 parfaitement **valide**. Il contenait 5 **octets NUL**, écrits par un littéral d'en-tête magique GIF portant les octets bruts :

```java
byte[] gif = "GIF87a\x01\x00\x01\x00\x00\x00\x00,".getBytes(StandardCharsets.US_ASCII);
```

Conséquences **mesurées, pas déduites** : `file` répond `data`, git traite le fichier en binaire (non diffable, non cherry-pickable, non fusionnable en 3-way), et surtout `grep -c "void "` renvoyait **0 ligne, code de sortie 1**. Le plus gros fichier de test du service de preuves était **invisible à toute recherche textuelle** — y compris aux greps de vérification que chaque story de ce projet utilise comme preuve.

C'est le défaut exact trouvé sur `AuthView.vue` à la 2ᵉ revue de suivi de la Story 1.9, toujours vivant à un autre endroit. Le ledger le décrivait comme un problème d'encodage, ce qui a envoyé le diagnostic dans la mauvaise direction : `iconv -f UTF-8` passe sans erreur.

**Corrigé** : tableau d'octets explicite (`{'G','I','F','8','7','a', 0x01, 0x00, …}`), qui supprime le littéral et toute subtilité d'échappement. `grep` voit maintenant 50 lignes `void ` dans ce fichier. Suite verte.

---

## 4. Partition des 55 entrées ouvertes

### Bundles — groupés parce qu'ils se posent au même endroit

| Bundle | Entrées | Cible | Pourquoi ce regroupement |
| --- | --- | --- | --- |
| **QUALITÉ-CI** | 5 | **Story 11-1** | ESLint absent, garde d'encodage/NUL, classe de base Testcontainers, couplage de la suite front, **aucun test de composant** alors que `@vue/test-utils` est installé depuis la 4.1. Tous se posent dans la CI, en une seule fois. |
| **SESSION-PARTAGÉE** | 9 | **tête d'Epic 2** | Déconnexion non propagée aux onglets, index d'expiration Workbox, `logoutUser` sans timeout, courses de lecture A→B, verrou de rejeu. Tous sur la surface que l'Epic 2 réécrit. |
| **STACK-PROD** | 9 | **Story 11-3** | XFF/trusted-proxy, rate-limiting distribué, cache DNS nginx, certificat XOR, rolling update du chiffrement, pagination du balayage, images non épinglées par digest. |
| **OBSERVABILITÉ** | 2 | **Story 11-4** | Enveloppe d'erreur absente sur les exceptions **de filtre** (dernier trou : la 1.10 a couvert les rejets Spring MVC, pas ce qui est levé avant le `@ControllerAdvice`) ; aucune sonde de santé antivirus. |
| **DOC anti-énumération** | 3 | passe unique, sans risque | 33 mentions de `NOT_A_PARTY` dans du code et des tests vivants, 3 artefacts de planification dont le texte d'AD-10 a dérivé, un commentaire de test faux. |
| **OFFLINE POC** | 5 | **Epic 9** | Reports POC restants après retrait des 4 clos par la 1.9. |

### Routés individuellement

Story 11-8 (charge) : lookup DB par requête authentifiée, scans concurrents non bornés, race check-then-act du limiteur. — Story 11-5 : absence de colonne d'empreinte sur `evidence_files`. — Story 7-1 : AAD constante globale. — Story 8-3 : retrait de clé à chaud cassant toutes les livraisons webhook. — Story 7-x : amorçage ADMIN sans rotation. — **Epic 2 (2-4)** : `existsByEmail` puis `save` non atomique à l'inscription — concurrence, à traiter dans la story qui réécrit ce parcours. — **Epic 2 (2-1)** : le chemin de dépôt en ligne ignore le champ `code`, donc un rejet définitif et une panne transitoire s'affichent à l'identique (écart AD-10), à corriger dans la story i18n qui réécrit ces libellés.

### Écartés

- **CSP Swagger en dev sur hôte** — SKIP : limite strictement locale, hors topologie conteneurisée livrée.
- **Redirection HTTP→HTTPS sur port non standard** — ACCEPTÉ : la production utilise 443.
- **Doublon XFF** — la décision de revue 1.4/1.5 et le report DEF2 de la 1.3 décrivent le **même** défaut ; fusionnés pour ne pas être traités deux fois.

---

## 5. Sept décisions qui n'appartiennent pas au code

Aucune ne se tranche par lecture du dépôt. Trois portent une conséquence de sécurité.

| # | Décision | Enjeu |
| --- | --- | --- |
| D1 | **Oracle d'énumération d'e-mails** sur `POST /api/v1/escrow` | `"No seller registered with that email"` (400) se distingue d'une création réussie : le fichier des utilisateurs est énumérable. **Même classe de défaut que tout l'Epic 1**, sur une autre surface. |
| D2 | **Comptes ADMIN auto-attribués avant la Story 1.1** | Ni révoqués ni audités. Le correctif ferme la porte ; il ne rattrape pas ceux qui sont déjà entrés. Migration de données. |
| D3 | **Inondation de la table d'audit par les détections antivirus** | Table append-only à rétention ≥ 5 ans, sans borne. Dédup sha256, limiteur de dépôt, ou consommation du nonce en cas de rejet. |
| D4 | **Fermer l'onglet sans se déconnecter** | Mode de défaillance **dominant** de l'appareil partagé, resté entier après la Story 1.9. Expiration d'inactivité, session non persistante, ou risque assumé. |
| D5 | **Politique de mot de passe purement combinatoire** | `Password123!` est conforme. Dictionnaire, HIBP k-anonymity, zxcvbn — ou aucun, assumé. À trancher avec la Story 2-6. |
| D6 | **Ingestion antivirus synchrone dans le verrou pessimiste** | 20 fichiers × 30 s ≈ 10 min de verrou de ligne. L'ingestion asynchrone est une décision d'architecture, pas un correctif de revue. |
| D7 | **Identifiants denses et monotones** vs détection | Identifiants opaques (migration touchant URL, DTO, file offline, liens persistés) ou limitation de débit + détection. Deux réponses au même problème. |

**Rappel** : le trou normatif « aucun AD ne dit quelle garde protège un wallet » reste ouvert au niveau du spine, et la dette de preuve wallet de l'AC d'epic 1 reste à la charge de l'Epic 4.

---

## 6. Recommandation d'ordonnancement

1. **Bundle DOC** — sans risque, referme la dette de la story qui vient d'être livrée.
2. **Bundle QUALITÉ-CI** — **avant** l'Epic 2, pas après : la Story 2-2 livre une bibliothèque de composants, et il n'existe aujourd'hui aucun test de composant ni configuration de lint pour l'accueillir. Poser la barrière avant d'écrire le code qu'elle doit tenir.
3. **D1 (oracle e-mail)** — décision à prendre pendant que le contexte anti-énumération est frais.
4. **Bundle SESSION-PARTAGÉE** — en tête d'Epic 2, sur la surface que l'epic réécrit de toute façon.
5. Le reste suit ses stories cibles.
