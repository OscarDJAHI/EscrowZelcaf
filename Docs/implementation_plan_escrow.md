# Plan d'Implémentation Technique du POC (Feuille de Route)

Ce document décrit le déroulement chronologique pour la mise en place concrète du Proof of Concept (POC) de la plateforme d'Escrow. Le plan est découpé en 5 phases itératives sur une durée totale estimée à 9 semaines pour un développeur fullstack travaillant de concert avec l'architecte Cloud.

## Phase 1 : Initialisation de l'Environnement & Base de Données (Semaines 1-2)
* **Livrables :** Dépôts Git structurés (Mono-repo ou multi-repos pour backend/frontend/infra), Schéma SQL initialisé, Conteneurs Docker locaux opérationnels.
* **Tâches clés :**
    1. Configuration de l'environnement de développement local avec Docker Compose (PostgreSQL, RabbitMQ).
    2. Écriture des scripts de migration Liquibase ou Flyway pour le schéma relationnel (`users`, `companies`, `escrow_transactions`).
    3. Mise en place de la sécurité de base (Spring Security) avec génération et validation de jetons JWT.

## Phase 2 : Moteur d'États & Algorithme de Séquestre Core (Semaines 3-4)
* **Livrables :** API Backend Spring Boot fonctionnelle avec couverture de tests unitaires sur les transitions d'état.
* **Tâches clés :**
    1. Implémentation du switch transactionnel sécurisé (`determineNextState`) gérant les règles métier strictes.
    2. Mise en place du mécanisme de verrouillage des lignes en base de données pour empêcher les conditions de concurrence (Race Conditions).
    3. Écriture de la couche de journalisation immuable dans la table `audit_logs` pour chaque transition réussie ou échouée.

## Phase 3 : Intégration n8n Asynchrone & Webhooks (Semaines 5-6)
* **Livrables :** Instance n8n opérationnelle, flux de traitement de paiement et de livraison simulés via des workflows visuels, mécanisme de signature HMAC fonctionnel.
* **Tâches clés :**
    1. Déploiement local ou cloud de n8n interconnecté avec notre Message Broker (RabbitMQ/Redis).
    2. Configuration du composant Spring Boot `@Async` chargé de publier les événements dans la file d'attente.
    3. Création de workflows n8n prototypes simulant :
        * La réception d'un paiement Mobile Money simulé changeant l'état à `FUNDS_LOCKED`.
        * La notification automatique vers l'API d'un transporteur tiers lorsque l'état passe à `SHIPPED`.
    4. Implémentation de la validation de sécurité des signatures webhooks (`X-Escrow-Signature`).

## Phase 4 : Frontend PWA Léger & Mode Dégradé (Semaines 7-8)
* **Livrables :** Application Vue 3 accessible sur mobile, installable en tant que PWA, capable d'afficher les transactions et d'empiler des actions en mode hors-ligne.
* **Tâches clés :**
    1. Initialisation du projet Vue 3 avec Vite et configuration du plugin PWA (`vite-plugin-pwa`) avec stratégie de cache `NetworkFirst`.
    2. Développement du dashboard acheteur/vendeur affichant la frise chronologique de la machine à états (Stepper visuel).
    3. Implémentation du store Pinia gérant la file d'attente locale (`offlineQueue`) interceptant les actions lorsque `navigator.onLine` est faux.
    4. Écriture du script de synchronisation automatique en arrière-plan (Background Sync) rétablissant les requêtes vers le serveur dès le retour du réseau.

## Phase 5 : Tests de Bout en Bout, Sécurité & Déploiement Cloud (Semaine 9)
* **Livrables :** POC hébergé dans un environnement Cloud de test (ex: AWS ou serveur dédié sécurisé), accessible via une URL publique protégée par Cloudflare.
* **Tâches clés :**
    1. Réalisation de scénarios de tests de bout en bout (E2E) : Simulation complète du parcours utilisateur (Achat ➡️ Séquestre ➡️ Expédition ➡️ Litige ➡️ Résolution).
    2. Validation de la résilience aux pannes réseaux : Coupure volontaire d'une API de paiement simulée pour valider les mécanismes de retry de n8n.
    3. Présentation finale du POC fonctionnel à l'équipe technique et aux parties prenantes du projet.