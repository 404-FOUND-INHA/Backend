# ---- build ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# 의존성 레이어를 먼저 받아 두면, 소스만 바뀐 재빌드가 빨라진다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew --no-daemon bootJar -x test \
    && cp "$(ls build/libs/*.jar | grep -v plain | head -1)" /workspace/app.jar

# ---- run ----
FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && apt-get clean \
    && useradd --system --uid 1001 --no-create-home app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app

# 같은 서버에 DB·Redis 도 함께 올리므로 힙을 제한한다. 시간대는 DB(Asia/Seoul)와 맞춘다.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -Duser.timezone=Asia/Seoul"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
