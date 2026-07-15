# Deferred Work

Ledger of real issues surfaced during review but deliberately not addressed in the
story that found them. Append-only.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-fondations-de-stockage-des-preuves.md`
  summary: La table `evidence_files` n'a aucune contrainte d'intégrité au-delà des NOT NULL et des FK — pas de CHECK sur les valeurs d'enum, pas de cohérence d'attribution, pas de cohérence du retrait, pas d'unicité de `storage_key`.
  evidence: Le schéma livré est conforme à l'ERD figé de l'architecture, qui ne prescrit aucune de ces contraintes — d'où le report plutôt qu'un correctif. Mais les invariants sont réels et documentés en javadoc sans être tenus : (1) une écriture hors application peut stocker `'CARRIER'` → `@Enumerated(STRING)` lève `IllegalArgumentException` à *chaque* relecture, empoisonnant les listes de la Story 1.3 ; (2) rien n'empêche une ligne `CARRIER_PARTNER` sans `partner_company_id`, ni une ligne `BUYER` qui en porte un → preuve inattribuable, précisément dans la table qui fonde l'arbitrage ; (3) rien n'empêche `status = WITHDRAWN` avec `withdrawn_at`/`withdrawn_by_user_id` nuls → retrait sans trace ; (4) `storage_key` n'est pas UNIQUE alors que la relation métadonnée↔objet est voulue 1:1. Décision de niveau architecture (ERD), à trancher avant qu'Epic 2 et 3 n'écrivent ces lignes.

- source_spec: `_bmad-output/implementation-artifacts/spec-1-1-fondations-de-stockage-des-preuves.md`
  summary: Aucun test automatisé ne vérifie que l'entité `EvidenceFile` correspond bien à la table créée par Flyway.
  evidence: `ddl-auto: none` : Hibernate ne valide pas le mapping au démarrage, et le seul test de la story contourne Spring pour ne prouver que le round-trip de stockage. La correspondance entité↔schéma a été vérifiée manuellement (`\d evidence_files` contre l'entité : les 14 colonnes concordent), mais rien ne la défend contre une régression — un `@Column(name)` erroné ou une migration divergente ne se révélerait qu'à l'exécution, dans la Story 1.2. Un `@DataJpaTest` sur conteneur Postgres (save/find) refermerait le trou ; il arrivera naturellement avec le chemin d'écriture de la Story 1.2.
