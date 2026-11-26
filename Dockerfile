# syntax=docker/dockerfile:1.7

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Optional, for networks with a TLS-intercepting proxy / egress gateway: trust extra CA
# certificates during the build only (the runtime image is unaffected). Accepts a PEM bundle.
#   docker build --secret id=ca,src=corporate-ca-bundle.pem .
RUN --mount=type=secret,id=ca,required=false \
    if [ -f /run/secrets/ca ]; then \
      mkdir -p /tmp/extra-ca && cd /tmp/extra-ca \
      && awk '/BEGIN CERTIFICATE/{n++} n{print > ("cert-" n ".pem")}' /run/secrets/ca \
      && for f in cert-*.pem; do \
           keytool -importcert -noprompt -cacerts -storepass changeit -alias "extra-$f" -file "$f" >/dev/null 2>&1 || true; \
         done \
      && echo "Imported $(ls cert-*.pem | wc -l) extra CA certificate(s)"; \
    fi

# Dependencies first, so they are cached independently of source changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/slotwise.jar extract --layers --launcher --destination target/extracted

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system slotwise && useradd --system --gid slotwise --no-create-home slotwise
WORKDIR /app

# Spring Boot layers, least to most frequently changing, for efficient image layer caching.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

USER slotwise
EXPOSE 8080
ENV TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
