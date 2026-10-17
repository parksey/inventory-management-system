# jar는 플랫폼과 상관없으므로 빌드는 빌드하는 기계의 플랫폼에서 한 번만 한다 (arm64 이미지도 에뮬레이션 없이 같은 jar를 쓴다)
FROM --platform=$BUILDPLATFORM amazoncorretto:21-alpine AS build
WORKDIR /workspace
COPY . .

RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon :app:bootJar

# Corretto는 JRE 전용 이미지가 없어 JDK 이미지를 그대로 쓴다
FROM amazoncorretto:21-alpine
RUN adduser -S -u 1001 app
USER app
COPY --from=build /workspace/app/build/libs/*.jar /app.jar

ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app.jar"]
