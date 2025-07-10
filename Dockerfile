# Use an official OpenJDK runtime as a parent image
FROM openjdk:21-jdk-slim

# Set the working directory in the container
WORKDIR /app

# Copy the Maven wrapper files to enable building inside Docker
COPY mvnw .
COPY .mvn .mvn

# Copy the pom.xml and download dependencies to leverage Docker layer caching
# This step only runs if pom.xml changes, speeding up subsequent builds
COPY pom.xml .
RUN ./mvnw dependency:go-offline -B

# Copy the rest of the application source code
COPY src src

# Build the Spring Boot application
# REMOVED --offline to ensure fresh dependency fetching if needed
# ADDED ls -l target/ to show contents after build
RUN ./mvnw package -DskipTests && ls -l target/

# Specify the JAR file name. Adjust if your artifactId or version changes.
ARG JAR_FILE=target/cryptoscannerbackend-0.0.1-SNAPSHOT.jar

# Copy the built JAR file into the container
COPY ${JAR_FILE} app.jar

# Expose the port your Spring Boot application runs on
EXPOSE 8080

# Define the command to run your application
ENTRYPOINT ["java", "-jar", "app.jar"]
