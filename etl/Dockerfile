# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9.8-eclipse-temurin-21 AS builder

WORKDIR /build

# Cache dependency resolution layer separately from source compilation
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Runtime
# Slim JRE only — no build tools. Same base as the scraper for layer sharing.
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd -r etl && useradd -r -g etl etl

COPY --from=builder /build/target/sreality-etl-1.0-SNAPSHOT.jar app.jar

RUN mkdir -p logs && chown -R etl:etl /app

USER etl

# Memory settings — conservative for a 4 GB shared server:
#   -Xms64m   : start small, expand only as needed
#   -Xmx256m  : hard cap — reference data (RUIAN polygons) ~50 MB, batch data ~50 MB
#   -XX:+UseSerialGC          : single-threaded GC, no GC threads stealing RAM
#   -XX:+UseContainerSupport  : respect cgroup memory limits from Docker
#   -XX:+ExitOnOutOfMemoryError : crash loudly rather than thrash
ENTRYPOINT ["java",
  "-Xms64m", "-Xmx256m",
  "-XX:+UseContainerSupport",
  "-XX:+UseSerialGC",
  "-XX:ReservedCodeCacheSize=16m",
  "-XX:+ExitOnOutOfMemoryError",
  "-jar", "app.jar"]
