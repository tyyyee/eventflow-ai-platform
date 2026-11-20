FROM maven:3.9.11-eclipse-temurin-17 AS build
ARG WITH_SHARDING=false
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src src
RUN if [ "$WITH_SHARDING" = "true" ]; then mvn -B -Psharding -DskipTests package; else mvn -B -DskipTests package; fi

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
RUN mkdir -p /app/data/media && chown -R app:app /app/data
COPY --from=build /workspace/target/life-choice-backend-1.0.0.jar app.jar
USER app
EXPOSE 8093
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--upgrade.demo-data=true"]

