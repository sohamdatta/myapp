# The backend: built with Maven, run on a Java 21 runtime as an unprivileged user.
# Build from the repository root: docker build -f deploy/backend.Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY backend/src ./src
RUN mvn -B -ntp -DskipTests package && cp target/login-app-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 hrms
COPY --from=build /app.jar /app/app.jar
USER hrms
# UTC for the JVM and its database sessions; the app works out India dates itself.
ENV TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
