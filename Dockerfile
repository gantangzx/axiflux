# syntax=docker/dockerfile:1

# ---- Build stage: full JDK + Maven, compile all modules ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# Resolve dependencies first for better layer caching
COPY pom.xml ./
COPY reaxon-core/pom.xml reaxon-core/
COPY reaxon-eval/pom.xml reaxon-eval/
COPY axiflux-storage/pom.xml axiflux-storage/
COPY axiflux-spring/pom.xml axiflux-spring/
COPY axiflux-registry/pom.xml axiflux-registry/
COPY axiflux-app/pom.xml axiflux-app/
RUN mvn -q -B -DskipTests dependency:go-offline || true
# Build
COPY . .
RUN mvn -q -B -DskipTests package

# ---- Runtime stage: JRE only ----
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
COPY --from=build /build/axiflux-app/target/axiflux-app-*.jar /app/axiflux.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/axiflux.jar"]
