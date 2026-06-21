# syntax=docker/dockerfile:1.6
# Build context: repo root  (docker build -f admin-service/Dockerfile .)

# ── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Copy parent pom first — changes rarely, so this layer is cached
COPY pom.xml                   ./pom.xml

# Copy all child poms so Maven can resolve the reactor in offline mode
COPY admin-service/pom.xml     ./admin-service/pom.xml
COPY search-indexer/pom.xml    ./search-indexer/pom.xml
COPY rag-indexer/pom.xml       ./rag-indexer/pom.xml

# Pre-fetch dependencies (cache layer)
RUN mvn -B -q -DskipTests dependency:go-offline -pl admin-service -am

# Copy this module's sources and build the fat jar
COPY admin-service/src         ./admin-service/src
RUN mvn -B -q -DskipTests package -pl admin-service -am

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

COPY --from=build /workspace/admin-service/target/portfolio-admin-service-*.jar /app/app.jar

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -Djava.net.preferIPv4Stack=true"
EXPOSE 8081

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
