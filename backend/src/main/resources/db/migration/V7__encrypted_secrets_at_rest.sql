-- ============================================================================
-- Chiffrement au repos des secrets partagés (Epic 1, Story 1.7 — AD-29, NFR-P6).
-- Les deux colonnes secret_key ne contiennent plus le clair mais une enveloppe
-- AES-256-GCM auto-descriptive « esc:1:<idDeClé>:<base64> » produite dans la JVM
-- (SecretCipher) : un pg_dump, une copie du volume pgdata ou une sauvegarde ne
-- divulguent plus rien d'exploitable, alors que la rétention imposée est >= 5 ans.
--
-- Conséquences de schéma, toutes délibérées :
--  (1) VARCHAR(255) -> TEXT : l'enveloppe (IV + chiffré + tag + en-tête, encodés
--      en base64) est ~1,4x plus longue que le clair et dépasserait 255 pour un
--      secret long. Une borne de longueur ne protégerait plus rien ici.
--  (2) ck_partner_hmac_keys_secret_len (V5) est REFORMULÉE, pas abandonnée. Telle
--      quelle elle mesurerait le CHIFFRÉ — garde mensongère, un secret d'un octet
--      la franchirait une fois chiffré. Mais la supprimer purement ouvrirait un
--      trou réel : il n'existe AUCUN chemin applicatif d'admission de clé
--      partenaire (le provisioning se fait en SQL direct jusqu'à l'outillage
--      d'admin de l'Epic 7), donc une garde posée uniquement dans l'entité JPA ne
--      couvrirait aucun écrivain réel. Le CHECK reste donc, tolérant l'enveloppe :
--      une valeur chiffrée est acceptée telle quelle, une valeur EN CLAIR doit
--      toujours peser >= 32 octets. Le préfixe n'est pas une porte dérobée :
--      forger une enveloppe déchiffrable exige la clé, et une pseudo-enveloppe
--      illisible fait échouer bruyamment la lecture, jamais une signature valide.
--      Le même plancher est doublé dans PartnerHmacKey (@PrePersist/@PreUpdate)
--      pour les écritures JPA, seul endroit qui voit le clair côté application.
--
-- Migration NON destructive : les lignes existantes restent en clair et lisibles
-- (le convertisseur les tolère) ; SecretsEncryptionBootstrap les scelle au premier
-- démarrage. Aucune clé ne figure ici — elles viennent d'ESCROW_CRYPTO_KEYS.
-- ============================================================================

ALTER TABLE partner_hmac_keys
    DROP CONSTRAINT ck_partner_hmac_keys_secret_len;

ALTER TABLE partner_hmac_keys
    ALTER COLUMN secret_key TYPE TEXT;

ALTER TABLE partner_hmac_keys
    ADD CONSTRAINT ck_partner_hmac_keys_secret_len
        CHECK (secret_key LIKE 'esc:%' OR octet_length(secret_key) >= 32);

ALTER TABLE webhook_subscriptions
    ALTER COLUMN secret_key TYPE TEXT;

COMMENT ON COLUMN partner_hmac_keys.secret_key IS
    'Secret HMAC entrant, CHIFFRÉ au repos (enveloppe esc:1:<idDeClé>:<base64>, AES-256-GCM). '
    'La base ne peut plus valider sa longueur ni le lire : la clé vit dans ESCROW_CRYPTO_KEYS, '
    'hors du dépôt et hors de la base (Story 1.7, AD-29).';

COMMENT ON COLUMN webhook_subscriptions.secret_key IS
    'Secret HMAC sortant, CHIFFRÉ au repos (même enveloppe que partner_hmac_keys.secret_key). '
    'Strictement distinct du secret entrant : seule la protection est commune (Story 1.7, AD-29).';
