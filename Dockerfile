# Build — only src/main (no tests in image)
FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B -Dmaven.test.skip=true dependency:go-offline
COPY src/main ./src/main
RUN mvn -q -B -Dmaven.test.skip=true package

# Run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/planora-backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
