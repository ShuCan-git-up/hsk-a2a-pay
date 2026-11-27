# Build context: repository root. The frontend is copied into Spring's static resources at build time.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
RUN mvn -f backend/pom.xml -B -q dependency:go-offline
COPY backend/src backend/src
COPY frontend frontend
RUN mvn -f backend/pom.xml -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache wget && addgroup -S app && adduser -S app -G app
COPY --from=build /src/backend/target/hsk-a2a-pay.jar /app/app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-Xms96m -Xmx224m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=8 CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
