# syntax=docker/dockerfile:1

# ---- build: compile, generate jOOQ classes from the Flyway migrations, package the jar ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Resolve dependencies in their own layer so code changes don't re-download them.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
# Runs the unit tests (a failure stops the build), then packages. The integration tests are
# left out: they start Postgres with Docker, which is not available inside an image build.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DexcludedGroups=integration package \
	&& cp target/seat_selection-*.jar /app.jar

# ---- run: JRE only, non-root ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
# PORT is set by Render; 8080 elsewhere. Size the heap from the container's memory limit.
ENV PORT=8080 \
	JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
