# ---------- 1단계: 빌드 ----------
FROM gradle:8.12-jdk21 AS builder
WORKDIR /app

COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar --no-daemon

# ---------- 2단계: 실행 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
