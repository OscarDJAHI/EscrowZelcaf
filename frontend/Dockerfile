# --- Build stage ---
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
# Bake the API base URL at build time (overridable via build arg).
ARG VITE_API_BASE=http://localhost:8080
ENV VITE_API_BASE=$VITE_API_BASE
RUN npm run build

# --- Runtime stage ---
FROM nginx:1.27-alpine
# Correctifs sécurité OS (Story 11.1) + openssl pour le certificat local/staging.
RUN apk upgrade --no-cache && apk add --no-cache openssl
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
# Certificat TLS généré au DÉMARRAGE, jamais baked dans l'image (Story 1.4) :
# une clé privée en couche d'image est une mauvaise pratique et le scan Trivy la
# rejette (finding HIGH). Le script /docker-entrypoint.d/*.sh est exécuté par
# l'entrypoint nginx avant le lancement ; il génère un pair auto-signé seulement
# si aucun certificat n'est monté sur /etc/nginx/certs (local/staging), et laisse
# intact un vrai certificat monté par volume (staging/prod).
COPY docker-entrypoint.d/40-generate-tls-cert.sh /docker-entrypoint.d/40-generate-tls-cert.sh
RUN chmod +x /docker-entrypoint.d/40-generate-tls-cert.sh
EXPOSE 80 443
