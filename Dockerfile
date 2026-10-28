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

# Pick up Alpine security fixes released since the base image was built.
RUN apk upgrade --no-cache

# Run as a non-root user.
RUN addgroup -S app && adduser -S app -G app

# Unpack the fat jar into app.jar + lib/ (Spring Boot's recommended layout for
# CDS), then do a training run that records every class the app loads into a
# Class Data Sharing archive. Real starts map that archive instead of loading and
# verifying ~15k classes again, which matters a lot on small hosts (Render's free
# tier gets 0.1 CPU). There's no database at build time, so the training run
# skips Flyway, schema validation and Hibernate's JDBC metadata lookup, and
# exits as soon as the context has started (spring.context.exit=onRefresh).
COPY --from=build /build/target/*.jar /tmp/app.jar
RUN java -Djarmode=tools -jar /tmp/app.jar extract --destination /app/extracted \
    && rm /tmp/app.jar \
    && cd /app/extracted \
    && java -XX:ArchiveClassesAtExit=app.jsa \
        -Dspring.context.exit=onRefresh \
        -Dspring.flyway.enabled=false \
        -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -Dapp.notifications.enabled=false \
        -jar app.jar \
    && chown -R app:app /app
USER app
WORKDIR /app/extracted

EXPOSE 8080

# Container-level health check hits the actuator endpoint (public, see SecurityConfig).
# Alpine's busybox provides wget. PORT is honoured like the app does (Render sets it).
HEALTHCHECK --interval=15s --timeout=3s --start-period=120s --retries=5 \
    CMD wget -qO- "http://localhost:${PORT:-8080}/actuator/health" | grep -q '"status":"UP"' || exit 1

# JAVA_OPTS lets a PaaS tune heap/GC without rebuilding (e.g. -XX:MaxRAMPercentage=75).
# Exec form via sh -c so JAVA_OPTS expands; exec keeps java as PID 1 for clean signals.
# -Xshare:auto falls back to normal class loading if the archive can't be used.
ENTRYPOINT ["sh", "-c", "exec java -XX:SharedArchiveFile=app.jsa -Xshare:auto $JAVA_OPTS -jar app.jar"]
