-- ============================================================================
-- Plancher sur le CORPS de l'enveloppe (Story 1.7, revue de suivi).
--
-- V8 a aligné le CHECK sur la forme que l'application reconnaît. Il restait un
-- écart : la forme acceptait un corps base64 de longueur QUELCONQUE, alors
-- qu'une enveloppe réelle ne peut pas être plus courte que son IV (12 octets)
-- et son tag GCM (16), soit 28 octets = 38 caractères base64 significatifs,
-- même pour un clair vide. Conséquence sur le chemin de provisioning en SQL
-- direct (le seul qui existe jusqu'à l'outillage d'admin de l'Epic 7) :
--
--   INSERT ... secret_key = 'esc:1:v1:AAAA'
--
-- franchissait le CHECK ; l'application y voyait une enveloppe BIEN FORMÉE de
-- la clé active, donc le balayage de démarrage la comptait « inchangée » — et
-- chaque lecture du secret échouait ensuite sur « enveloppe tronquée » (500 sur
-- toute requête du partenaire concerné), sans qu'aucune couche n'ait jamais
-- signalé la ligne. Le déguisement était refusé quand il était mal formé, pas
-- quand il était bien formé mais vide.
--
-- Le motif est le MÊME que SecretCipher.TEXT_ENVELOPE_PATTERN, plancher
-- compris (SecretCipher.MIN_BODY_BASE64_CHARS) : les deux doivent continuer
-- d'évoluer ensemble, comme le rappelle déjà V8.
--
-- Migration NON destructive : toute enveloppe produite par SecretCipher porte
-- au moins 38 caractères de corps, et tout clair legacy >= 32 octets reste
-- accepté par la seconde branche.
-- ============================================================================

ALTER TABLE partner_hmac_keys
    DROP CONSTRAINT ck_partner_hmac_keys_secret_len;

ALTER TABLE partner_hmac_keys
    ADD CONSTRAINT ck_partner_hmac_keys_secret_len
        CHECK (secret_key ~ '^esc:[0-9]{1,3}:[A-Za-z0-9_-]{1,64}:[A-Za-z0-9+/]{38,}={0,2}$'
               OR octet_length(secret_key) >= 32);

COMMENT ON COLUMN partner_hmac_keys.secret_key IS
    'Secret HMAC entrant, CHIFFRÉ au repos (enveloppe esc:<version>:<idDeClé>:<base64>, AES-256-GCM). '
    'La base ne peut plus lire le clair : la clé vit dans ESCROW_CRYPTO_KEYS, hors du dépôt et hors '
    'de la base. Le CHECK accepte soit une enveloppe DÉCHIFFRABLE (forme complète ET corps assez long '
    'pour porter IV + tag), soit un clair legacy >= 32 octets — jamais un clair court déguisé en '
    'enveloppe, bien formé ou non (Story 1.7, AD-29).';
