# syntax=docker/dockerfile:1

# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
# Tests run separately (they need Docker for Testcontainers); see README.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/rdss-backend.jar extract --layers --launcher --destination /extracted

# ---- runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S rdss && adduser -S -G rdss -H -s /sbin/nologin rdss
WORKDIR /app
COPY --from=build --chown=rdss:rdss /extracted/dependencies/ ./
COPY --from=build --chown=rdss:rdss /extracted/spring-boot-loader/ ./
COPY --from=build --chown=rdss:rdss /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=rdss:rdss /extracted/application/ ./
USER rdss
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness >/dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
