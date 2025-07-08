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
RUN ./mvnw install -DskipTests

# --- Stage 2: Create the final runtime image ---
# Corrected: Changed from 17-jre-slim to 17-jre (Moved comment to its own line)
FROM openjdk:17-jre

WORKDIR /app

# Copy only the built JAR file from the 'builder' stage
COPY --from=builder /app/target/crypto-scanner-backend-0.0.1-SNAPSHOT.jar .

# Expose the port your Spring Boot app runs on
EXPOSE 8080

# Command to run the application
ENTRYPOINT ["java", "-jar", "crypto-scanner-backend-0.0.1-SNAPSHOT.jar"]