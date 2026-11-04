# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-noble AS builder
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle proguard.pro ./
COPY gradle ./gradle
RUN chmod +x ./gradlew
COPY src ./src
RUN ./gradlew bootJarObfuscate --no-daemon -x test

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
RUN groupadd -r appgroup && useradd -r -g appgroup appuser \
    && mkdir -p /app/logs/archive && chown -R appuser:appgroup /app/logs
COPY --from=builder /workspace/build/libs/app-obfuscated.jar /app/app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
