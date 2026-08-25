# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Correctifs sécurité OS (Story 11.1) : l'image temurin traîne derrière les
# fixes Alpine (libexpat, p11-kit…) ; upgrade à chaque build.
RUN apk upgrade --no-cache && addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/escrow-core-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
