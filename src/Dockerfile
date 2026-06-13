# syntax=docker/dockerfile:1
#
# Shared multi-stage build for every loyalty service. Each service in src/ is a
# self-contained Gradle (Kotlin DSL) project with its own wrapper, so the build
# context is the individual service directory and this same Dockerfile is reused
# for all of them (see src/docker-compose.yml -> services.*.build.dockerfile).
#
# Stack: Java 25 (LTS) · Spring Boot 4 · Gradle 9.5.1 (via the project wrapper).

# ---- build stage -----------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy the Gradle wrapper + build scripts first so the dependency layer caches
# independently of source changes. The cache mount on ~/.gradle is shared across
# all service builds, so the Gradle distribution + dependencies download once.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x ./gradlew

# Source, then build the executable boot jar. Tests are skipped here: they rely on
# Testcontainers (Docker-in-Docker) and are run in CI, not during image builds.
COPY src ./src
# --no-watch-fs: Gradle's native file-system watching fails to initialise inside the build
# container on some hosts (overlay FS / WSL2), throwing "Cannot create service ... FileWatchingFilter".
# It is useless for a one-shot image build anyway.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon --no-watch-fs -x test \
 && JAR="$(ls build/libs/*.jar | grep -v -- '-plain.jar' | head -n1)" \
 && cp "$JAR" /app/app.jar

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Run as a non-root user.
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
COPY --from=build /app/app.jar ./app.jar
USER app

# Honour container memory limits; the actual port comes from SERVER_PORT (compose).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
