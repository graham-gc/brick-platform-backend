FROM maven:3.9.13-eclipse-temurin-8-noble AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
COPY examples ./examples
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:8-jre-noble AS runtime

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system brick \
    && useradd --system --gid brick --home-dir /app brick

WORKDIR /app

COPY --from=build --chown=brick:brick /workspace/target/BrickPlatform-*.jar ./app.jar

USER brick

EXPOSE 8080 9090

ENV JAVA_OPTS=""

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD curl --fail --silent http://localhost:8080/api/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
