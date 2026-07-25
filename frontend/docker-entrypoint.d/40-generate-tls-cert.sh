#!/bin/sh
# Génère un certificat TLS auto-signé au DÉMARRAGE du conteneur (Story 1.4).
#
# Pourquoi au runtime et non au build : une clé privée baked dans une couche
# d'image est une mauvaise pratique et le scan Trivy la rejette (finding HIGH
# « private key »). Générée ici, la clé vit dans le système de fichiers éphémère
# du conteneur, jamais dans l'image publiée.
#
# L'image nginx exécute automatiquement /docker-entrypoint.d/*.sh (par ordre de
# nom) AVANT de lancer nginx. Si un vrai certificat est déjà monté par volume sur
# /etc/nginx/certs (staging/prod), on le laisse intact : la génération n'a lieu
# que pour l'usage LOCAL/STAGING sans certificat fourni.
set -e

CERT_DIR=/etc/nginx/certs

if [ -s "$CERT_DIR/tls.crt" ] && [ -s "$CERT_DIR/tls.key" ]; then
    echo "[tls] certificat déjà présent sur $CERT_DIR — génération ignorée"
    exit 0
fi

echo "[tls] aucun certificat monté — génération d'un pair auto-signé (local/staging uniquement)"
mkdir -p "$CERT_DIR"
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
    -keyout "$CERT_DIR/tls.key" -out "$CERT_DIR/tls.crt" \
    -subj "/CN=localhost"
chmod 600 "$CERT_DIR/tls.key"
