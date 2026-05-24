# Stage 1: build — compiles the Maven project inside Cloud Build
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
# Cache dependencies separately from source for faster rebuilds
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: runtime — minimal JRE image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root to limit blast radius if the container is ever compromised.
RUN addgroup -S app && adduser -S -G app -H -h /app app
COPY --from=build --chown=app:app /build/target/trackam-api-*.jar app.jar
USER app

EXPOSE 8080

ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar --spring.profiles.active=prod"]
