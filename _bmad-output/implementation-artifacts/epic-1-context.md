# Epic 1 Context: Sécurité & protection des données

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Durcir la plateforme contre les attaques et les fuites, afin que des professionnels puissent y confier de l'argent réel. C'est le lot P0 « stop-ship » du lancement commercial : tant qu'il n'est pas soldé, aucune mise en production n'est envisageable, quel que soit l'avancement fonctionnel. L'epic porte l'ensemble des exigences de sécurité transverses — secrets, anti-bruteforce, TLS et en-têtes, CORS et documentation d'API, mots de passe et révocation de session, chiffrement au repos, antivirus à l'ingestion, hygiène de session, anti-énumération — et prolonge le durcissement déjà entamé sur le rôle ADMIN. Il avance en **piste parallèle** : aucune de ses stories ne dépend du chemin critique fonctionnel, et plusieurs d'entre elles fixent des conventions que tous les epics suivants réutiliseront sans les réimplémenter.

## Stories

- Story 1.1 : Rôle ADMIN non auto-attribuable
- Story 1.2 : Externalisation des secrets
- Story 1.3 : Anti-bruteforce sur l'authentification
- Story 1.4 : TLS de bout en bout et en-têtes de sécurité
- Story 1.5 : CORS allowlist et Swagger fermé en production
- Story 1.6 : Politique de mots de passe et révocation JWT
- Story 1.7 : Chiffrement au repos
- Story 1.8 : Scan anti-malware à l'ingestion
- Story 1.9 : Hygiène de session sur appareil partagé
- Story 1.10 : Anti-énumération des ressources

## Requirements & Constraints

- **Preuve par test, jamais par déclaration.** Chaque durcissement doit être démontré par un test automatisé (intégration de préférence) : c'est le critère d'acceptation implicite de tout l'epic.
- **Démarrage fail-fast** : l'absence d'une variable critique (JWT, base, stockage objet, clés HMAC) fait échouer le boot en **nommant la variable manquante**. Aucun secret en dur ne subsiste dans le code, les migrations ou la composition de conteneurs ; les valeurs historiquement exposées sont révoquées/rotées, pas seulement retirées.
- **Anti-bruteforce** : au-delà d'un seuil de tentatives échouées depuis une même origine, réponse 429 avec backoff progressif et journalisation dans le journal d'audit. Un utilisateur légitime retrouve l'accès **sans intervention manuelle** après la fenêtre.
- **Transport** : redirection HTTPS et HSTS au reverse-proxy ; CSP et X-Frame-Options sur toutes les réponses HTML.
- **Surface d'API bornée en production uniquement** : origines CORS restreintes à une allowlist, documentation d'API et spec OpenAPI non servies (404/403). Les profils de développement et la CI doivent rester pleinement utilisables — le durcissement est conditionné au profil, jamais global.
- **Mots de passe et session** : règles explicitées au rejet ; la révocation doit être **effective côté serveur** — un jeton révoqué est refusé par le backend, pas seulement oublié par le client.
- **Chiffrement au repos** avec clés gérées hors du code, et **rotation documentée et testée** : la rotation fait partie du livrable, pas d'un plan futur.
- **Antivirus obligatoire à l'ingestion** de tout fichier, quelle que soit sa provenance (preuve, pièce KYB, pièce de ticket) : rejet motivé et audité en cas de détection, flux normal sans dégradation sensible du temps de réponse sinon. Cette story lève formellement le risque « pas d'antivirus » accepté en phase POC.
- **Hygiène de session** : la déconnexion vide la file offline et les stores locaux ; la file est **scopée par utilisateur** pour qu'un appareil partagé ne laisse rien fuir.
- **Anti-énumération** : les réponses à une ressource inexistante et à une ressource appartenant à un tiers sont **indistinguables**, au minimum sur transactions, preuves et wallets.
- Tout événement de sécurité significatif (blocage, rejet de fichier, révocation) est audité.

## Technical Decisions

