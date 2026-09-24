FROM gradle:8.14.3-jdk21-alpine AS build
WORKDIR /workspace/api
COPY api/build.gradle.kts api/settings.gradle.kts ./
COPY api/gradle ./gradle
COPY api/gradlew api/gradlew.bat ./
RUN ./gradlew --no-daemon dependencies
COPY api/src ./src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21.0.2_13-jre-jammy
WORKDIR /app
RUN useradd --system --uid 10001 --create-home appuser
COPY --from=build --chown=10001:10001 /workspace/api/build/libs/paper-t-rail-api-0.1.0.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
