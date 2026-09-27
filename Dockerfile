# syntax=docker/dockerfile:1

# ---- Build stage: full JDK + Maven, compile all modules ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# Resolve dependencies first for better layer caching
COPY pom.xml ./
COPY tianshu-core/pom.xml tianshu-core/
COPY tianshu-eval/pom.xml tianshu-eval/
COPY tianshu-storage/pom.xml tianshu-storage/
COPY tianshu-spring/pom.xml tianshu-spring/
COPY tianshu-registry/pom.xml tianshu-registry/
COPY tianshu-app/pom.xml tianshu-app/
RUN mvn -q -B -DskipTests dependency:go-offline || true
# Build
COPY . .
RUN mvn -q -B -DskipTests package

# ---- Runtime stage: JRE only ----
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
COPY --from=build /build/tianshu-app/target/tianshu-app-*.jar /app/tianshu.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/tianshu.jar"]
