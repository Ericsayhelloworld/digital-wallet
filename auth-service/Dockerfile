# ---- build stage: compile the jar with Maven ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -Dmaven.test.skip=true

# ---- run stage: only the Java runtime and the jar ----
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/target/*.jar app.jar
# OPS-9: not root
USER app
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
