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
