# Build stage
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install dependencies for OpenCV if needed (though openpnp usually bundles them)
# Alpine needs libc6-compat for some native libs
RUN apk add --no-cache libc6-compat libstdc++ gcompat

COPY --from=build /app/target/EthQR-0.0.1-SNAPSHOT.jar app.jar

# Expose the application port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
