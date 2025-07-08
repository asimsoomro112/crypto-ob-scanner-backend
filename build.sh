#!/bin/bash

echo "--- Custom Build Script: Installing Maven ---"

# Install Maven (using apt-get for Ubuntu-based Render environments)
sudo apt-get update -y
sudo apt-get install -y maven

# Verify Maven installation
mvn -v

echo "--- Maven Installation Complete. Running Project Build ---"

# Now run your actual Maven build command
mvn clean install -DskipTests

echo "--- Project Build Complete ---"