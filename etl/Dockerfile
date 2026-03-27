FROM maven:3.9.8-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd -r etl && useradd -r -g etl etl
COPY --from=builder /build/target/sreality-etl-1.0-SNAPSHOT.jar app.jar
RUN mkdir -p logs && chown -R etl:etl /app
USER etl

# Memory budget:
#   JVM base:             ~80 MB
#   Obec polygons:        ~60 MB  (simplified, ~6k polygons)
#   ZSJ polygons:        ~120 MB  (simplified, ~45k polygons)
#   STRtree indexes:      ~40 MB
#   MongoDB batch:        ~20 MB
#   JDBC + misc:          ~30 MB
#   Safety margin:        ~50 MB
#   Total peak:          ~400 MB → Xmx512m
ENTRYPOINT ["java",  "-Xms64m", "-Xmx512m",  "-XX:+UseContainerSupport", "-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=16m", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
