-- Story 2.4 — inscription vérifiée par OTP, consentement horodaté, rattachement entreprise.
--
-- Une seule migration pour la story (convention du projet). Elle porte quatre choses :
-- l'état de vérification du compte, le code de vérification, le consentement légal, et
-- l'outbox de notification qui remplace le transport e-mail absent (voir T0/blocage 1).

-- ---------------------------------------------------------------------------
-- 1. Compte non vérifié
-- ---------------------------------------------------------------------------
-- DEFAULT FALSE serait un piège pour les comptes DÉJÀ créés : ils deviendraient tous non
-- vérifiés d'un coup, et se retrouveraient bloqués à la connexion par un OTP qu'aucun
-- d'eux ne peut recevoir. Les lignes existantes sont donc passées à TRUE explicitement,
-- et seul le défaut applicable aux NOUVELLES lignes est FALSE.
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE users SET email_verified = TRUE;

-- Gestionnaire de l'entreprise (FR-P9), forme MINIMALE et assumée comme telle.
--
-- Le lien utilisateur↔entreprise existe déjà (`users.company_id`, V1). Ajouter ici une
-- table d'appartenance parallèle créerait deux sources de vérité pour la même relation,
-- qui divergeraient au premier oubli. Les rôles INTERNES à l'entreprise et le
-- multi-utilisateur appartiennent à la Story 2.5 : c'est elle qui introduira une table
-- d'appartenance si son modèle l'exige, et qui migrera cette colonne.
--
-- À NE PAS confondre avec `users.role` : celui-ci porte les rôles PLATEFORME
-- {BUYER, SELLER, ADMIN}, dont AD-21 réserve le routage des trois espaces. Gestionnaire
-- est un axe différent — y mélanger les deux casserait le cloisonnement.
ALTER TABLE users ADD COLUMN company_manager BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------
-- 2. Code de vérification e-mail
-- ---------------------------------------------------------------------------
-- UNE ligne par utilisateur (`user_id` UNIQUE) : un renvoi REMPLACE le code courant au
-- lieu d'en empiler un second. Plusieurs codes valides simultanément multiplieraient
-- mécaniquement les chances d'un attaquant à chaque renvoi, et le plafond de tentatives
-- porterait sur un code au lieu de porter sur le compte.
--
-- Le code n'est JAMAIS stocké en clair : c'est une credential, au même titre qu'un mot de
-- passe. Une fuite de sauvegarde donnerait sinon un accès direct à tout compte en attente
-- de vérification. On stocke un hash bcrypt produit par le `PasswordEncoder` déjà en
-- service — réutiliser l'encodeur existant plutôt qu'inventer une comparaison maison, dont
-- la lenteur est ici un avantage : elle freine le forçage autant que le plafond.
CREATE TABLE email_verification_codes (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT      NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    code_hash         VARCHAR(255) NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,

    -- Tentatives de VÉRIFICATION du code courant. Persisté et non gardé en mémoire : le
    -- code, lui, vit en base et survit à un redémarrage. Un compteur en mémoire process
    -- offrirait donc un plafond qu'il suffit d'attendre — ou de faire redémarrer — pour
    -- réarmer, tout en donnant l'apparence d'une protection.
    attempts          INTEGER     NOT NULL DEFAULT 0,

    -- Quota d'ENVOIS, distinct des tentatives. Il ferme le canal ouvert par
    -- l'anti-énumération : l'inscription répondant la même chose que l'adresse existe ou
    -- non, n'importe qui peut déclencher un envoi vers l'adresse d'un TIERS. Le quota est
    -- donc porté par le compte destinataire, pas seulement par l'origine de la requête.
    send_count        INTEGER     NOT NULL DEFAULT 0,
    send_window_start TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_sent_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- 3. Consentement légal horodaté (FR-P27)
-- ---------------------------------------------------------------------------
-- Historique et non état : plusieurs lignes par utilisateur, une par version de document
-- acceptée. Écraser le consentement précédent détruirait la preuve de ce qui a été accepté
-- et quand — or c'est précisément ce que cette table existe pour produire.
--
-- `consented_at` est rempli par le SERVEUR (AD-11 : l'heure serveur est la source de
-- vérité). Une date venue du client serait une date choisie par le signataire.
CREATE TABLE legal_consents (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    document_version VARCHAR(50) NOT NULL,
    consented_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_legal_consents_user ON legal_consents (user_id);

-- ---------------------------------------------------------------------------
-- 4. Outbox de notification (AD-22)
-- ---------------------------------------------------------------------------
-- Décision T0 de cette story : le transport e-mail n'existe pas et appartient au spike
-- 8.1 (choix du fournisseur, délivrabilité par corridor ZLECAf). L'envoi est donc PERSISTÉ
-- ici, exactement comme le backlog le prévoit ailleurs (« un événement de notification est
-- persisté […] les canaux de livraison arrivent en Epic 8 »).
--
-- Conséquence à ne pas maquiller : tant qu'un adaptateur de production n'est pas branché,
-- une ligne ici N'EST PAS un e-mail reçu. `delivered_at` reste donc NULL, et c'est la
-- vérité de l'état du système, pas un défaut.
--
-- Le corps ne contient JAMAIS le code : l'outbox est lisible par tout ce qui lit la base,
-- alors que le code ne doit exister en clair que le temps d'un appel au port. Le rendu du
-- message appartient à l'adaptateur, qui reçoit le code hors de la ligne.
CREATE TABLE notification_outbox (
    id           BIGSERIAL PRIMARY KEY,
    channel      VARCHAR(20)  NOT NULL,
    recipient    VARCHAR(150) NOT NULL,
    template_key VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ
);

CREATE INDEX idx_notification_outbox_pending ON notification_outbox (created_at)
    WHERE delivered_at IS NULL;
