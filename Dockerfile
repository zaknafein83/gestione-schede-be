# Multi-stage build: Maven dentro l'immagine per evitare di doverlo avere
# in locale o in CI. Java 25 LTS (Eclipse Temurin) sia per build che runtime.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
# Pre-scarica le dipendenze: cache layer separato dal codice, così tocchi
# al codice non invalidano la cache delle dep.
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Runtime: solo JRE alpine, niente Maven, niente sorgente.
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# wget per il healthcheck del container.
RUN apk add --no-cache wget

# Quarkus produce il layout "fast jar" in target/quarkus-app/.
# Copiamo le 4 cartelle che servono al runtime nelle posizioni attese.
COPY --from=build /app/target/quarkus-app/lib/        ./lib/
COPY --from=build /app/target/quarkus-app/*.jar       ./
COPY --from=build /app/target/quarkus-app/app/        ./app/
COPY --from=build /app/target/quarkus-app/quarkus/    ./quarkus/

# Utente non-root (alpine ha "java" come UID 1000 nelle immagini ufficiali
# Temurin? No — creiamo il nostro).
RUN addgroup -S app && adduser -S app -G app && chown -R app:app /app
USER app

EXPOSE 8080

ENV QUARKUS_PROFILE=prod \
    QUARKUS_HTTP_HOST=0.0.0.0 \
    QUARKUS_HTTP_PORT=8080

ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
