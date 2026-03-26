# ──────────────────────────────────────────────────────────────────
# Stage 1 – Build
#   Uses the full Maven + JDK image to compile and package the JAR.
# ──────────────────────────────────────────────────────────────────
FROM maven:3.9.8-eclipse-temurin-21 AS builder

WORKDIR /build

# Copy dependency descriptors first so Docker can cache the layer
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build the fat JAR
COPY src ./src
RUN mvn package -DskipTests -q

# ──────────────────────────────────────────────────────────────────
# Stage 2 – Runtime
#   Slim JRE-only image; no build tools, smaller attack surface.
# ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Create a non-root user for security
RUN groupadd -r scraper && useradd -r -g scraper scraper

# Copy only the shaded JAR from the build stage
COPY --from=builder /build/target/sreality-scraper-1.0-SNAPSHOT.jar app.jar

# Directory for log files (mounted as a volume in compose)
RUN mkdir -p logs && chown -R scraper:scraper /app

USER scraper

ENTRYPOINT ["java", "-Xms64m", "-Xmx256m", "-XX:+UseContainerSupport", "-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=16m", "-XX:+ExitOnOutOfMemoryError", "-XX:NativeMemoryTracking=detail", "-jar", "app.jar"]
