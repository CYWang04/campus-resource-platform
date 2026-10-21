FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/ ./
RUN --mount=type=cache,target=/root/.m2 mvn -B package
ARG MODULE
RUN cp ${MODULE}/target/${MODULE}-0.1.0-SNAPSHOT.jar /app.jar
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 campus
WORKDIR /app
COPY --from=build /app.jar app.jar
USER campus
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
