FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN mkdir -p /var/music/audio /var/log/musicapp && chown -R appuser:appgroup /var/music/audio /var/log/musicapp /app
USER appuser
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
