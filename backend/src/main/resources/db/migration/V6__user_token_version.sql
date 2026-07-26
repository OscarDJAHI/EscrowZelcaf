-- Story 1.6 (NFR-P5) — révocation JWT effective côté serveur (Approche A).
-- Chaque JWT porte un claim `tv` = users.token_version au moment de l'émission ;
-- le filtre le compare à la valeur courante. Incrémenter token_version invalide
-- d'un coup TOUTES les sessions du compte (logout, changement de mot de passe,
-- et plus tard changement de rôle / désactivation).
ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
