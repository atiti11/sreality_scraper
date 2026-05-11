# =============================================================================
# Backend image — Java 21 / Maven / Javalin.
#
# Stage 1: Maven build → produces a shaded fat-jar in /build/target/.
# Stage 2: JRE runtime → just the JAR. Small Alpine base, no Maven left over.
#
# The shaded jar bundles Javalin (Jetty), Jackson, the Postgres JDBC driver
# and HikariCP, so the runtime container needs nothing but Java.
# =============================================================================

# ---- build stage -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve deps in their own layer so source changes don't bust the cache.
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn package -DskipTests -B

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# wget for the HEALTHCHECK below; nothing else is needed.
RUN apk add --no-cache wget

WORKDIR /app
COPY --from=build /build/target/dashboard-backend.jar /app/dashboard-backend.jar

EXPOSE 8000

# /api/health is unauthenticated so the docker healthcheck can hit it
# without credentials. Same contract as the Python service.
HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=5 \
    CMD wget -q -O - http://localhost:8000/api/health >/dev/null 2>&1 || exit 1

# JVM flags:
#   -XX:MaxRAMPercentage=75 keeps the heap proportional to the container
#     memory limit (the prod overlay caps backend at 512m).
#   -Dfile.encoding=UTF-8 ensures Czech place names round-trip correctly.
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75", \
    "-Dfile.encoding=UTF-8", \
    "-jar", "/app/dashboard-backend.jar"]
