FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY server ./server

RUN printf '%s\n' \
    'pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }' \
    'dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }' \
    'rootProject.name = "CatLifePetServer"' \
    'include(":server")' \
    > settings.gradle.kts \
    && chmod +x ./gradlew \
    && ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --home-dir /opt/catlifepet catlifepet

WORKDIR /opt/catlifepet
COPY --from=build /workspace/server/build/install/server ./server
RUN chown -R catlifepet:catlifepet /opt/catlifepet

USER catlifepet
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8080/health >/dev/null || exit 1

ENTRYPOINT ["./server/bin/server"]
