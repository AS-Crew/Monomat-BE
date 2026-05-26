# ============================================================================
# Monomat-BE Dockerfile
# Java 21 + Spring Boot 4.x
# ============================================================================

# 1. Build stage
FROM gradle:9.1.0-jdk21 AS builder

WORKDIR /app

# Gradle 캐시 효율을 위해 설정 파일을 먼저 복사
COPY build.gradle settings.gradle ./

# 소스 복사
COPY src ./src

# Gradle 이미지에 포함된 gradle 명령어를 사용한다.
# ./gradlew를 사용하면 컨테이너 내부에서 Gradle Wrapper distribution을 다시 다운로드하다가
# 네트워크 타임아웃이 발생할 수 있다.
RUN gradle clean bootJar --no-daemon -x test


# 2. Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

ENV TZ=Asia/Seoul
ENV SPRING_PROFILES_ACTIVE=prod

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]