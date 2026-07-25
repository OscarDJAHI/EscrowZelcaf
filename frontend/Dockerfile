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
# Certificat TLS auto-signé embarqué pour l'usage LOCAL/STAGING (Story 1.4).
# En staging/prod un vrai certificat est monté par volume sur /etc/nginx/certs,
# écrasant celui-ci (secrets injectés hors-repo, pattern Story 1.2 / spike AR-P4).
RUN mkdir -p /etc/nginx/certs && \
    openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
      -keyout /etc/nginx/certs/tls.key -out /etc/nginx/certs/tls.crt \
      -subj "/CN=localhost" && \
    chmod 600 /etc/nginx/certs/tls.key
EXPOSE 80 443
