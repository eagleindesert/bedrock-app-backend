# Stage 1: Build the application
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Copy gradle wrapper files
COPY gradlew .
COPY gradle gradle
# Copy build configuration files
COPY build.gradle settings.gradle ./
# Copy source code
COPY src src

# Make wrapper executable and build the project
# Exclude tests during docker build for faster startup
RUN chmod +x ./gradlew
RUN ./gradlew build -x test --no-daemon

# Stage 2: Run the application
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the built jar file from the builder stage
# Spring Boot 3+ typically creates an executable jar ending with -SNAPSHOT.jar or similar
COPY --from=builder /app/build/libs/*.jar app.jar

# Run the jar file
ENTRYPOINT ["java", "-jar", "app.jar"]
