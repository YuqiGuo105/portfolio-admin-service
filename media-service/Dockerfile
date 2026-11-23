# syntax=docker/dockerfile:1.6
# Build context: repo root  (docker build -f media-service/Dockerfile .)

# ── Build stage ─────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml                   ./pom.xml
COPY admin-service/pom.xml     ./admin-service/pom.xml
COPY search-indexer/pom.xml    ./search-indexer/pom.xml
COPY rag-indexer/pom.xml       ./rag-indexer/pom.xml
COPY media-service/pom.xml     ./media-service/pom.xml

RUN mvn -B -q -DskipTests dependency:go-offline -pl media-service -am

COPY media-service/src         ./media-service/src
RUN mvn -B -q -DskipTests package -pl media-service -am

# ── Runtime stage ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# The service shells out to these. libvips needs the libheif plugins to write AVIF and read HEIC.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg libvips-tools libheif-plugin-aomenc libheif-plugin-libde265 \
 && rm -rf /var/lib/apt/lists/* \
 && ffmpeg -version | head -n 1 && vips --version

COPY --from=build /workspace/media-service/target/portfolio-media-service-*.jar /app/app.jar

# Transcoding is CPU-bound: give the JVM half the memory and leave the rest to FFmpeg.
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=50 -Djava.net.preferIPv6Addresses=true"
EXPOSE 8084

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
