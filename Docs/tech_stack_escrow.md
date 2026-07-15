# Spécification de la Tech Stack - Architecture Cloud & Applicative

Ce document formalise les choix technologiques retenus pour le POC et la future mise en production de la plateforme d'Escrow B2B. L'architecture est guidée par deux principes fondamentaux : la robustesse financière (Backend) et l'adaptabilité aux contraintes du réseau africain (Frontend & Infrastructure).

## 1. Frontend : Performance & Accessibilité Hors-Ligne
* **Framework :** **Vue 3** (Composition API). Choisi pour sa légèreté, ses performances de rendu et sa courbe d'apprentissage rapide.
* **Gestion d'État :** **Pinia**. Utilisation stratégique pour maintenir un store local persistant facilitant la mise en file d'attente des requêtes hors-ligne (Offline Queue).
* **Build Tool & PWA :** **Vite** avec `@vitejs/plugin-vue` et `vite-plugin-pwa`. Permet la génération d'un Service Worker robuste assurant le caching agressif de l'interface graphique (HTML, CSS, JS) pour un chargement instantané en 3G.
* **Design / UI :** **TailwindCSS**. Permet de concevoir des interfaces responsives, épurées et légères sans surcharger le bundle final.

## 2. Backend Core : Robustesse FinTech
* **Langage & Framework :** **Java 17/21** avec **Spring Boot 3.x**. C'est le standard industriel pour les applications financières exigeant un typage fort, une maintenabilité à long terme et un écosystème de sécurité mature (Spring Security).
* **Persistance & Données :** **Spring Data JPA / Hibernate**. Fournit une abstraction propre pour les interactions avec la base de données tout en garantissant une gestion rigoureuse des transactions complexes (`@Transactional`).
* **Sécurité :** **Spring Security + OAuth2 / JWT**. Authentification sans état (stateless) pour sécuriser les communications entre la PWA et les API du backend.

## 3. Middleware d'Intégration & Asynchronisme
* **Orchestrateur de Workflows :** **n8n (Auto-hébergé / Self-hosted)**. Déployé au sein de notre propre infrastructure Cloud pour garantir la souveraineté complète des données transactionnelles. Il sert d'Enterprise Service Bus (ESB) moderne pour mapper, transformer et router les requêtes vers les API hétérogènes des partenaires (M-Pesa, MTN, Orange Money, DHL, Bolloré Logistics).
* **Gestion des Messages (Message Broker) :** **RabbitMQ** ou **Redis Pub/Sub** (selon la charge). Permet de découpler le moteur d'Escrow de l'orchestrateur n8n. Les événements de changement d'état sont empilés dans une file, garantissant qu'aucune notification n'est perdue si un service tiers subit une coupure.

## 4. Base de Données & Stockage
* **Base de Données Principale :** **PostgreSQL**. Choix incontournable pour les applications transactionnelles exigeant une conformité ACID stricte, une gestion fine des verrous (Locks) et une excellente extensibilité.
* **Stockage Documentaire (Preuves de litiges) :** Stockage d'objets compatible **S3** (AWS S3 ou MinIO en local). Utilisé pour stocker de manière sécurisée et isolée les documents justificatifs et factures.

## 5. Architecture Cloud & Infrastructure Cible
* **Edge & Sécurité Périmétrique :** **Cloudflare**. Fournit le WAF (Web Application Firewall) contre les attaques DDoS, la gestion des certificats SSL/TLS et le CDN pour distribuer la PWA au plus près des utilisateurs en Afrique via ses points de présence locaux (Johannesburg, Nairobi, Lagos, Casablanca, etc.).
* **Entrée unique (API Gateway) :** **Spring Cloud Gateway** ou **Kong**. Gère le routage unifié, le Rate-Limiting par clé d'API et la validation initiale des tokens JWT.
* **Conteneurisation & Orchestration :** **Docker** et **Kubernetes (K8s)**. L'ensemble des services (Spring Boot Core, instances n8n Workers, RabbitMQ) est conteneurisé. Kubernetes assure l'auto-scaling horizontal, la haute disponibilité et le self-healing des instances applicatives.
* **Base de données managée :** **AWS RDS PostgreSQL (Configuration Multi-AZ)**. Assure la réplication automatique en temps réel des données sur plusieurs zones de disponibilité pour parer à toute panne d'infrastructure majeure.