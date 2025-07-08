# --- Stage 1: Build the application ---
FROM openjdk:17-jdk-slim AS builder

WORKDIR /app

# Copy Maven wrapper and pom.xml first to leverage Docker layer caching
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Grant execute permissions to mvnw
RUN chmod +x mvnw

# Download dependencies (only if pom.xml changes)
RUN ./mvnw dependency:go-offline

# Copy the rest of the application source code
COPY src src

# Build the application - this creates the JAR in /app/target/
# The spring-boot-maven-plugin will repackage it into an executable JAR.
# The name will be artifactId-version.jar
RUN ./mvnw install -DskipTests

# --- Stage 2: Create the final runtime image ---
FROM eclipse-temurin:17-jre-focal

WORKDIR /app

# Copy only the built and REPACKAGED JAR file from the 'builder' stage
# CORRECTED JAR NAME: removed hyphen between 'crypto' and 'scanner'
COPY --from=builder /app/target/cryptoscannerbackend-0.0.1-SNAPSHOT.jar .

# Expose the port your Spring Boot app runs on
EXPOSE 8080

# Command to run the application
# CORRECTED JAR NAME: removed hyphen between 'crypto' and 'scanner'
ENTRYPOINT ["java", "-jar", "cryptoscannerbackend-0.0.1-SNAPSHOT.jar"]
