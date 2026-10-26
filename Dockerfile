# syntax=docker/dockerfile:1

# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# ---- run ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/ridehail-platform-*.jar app.jar
USER app
EXPOSE 8080
# size the heap from the container's memory limit; die cleanly on OOM so the orchestrator restarts us
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
