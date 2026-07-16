---
title: 'Verrouiller dépôt & retrait aux états terminaux'
type: 'feature'
created: '2026-07-16'
status: 'done'
baseline_revision: 'ef1522822121e1329517f907769204ff53147d6a'
final_revision: '6ab4fb7f1fa61ce02e0c83447f91d323580c1b53'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** L'invariant « aucune mutation de preuve après résolution » (verrou dès `RELEASED`/`REFUNDED`, FR-8 / AD-2) n'est pour l'instant qu'un effet de bord d'un `EnumSet` privé (`UPLOAD_WINDOW`) enfoui dans `EvidenceService.deposit`. Il n'est ni exprimé comme règle d'état de premier ordre, ni réutilisable par le retrait (Story 2.3), ni couvert par un test unitaire pur prouvant explicitement le gel terminal.

**Approach:** Promouvoir la fenêtre de mutation en un prédicat d'état centralisé et unique — `EscrowState.allowsEvidenceMutation()` (`{FUNDS_LOCKED, SHIPPED, DISPUTED}`) — source de vérité unique portée par le domaine, à côté de `isTerminal()`. `EvidenceService` délègue sa garde à ce prédicat (comportement inchangé, toujours `409`), et la Story 2.3 réutilisera exactement le même prédicat pour le retrait. Ajouter la preuve unitaire du gel terminal.

## Boundaries & Constraints

**Always:**
- Le verrou est **fondé sur l'état courant** (fonction pure de `EscrowState`), jamais dérivé du dernier événement.
- Une seule source de vérité : `EscrowState.allowsEvidenceMutation()` renvoie vrai ssi l'état ∈ `{FUNDS_LOCKED, SHIPPED, DISPUTED}`.
- La garde de dépôt (`EvidenceService`) délègue à ce prédicat et lève `ConflictException` (`409`) sur violation ; message de dépôt inchangé.
- Dépôt en `DISPUTED` reste **accepté** pour toutes les parties (acheteur, vendeur, arbitre) — FR-7.
- La lecture verrouillée pessimiste (`findByIdForUpdate`) précédant la garde reste inchangée (anti-course avec un `RELEASE`/`REFUND` concurrent).

**Block If:**
- La centralisation du prédicat imposerait une dépendance circulaire ou un changement de contrat au-delà de cette story.

**Never:**
- N'implémente PAS le retrait ni son endpoint — c'est la Story 2.3 ; cette story ne fait que fournir et prouver la garde que 2.3 consommera.
- Aucune vérification fondée sur l'événement (`RESOLVE_*`/`DELIVERY_CONFIRMED`) ; le gel doit tenir quel que soit le chemin vers l'état terminal.
- Ne modifie pas l'ensemble des états autorisés, n'affaiblit pas le verrou de dépôt, ne retire pas le verrou pessimiste.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Dépôt en litige | `POST /evidence`, état `DISPUTED` | Dépôt accepté (`201`) | Aucune erreur |
| Dépôt libéré | `POST /evidence`, état `RELEASED` | Refus, aucune écriture | `409 ConflictException` |
| Dépôt remboursé | `POST /evidence`, état `REFUNDED` | Refus, aucune écriture | `409 ConflictException` |
| Prédicat — états ouverts | `allowsEvidenceMutation()` sur `FUNDS_LOCKED`/`SHIPPED`/`DISPUTED` | `true` | — |
| Prédicat — états fermés | `allowsEvidenceMutation()` sur `INITIATED`/`RELEASED`/`REFUNDED` | `false` | — |

</intent-contract>

## Code Map

