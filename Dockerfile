# Stage 1: build the application
FROM eclipse-temurin:25-jdk-ubi10-minimal AS builder

# set working directory
WORKDIR /app

# copy gradle wrapper and configuration
COPY banking/gradlew banking/settings.gradle banking/build.gradle ./
COPY banking/gradle ./gradle

# download dependencies
RUN ./gradlew dependencies --no-daemon

# copy the application source code
COPY banking/src ./src

# build the executable
RUN ./gradlew bootJar --no-daemon

# Stage 2: run the application
FROM eclipse-temurin:25-jre-ubi10-minimal

WORKDIR /app

# copy compiled application from the builder
COPY --from=builder /app/build/libs/*.jar app.jar

# expose the application port
EXPOSE 8080

# run the application
ENTRYPOINT ["java", "-jar", "app.jar"]