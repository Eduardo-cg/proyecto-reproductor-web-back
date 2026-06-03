FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 1000 appgroup \
 && adduser -S -u 1000 -G appgroup appuser \
 && apk add --no-cache su-exec \
 && mkdir -p /var/music/audio /var/log/musicapp \
 && chown -R appuser:appgroup /var/music /var/log/musicapp
WORKDIR /app
COPY --from=build /app/target/app.jar app.jar
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh
EXPOSE 8080 8081
ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-jar", "app.jar"]
