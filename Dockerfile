# One image recipe for every Java service: docker build --build-arg MODULE=trade-service .
FROM eclipse-temurin:21-jdk AS build
ARG MODULE
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY accounting-engine accounting-engine
COPY service-common service-common
COPY reference-data-service reference-data-service
COPY trade-service trade-service
COPY accounting-service accounting-service
COPY gateway gateway
RUN --mount=type=cache,target=/root/.gradle ./gradlew :${MODULE}:bootJar -x test --no-daemon -q \
    && cp ${MODULE}/build/libs/${MODULE}-*[0-9].jar /app.jar \
    && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
