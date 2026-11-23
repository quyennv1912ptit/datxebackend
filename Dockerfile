FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
ENV GRADLE_OPTS="-Djava.net.preferIPv4Stack=true"

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --version --no-daemon

COPY src src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/build/libs/*-SNAPSHOT.jar app.jar
USER app

ENV TZ=Asia/Ho_Chi_Minh

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# JVM flags are fixed here: an env_file-supplied JAVA_OPTS could otherwise inject
# -Dspring.profiles.active/-Dwallet.mock-topup-enabled above the pinned profile.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -jar app.jar"]
