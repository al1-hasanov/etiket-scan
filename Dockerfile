FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle/ gradle/
COPY core/ core/
COPY backend/ backend/
RUN chmod +x gradlew && ./gradlew -PbackendOnly :backend:bootJar --no-daemon

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN mkdir /app/data && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /src/backend/build/libs/etiket-backend-0.2.0.jar /app/etiket.jar
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/etiket.jar"]
