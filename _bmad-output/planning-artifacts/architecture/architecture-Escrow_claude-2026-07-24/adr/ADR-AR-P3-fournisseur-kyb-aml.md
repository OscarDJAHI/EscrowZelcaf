# ADR AR-P3 — Fournisseur KYB/AML

- **Statut : PROPOSÉE** (décision argumentée, à ratifier par Oscard ; contractualisation non engagée)
- **Date : 2026-07-25** · **Story : 3.1** · **Spine : port `KybScreeningProvider`, FR-P8, FR-P30, NFR-P23**
- **Base factuelle :** `planning-artifacts/spikes/annexe-AR-P3-recherche-kyb-aml-2026-07-25.md` (6 candidats comparés, sources datées)

## Décision

**Duo de fournisseurs derrière le port unique `KybScreeningProvider`, scindé en deux adaptateurs :**

1. **Vérification KYB registres : Smile ID** (couvre CAC Nigéria + fiscal, BRS Kenya, CIPC Afrique du Sud, avec dirigeants/UBO ; API async qui tolère l'indisponibilité chronique des registres). **Youverify reste challenger** à confronter lors du premier POC d'intégration (Story 3.2) — ses accréditations DPA Nigéria/Kenya/Afrique du Sud sont un atout NFR-P23, ses webhooks et tarifs sont à clarifier.
2. **Screening sanctions/PEP + monitoring continu : ComplyAdvantage Mesh** — seul candidat avec re-screening automatique 24 h, cases et webhooks « at least once » : c'est FR-P8 « en continu » servi nativement ; notre scheduler de re-screening (spine) devient un filet de réconciliation, pas le mécanisme primaire.
3. **Ghana : revue documentaire manuelle assumée** via la file opérateur (Story 3.3) sur pièces du portail ORC — aucun fournisseur n'automatise ce registre de façon fiable. Le formulaire 3.2 + upload de pièces couvre ce chemin sans story supplémentaire.
4. **Repli contractuel (exigé par l'AC) : OpenSanctions + yente auto-hébergé** + revue 100 % manuelle — activable si la contractualisation traîne ; conforme NFR-P23 par construction ; le port rend la substitution invisible pour les stories 3.2-3.5.

## Contrat d'intégration

- Soumission KYB (3.2) → appel registre (sync, async si indisponible) + screening à l'appel → **tout hit ou incertitude route vers la file opérateur 3.3 — le fournisseur ne décide JAMAIS** (FR-P30).
- Résultats de screening stockés horodatés, chiffrés au repos (AD-29), rétention WORM ≥ 5 ans (3.5).
- Monitoring continu : webhook Mesh → alerte dans la file opérateur, tracée (FR-P8) ; scheduler interne conservé (réconciliation + filet anti-oubli).
- Impact modèle de données 3.2-3.5 : le dossier KYB porte `provider_ref`, `screening_result` (JSON brut fournisseur), `screened_at`, `decision` (opérateur uniquement) — aucune dépendance dure à un service non contractualisé : l'interface est la nôtre, le mode manuel est un adaptateur comme les autres.

## Points à verrouiller avant contractualisation (non bloquants pour 3.2-3.5)

Localisation des données Smile ID (non publiée — exigence contractuelle NFR-P23) ; webhooks Youverify ; tarifs KYB réels ; profondeur UBO du CAC sur structures complexes.

## Conséquences backlog

- 3.2/3.3/3.4 s'écrivent contre le port (aucun changement de périmètre).
- 3.5 : le monitoring continu Mesh alimente le re-screening AML — le job scheduler du spine reste, en réconciliation.
- Aucun code livré par cette story (conforme AC3).
