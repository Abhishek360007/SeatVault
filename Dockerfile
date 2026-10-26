# ============================================================================
# Multi-stage build. Stage 1 compiles the fat jar with Maven inside the image,
# so the host never needs Maven installed. Stage 2 ships only the JRE + jar,
# keeping the runtime image small.
# ============================================================================

# ---- Stage 1: build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy only the POM first so dependency resolution is cached across rebuilds
# whenever source changes but dependencies don't.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Stage 2: runtime ----
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Run as a non-root user.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

# Container-level health check hits the actuator endpoint (public, see SecurityConfig).
# Alpine's busybox provides wget. Gives the app a generous start window for Flyway/boot.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# JAVA_OPTS lets a PaaS tune heap/GC without rebuilding (e.g. -XX:MaxRAMPercentage=75).
# Exec form via sh -c so JAVA_OPTS expands; exec keeps java as PID 1 for clean signals.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
