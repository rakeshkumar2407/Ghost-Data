FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY backend/pom.xml .
COPY backend/src src
COPY dataset /dataset
RUN mvn -B package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /build/target/deletion-audit-0.1.0.jar app.jar
COPY --chown=app:app dataset /app/dataset
ENV DATASET_DIRECTORY=/app/dataset
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
