# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ARG APP_VERSION=unknown
ENV APP_VERSION=${APP_VERSION}

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -Dapp.version=$APP_VERSION -jar app.jar"]
