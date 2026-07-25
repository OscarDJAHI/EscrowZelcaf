#!/usr/bin/env bash
# Vérification manuelle de la couche reverse-proxy (Story 1.4, NFR-P3).
# La redirection HTTP->HTTPS et les en-têtes posés par nginx ne sont PAS
# couvrables par les tests MockMvc (couche proxy) ; ce script les éprouve sur la
# stack compose réelle. L'automatisation E2E est une dette de la Story 11.7.
#
# Prérequis : la stack tourne (docker compose -f infra/docker-compose.yml up --build).
# Usage : infra/verify-tls-headers.sh [http_port] [https_port]
#   (défauts alignés sur .env.example : 8080 / 8443)
set -euo pipefail

HTTP_PORT="${1:-8080}"
HTTPS_PORT="${2:-8443}"
FAIL=0

check() { # description | commande produisant la valeur | motif grep attendu
  local desc="$1" value="$2" pattern="$3"
  if grep -qi "$pattern" <<<"$value"; then
    echo "  OK   $desc"
  else
    echo "  FAIL $desc (attendu ~ '$pattern')"
    FAIL=1
  fi
}

echo "== AC #1 : redirection HTTP -> HTTPS =="
REDIRECT=$(curl -sI "http://localhost:${HTTP_PORT}/" || true)
check "301 Moved Permanently"        "$REDIRECT" "301"
check "Location: https://"           "$REDIRECT" "location: https://"

echo "== AC #1/#2 : en-têtes de sécurité sur la réponse HTTPS (HTML) =="
# -k : certificat auto-signé accepté en local.
HEADERS=$(curl -skI "https://localhost:${HTTPS_PORT}/" || true)
check "Strict-Transport-Security (HSTS)" "$HEADERS" "strict-transport-security: max-age=31536000"
check "Content-Security-Policy"          "$HEADERS" "content-security-policy:"
check "X-Frame-Options: DENY"            "$HEADERS" "x-frame-options: deny"
check "X-Content-Type-Options: nosniff"  "$HEADERS" "x-content-type-options: nosniff"
check "Referrer-Policy"                  "$HEADERS" "referrer-policy: strict-origin-when-cross-origin"
check "Permissions-Policy"               "$HEADERS" "permissions-policy:"

echo "== AC #3 : l'API répond via le proxy (même origine /api) =="
API=$(curl -skI "https://localhost:${HTTPS_PORT}/api/v1/escrow" || true)
# 401/403 attendu (endpoint protégé) : prouve que /api est bien proxifié, pas 404.
check "API proxifiée (pas de 404)" "$API" "HTTP/.* 40[13]"

echo "== Story 1.5 : sondes de documentation d'API coupées à l'ingress =="
# Couche nginx, non couvrable par MockMvc (les tests d'intégration prouvent la
# fermeture CÔTÉ BACKEND sous profil prod ; ici on prouve l'ingress). Sans les
# locations dédiées, ces chemins recevraient le shell SPA en 200.
for probe in /swagger-ui.html /swagger-ui /swagger-ui/index.html /v3/api-docs /v3/api-docs.yaml; do
  PROBE=$(curl -skI "https://localhost:${HTTPS_PORT}${probe}" || true)
  check "404 sur ${probe}" "$PROBE" "HTTP/.* 404"
done

echo
if [ "$FAIL" -eq 0 ]; then
  echo "TOUT VERT - OK"
else
  echo "ECHECS DETECTES"
  exit 1
fi
