# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9.8-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Runtime
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd -r etl && useradd -r -g etl etl

COPY --from=builder /build/target/sreality-etl-1.0-SNAPSHOT.jar app.jar

RUN mkdir -p logs && chown -R etl:etl /app

USER etl

# Memory budget on a 4 GB server shared with MongoDB and PostgreSQL:
#   MongoDB:    ~512 MB
#   PostgreSQL: ~512 MB
#   Scraper:    ~512 MB (when running)
#   OS + other: ~512 MB
#   ETL:        ~512 MB available (scraper not running at same time as ETL)
#
# Heap breakdown at peak:
#   JVM base overhead:                ~80 MB
#   RUIAN obec polygons (simplified): ~60 MB  (was ~200 MB before simplification)
#   RUIAN cast_obce attributes:       ~20 MB
#   STRtree spatial index:            ~30 MB
#   MongoDB batch (500 estates):      ~20 MB
#   JDBC + HikariCP:                  ~20 MB
#   Jackson, logback, misc:           ~20 MB
#   Safety margin:                    ~50 MB
#   Total:                           ~300 MB → set Xmx to 384m
ENTRYPOINT ["java",  "-Xms64m", "-Xmx384m",  "-XX:+UseContainerSupport",  "-XX:+UseSerialGC",  "-XX:ReservedCodeCacheSize=16m",  "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
