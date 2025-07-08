# Use a slim OpenJDK base image
FROM openjdk:17-jdk-slim

# Set the working directory in the container
WORKDIR /app

# Copy the Maven wrapper files and pom.xml to leverage Docker caching
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Download dependencies (only if pom.xml changes)
RUN ./mvnw dependency:go-offline

# Copy the rest of the application source code
COPY src src

# Build the application
RUN ./mvnw install -DskipTests

# Expose the port your Spring Boot app runs on
EXPOSE 8080

# Command to run the application
ENTRYPOINT ["java", "-jar", "target/crypto-scanner-backend-0.0.1-SNAPSHOT.jar"]