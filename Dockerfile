FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle/ gradle/
RUN ./gradlew dependencies --no-daemon --quiet
COPY src/ src/
RUN ./gradlew installDist --no-daemon -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/build/install/log-fix-agent/ .
ENTRYPOINT ["bin/log-fix-agent"]
