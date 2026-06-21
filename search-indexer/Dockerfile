# syntax=docker/dockerfile:1.6
# Build context: repo root  (docker build -f search-indexer/Dockerfile .)

# ── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml                   ./pom.xml
COPY admin-service/pom.xml     ./admin-service/pom.xml
COPY search-indexer/pom.xml    ./search-indexer/pom.xml
COPY rag-indexer/pom.xml       ./rag-indexer/pom.xml

RUN mvn -B -q -DskipTests dependency:go-offline -pl search-indexer -am

COPY search-indexer/src        ./search-indexer/src
RUN mvn -B -q -DskipTests package -pl search-indexer -am

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

COPY --from=build /workspace/search-indexer/target/portfolio-search-indexer-*.jar /app/app.jar

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -Djava.net.preferIPv6Addresses=true"
EXPOSE 8082

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
