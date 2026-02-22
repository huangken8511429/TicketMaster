# === Build Stage ===
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /app

# Cache Gradle wrapper
COPY gradlew gradle/ ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew --version

# Cache dependencies
COPY build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon || true

# Build application
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# === Runtime Stage ===
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

# ZGC for low-latency under high concurrency
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -Xmx2G -Xms2G -XX:+AlwaysPreTouch"

EXPOSE 8080 8180 8280

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
