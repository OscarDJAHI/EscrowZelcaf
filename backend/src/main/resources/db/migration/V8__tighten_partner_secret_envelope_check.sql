-- ============================================================================
-- Resserrage du CHECK de plancher des secrets partenaires (Story 1.7, revue).
--
-- V7 a reformulé ck_partner_hmac_keys_secret_len en « LIKE 'esc:%' OR
-- octet_length(secret_key) >= 32 » pour tolérer l'enveloppe de chiffrement.
-- Ce motif est PLUS LARGE que ce que l'application reconnaît réellement comme
-- une enveloppe (SecretCipher.TEXT_ENVELOPE_PATTERN exige la forme complète
-- esc:<version>:<idDeClé>:<base64>), et l'écart ouvre un trou dans la garde
-- que V7 avait précisément pour but de conserver :
--
--   INSERT ... secret_key = 'esc:secret-du-transitaire'   -- 25 octets
--
-- passe le CHECK par la branche « LIKE », mais l'application ne voit PAS une
-- enveloppe : elle lit un secret legacy en clair de 25 octets. Conséquences,
-- toutes deux réelles sur le chemin de provisioning en SQL direct (le seul qui
-- existe jusqu'à l'outillage d'admin de l'Epic 7) :
--   1. tant que l'instance tourne, ce secret faible signe et vérifie pour de bon ;
--   2. au redémarrage suivant, le balayage de scellement refuse un clair sous le
--      plancher et le démarrage échoue — la base aura validé une donnée que
--      l'application juge fatale.
--
-- Le CHECK épouse donc désormais la MÊME forme que l'application. Toute
-- évolution du format d'enveloppe (SecretCipher.FORMAT_VERSION) doit faire
-- évoluer ce motif dans la même livraison, sinon les nouvelles enveloppes
-- seraient rejetées à l'écriture. Le plancher de 32 octets, lui, reste la
-- valeur de PartnerHmacKey.MIN_SECRET_BYTES.
--
-- Migration NON destructive : toute enveloppe produite par SecretCipher et tout
-- clair >= 32 octets satisfont déjà le nouveau motif.
-- ============================================================================

ALTER TABLE partner_hmac_keys
    DROP CONSTRAINT ck_partner_hmac_keys_secret_len;

ALTER TABLE partner_hmac_keys
    ADD CONSTRAINT ck_partner_hmac_keys_secret_len
        CHECK (secret_key ~ '^esc:[0-9]{1,3}:[A-Za-z0-9_-]{1,64}:[A-Za-z0-9+/]+={0,2}$'
               OR octet_length(secret_key) >= 32);

COMMENT ON COLUMN partner_hmac_keys.secret_key IS
    'Secret HMAC entrant, CHIFFRÉ au repos (enveloppe esc:<version>:<idDeClé>:<base64>, AES-256-GCM). '
    'La base ne peut plus lire le clair : la clé vit dans ESCROW_CRYPTO_KEYS, hors du dépôt et hors '
    'de la base. Le CHECK accepte soit une enveloppe BIEN FORMÉE, soit un clair legacy >= 32 octets — '
    'jamais un clair court déguisé en enveloppe (Story 1.7, AD-29).';