- `backend/src/main/java/com/zlecaf/escrow/domain/EscrowState.java` -- enum du cycle de vie ; porte déjà `isTerminal()` ; recevra le prédicat `allowsEvidenceMutation()` (nouvelle source de vérité).
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` -- `UPLOAD_WINDOW` (l.54-55) + `requireUploadWindow` (l.219-224, appelée l.102) à refactorer pour déléguer au prédicat ; retirer l'`EnumSet` privé et les imports devenus inutiles.
- `backend/src/test/java/com/zlecaf/escrow/service/EscrowStateMachineTest.java` -- test unitaire pur (JUnit5 + AssertJ, aucun Spring) ; teste déjà `isTerminal()` (l.98-107) ; recevra la preuve du prédicat.
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` -- couvre déjà le refus de dépôt en `RELEASED` (l.235-244) ; à étendre à `REFUNDED`.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/java/com/zlecaf/escrow/domain/EscrowState.java` -- ajouter `public boolean allowsEvidenceMutation()` renvoyant `this == FUNDS_LOCKED || this == SHIPPED || this == DISPUTED`, avec javadoc précisant que c'est la source de vérité unique du verrou dépôt/retrait (FR-8, AD-2).
- [x] `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` -- remplacer le corps de `requireUploadWindow` par `if (!state.allowsEvidenceMutation())` ; supprimer la constante `UPLOAD_WINDOW` et les imports `EnumSet`/`Set` s'ils deviennent inutiles ; conserver le message et le `ConflictException` (`409`) existants.
- [x] `backend/src/test/java/com/zlecaf/escrow/service/EscrowStateMachineTest.java` -- ajouter des tests purs : `allowsEvidenceMutation()` vrai pour `FUNDS_LOCKED`/`SHIPPED`/`DISPUTED`, faux pour `INITIATED`/`RELEASED`/`REFUNDED` ; un `@ParameterizedTest @EnumSource(names={"RELEASED","REFUNDED"})` asserte `terminal.isTerminal()` ET `!terminal.allowsEvidenceMutation()` (gel terminal).
- [x] `backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` -- étendre la couverture du refus de dépôt hors fenêtre à l'état `REFUNDED` (miroir du cas `RELEASED` existant), en vérifiant `ConflictException` et l'absence d'écriture.

**Acceptance Criteria:**
- Given une transaction devenue terminale par libération normale (`DELIVERY_CONFIRMED` → `RELEASED`), when une partie tente un dépôt, then refus `409` — le verrou ne dépend que de l'état, pas du chemin.
- Given une transaction devenue terminale par arbitrage (`RESOLVE_RELEASE`/`RESOLVE_REFUND` → `RELEASED`/`REFUNDED`), when une partie tente un dépôt, then refus `409` — verrou identique quel que soit l'événement d'origine.
- Given le prédicat centralisé `EscrowState.allowsEvidenceMutation()`, when la Story 2.3 ajoutera le retrait, then le retrait consommera ce même prédicat (contrat de réutilisation : verrou identique dépôt/retrait) sans redéfinir l'ensemble d'états.
- Given une transaction `DISPUTED`, when une partie prenante dépose une contre-preuve, then acceptation.

## Review Triage Log

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 3: (high 0, medium 0, low 3)
- defer: 0
- reject: 9: (high 0, medium 0, low 9)
- addressed_findings:
  - `[low]` `[patch]` Couverture service-layer du refus en état fermé non terminal : `INITIATED` ajouté à l'`@EnumSource` de `closedStateWindowConflicts` (EvidenceServiceTest), paramètre renommé `closedState`.
  - `[low]` `[patch]` Commentaire enum `DISPUTED` (« process frozen ») clarifié pour ne plus contredire `allowsEvidenceMutation()` (mutation de preuve reste ouverte en litige).
  - `[low]` `[patch]` Test d'invariant exhaustif ajouté (`@EnumSource(EscrowState.class)`) : aucun état terminal n'autorise la mutation — fail-closed contre l'ajout futur d'un état.
- rejected (résumé) : nom/javadoc du prédicat couvrant le retrait (délibéré — contrat de réutilisation 2.1/2.3 par epic-context L21) ; redondance de tests (test terminal paramétré exigé par le spec) ; placement de la policy sur l'enum lifecycle (décision justifiée en Design Notes) ; drift de nommage `requireUploadWindow` (garde dépôt privée ; 2.3 ajoutera sa propre garde) ; set terminal codé en dur dans le test (convention existante) ; non-épinglage du statut 409/message (`ConflictException` ⇒ 409 via handler) ; dépôt `DISPUTED` end-to-end (déjà couvert par `EscrowDisputeServiceTest` de 2.1) ; NPE si `state` null (colonne `@Column(nullable=false)` + DB `NOT NULL` — inatteignable).

## Design Notes

Le gel « après arbitrage comme après libération normale » (AC de la story) est garanti **par construction** : la garde n'inspecte que `EscrowState`, donc `RELEASED` atteint via `DELIVERY_CONFIRMED` et `RELEASED`/`REFUNDED` atteints via `RESOLVE_*` sont indiscernables pour elle. Inutile de tester chaque chemin — tester le prédicat sur les états terminaux suffit et couvre tous les chemins.

Le prédicat vit sur l'enum `EscrowState` (et non sur `EscrowStateMachine`) car le verrou de mutation de preuve est une propriété d'état, pas une transition ; il reste ainsi testable sans Spring/Testcontainers, dans le même fichier que le test `isTerminal()` déjà présent. Direction de dépendance à préserver en 2.3 : `EscrowService → EvidenceService`, jamais l'inverse.

Golden (prédicat) :
```java
/** Source de vérité unique : dépôt ET retrait de preuve permis ssi vrai (FR-8, AD-2). */
public boolean allowsEvidenceMutation() {
    return this == FUNDS_LOCKED || this == SHIPPED || this == DISPUTED;
}
```

## Verification

**Commands:**
- `cd backend && mvn -q -Dtest=EscrowStateMachineTest test` -- attendu : vert, incluant les nouvelles assertions du prédicat.
- `cd backend && mvn -q -Dtest=EvidenceServiceTest test` -- attendu : vert, incluant le refus `REFUNDED`.
- `cd backend && grep -n "UPLOAD_WINDOW" src/main/java/com/zlecaf/escrow/service/EvidenceService.java` -- attendu : aucune occurrence (constante supprimée).

## Auto Run Result

Status: done

**Changement implémenté :** le verrou d'état dépôt/retrait de preuve est promu d'un `EnumSet` privé (`UPLOAD_WINDOW`) enfoui dans `EvidenceService` en un prédicat pur et centralisé `EscrowState.allowsEvidenceMutation()` (`{FUNDS_LOCKED, SHIPPED, DISPUTED}`), source de vérité unique. La garde de dépôt y délègue (comportement `409 ConflictException` inchangé) ; le retrait (Story 2.3) réutilisera ce même prédicat. Le gel terminal est prouvé par des tests unitaires purs et un test service-layer.

**Fichiers modifiés :**
- `backend/src/main/java/com/zlecaf/escrow/domain/EscrowState.java` — ajout du prédicat `allowsEvidenceMutation()` ; commentaire `DISPUTED` clarifié (mutation ouverte en litige).
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` — garde déléguée au prédicat ; suppression de `UPLOAD_WINDOW` et des imports `EnumSet`/`Set`.
- `backend/src/test/java/com/zlecaf/escrow/service/EscrowStateMachineTest.java` — tests du prédicat (fenêtre ouverte/fermée), gel terminal paramétré, invariant exhaustif sur tous les états.
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` — refus de dépôt hors fenêtre paramétré sur `INITIATED`/`RELEASED`/`REFUNDED`.

**Revue :** 0 intent_gap, 0 bad_spec, 3 patches appliqués (tous low : couverture `INITIATED` service-layer, commentaire `DISPUTED`, test d'invariant exhaustif), 0 report, 9 findings rejetés (voir Review Triage Log). Aucune boucle de réparation.

**Vérification :** `mvn -Dtest=EscrowStateMachineTest,EvidenceServiceTest test` → `Tests run: 48, Failures: 0, Errors: 0` — BUILD SUCCESS (EscrowStateMachineTest 23, EvidenceServiceTest 25, Testcontainers Postgres actif). `grep UPLOAD_WINDOW` → aucune occurrence.

**Risques résiduels :** le contrat de réutilisation du prédicat par la Story 2.3 (retrait) est documenté mais non exécutable tant que le retrait n'existe pas ; à honorer lors de l'implémentation de 2.3 (garde de retrait déléguant au même prédicat, avec son propre message/`ConflictException`).
