FROM maven:3.9.8-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
# Cache-bust: increment this comment to force rebuild: v14
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd -r etl && useradd -r -g etl etl
COPY --from=builder /build/target/sreality-etl-1.0-SNAPSHOT.jar app.jar
RUN mkdir -p logs && chown -R etl:etl /app
USER etl

# Memory budget (ArcGIS hybrid mode — 6258 obec polygons, no ZSJ):
#   JVM base:                  ~80 MB
#   6258 obec polygons (JTS):  ~80 MB  (simplified + STRtree)
#   MOS CSV parse buffer:      ~50 MB  (42 MB file in memory during parse)
#   VFR XML (StAX streaming):  ~20 MB
#   MongoDB batch (500 docs):  ~20 MB
#   JDBC + HikariCP:           ~20 MB
#   JVM overhead + GC:         ~50 MB
#   Total peak:               ~320 MB → Xmx512m is enough
#   Container limit:           768m    (2.4x headroom)
ENTRYPOINT ["java", "-Xms64m", "-Xmx512m", "-XX:+UseContainerSupport", "-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=16m", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