- **Rôles plateforme = whitelist fermée** `{BUYER, SELLER, ADMIN, ARBITRATOR}`. Ni ADMIN ni ARBITRATOR ne sont attribuables à l'inscription ; ADMIN est semé par un bootstrap serveur idempotent, ARBITRATOR est octroyé/révoqué par un ADMIN via une action motivée et auditée. Le routage des trois espaces de l'interface se fonde exclusivement sur ce rôle.
- **Trois canaux d'authentification distincts et non interchangeables** : JWT pour les utilisateurs, HMAC + nonce pour les partenaires, signature pour les webhooks du prestataire de paiement.
- **Rate-limiting applicatif** implémenté comme filtre sur les routes d'authentification ; le reverse-proxy vient en complément, jamais en substitut.
- **Révocation de session** : refresh tokens persistés et révocables ; déconnexion et réinitialisation de mot de passe révoquent.
- **Secrets par variables d'environnement.** Le choix de l'outil de gestion de secrets et la rotation opérationnelle sont une décision d'architecture encore ouverte, traitée dans une story d'opérabilité distincte — ne pas la préempter ici.
- **Périmètre du chiffrement au repos** (fixé ; ne pas l'élargir ni le réduire arbitrairement) : binaires de preuves et justificatifs KYB côté stockage objet, derrière le port de stockage existant ; coordonnées bancaires/mobile money de retrait ; résultats de screening AML ; secrets TOTP et codes de récupération (ces derniers de plus hachés) ; secrets HMAC partenaires et webhooks. Règle permanente : aucune nouvelle catégorie de donnée sensible n'est persistée sans statuer explicitement son chiffrement.
- **Gardes centralisées, jamais recodées par endpoint** : appartenance (anti-IDOR), gating KYB/suspension, rôle interne d'entreprise. Elles s'exécutent **avant** toute opération. L'uniformisation 403/404 est une convention transverse du même rang : toute nouvelle ressource protégée s'y conforme d'office.
- **Audit** : writer unique, écriture obligatoire dans la même transaction que l'action auditée. Horodatage serveur = source de vérité.
- **WORM** : aucun DELETE applicatif sur preuves, justificatifs KYB, résultats de screening, messages et journaux d'audit — retrait logique uniquement, avec échéance de purge calculée.
- **Ingestion de fichiers** : le scan antivirus s'insère dans le pipeline de validation existant (type/taille, service en `attachment`), derrière le même port de stockage, pour bénéficier automatiquement aux briques d'upload créées plus tard.
- Conventions transverses applicables : enums et codes machine en anglais (les libellés français sont de l'affichage i18n) ; enveloppe d'erreur globale unique ; API sous `/api/v1`, endpoints d'administration sous `/api/v1/admin` ; migrations Flyway `V<n>__desc.sql`, une par story qui en a besoin.

## UX & Interaction Patterns

- **Session expirée** : un 403 nu est traité comme une expiration de session — retour à l'écran de connexion avec **conservation de la cible** ; le cache de lecture et la file offline survivent à la ré-authentification, à distinguer de la déconnexion explicite, qui, elle, purge tout.
- **Erreurs** : tout rejet affiche un motif **et** une action de reprise. Erreur réseau = toast avec relance et saisie conservée ; erreur serveur = carte d'erreur avec référence d'incident.
- **Formulaires** (politique de mot de passe) : validation à la volée, erreurs sous le champ, saisie conservée.
- **i18n** : toute chaîne visible passe par une clé EN/FR, y compris les messages de sécurité ; les codes machine renvoyés par l'API restent en anglais et pilotent le rendu.
- Annonces d'état via le canal `aria-live` centralisé ; plancher d'accessibilité WCAG 2.2 AA.

## Cross-Story Dependencies

- **État à date** : 1.1, 1.2 et 1.3 sont livrées ; 1.4 est en revue ; 1.5 à 1.10 sont au backlog.
- **Aucune dépendance amont** vers les epics fonctionnels : cet epic peut avancer en continu, en parallèle du chemin critique.
- **1.2 → 1.7** : l'externalisation des secrets conditionne la gestion des clés de chiffrement au repos. La rotation opérationnelle et le choix d'outil sont complétés par la story d'opérabilité dédiée aux environnements et profils.
- **1.4 et 1.5** dépendent de la configuration du reverse-proxy et des profils d'environnement ; 1.5 est complétée plus tard par la story d'opérabilité « stack durcie et hébergement ».
- **1.6 → 2.6** : la révocation de session est le socle sur lequel s'appuie la 2FA TOTP de l'epic Onboarding.
- **1.8** vaut pour **toutes** les briques d'upload, y compris celles créées ultérieurement (justificatifs KYB de l'epic Conformité, pièces de tickets du back-office) : la livrer tôt évite un rétrofit coûteux.
- **1.9** touche la couche offline (file IndexedDB) partagée avec l'epic Offline — à coordonner avec la story de fondation de cette file pour éviter deux implémentations concurrentes du scoping par utilisateur.
- **1.10** fixe une convention réutilisée dans une douzaine de stories réparties sur les autres epics ; toute ressource protégée créée ailleurs s'y aligne.
- **Point de vigilance** : la note d'epic annonce « porte NFR-P1..P10 », mais l'exigence d'**idempotence du rejeu offline** n'est couverte par aucune story de cet epic — elle relève de la fondation de l'epic Offline, étendue au périmètre financier par l'epic Wallet. Ne pas la traiter ici.
