FROM node:24.19.0-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43 AS web
WORKDIR /web
COPY apps/web/package*.json ./
RUN npm ci --no-audit --no-fund
COPY apps/web/ ./
RUN npm run build

FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /src
COPY services/api/pom.xml ./
COPY services/api/src ./src
COPY templates/cv/*.docx ./src/main/resources/templates/cv/
COPY --from=web /web/dist ./src/main/resources/static
COPY infrastructure/Healthcheck.java ./Healthcheck.java
RUN javac Healthcheck.java && mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-jammy@sha256:dff6eb46527bd293a3c06bbbe9e64e8c9f91f01905b5abbdad31761c03059d2c
WORKDIR /app
COPY --from=build /src/target/skillhunters-api-0.1.0-SNAPSHOT.jar app.jar
COPY --from=build /src/Healthcheck.class /app/Healthcheck.class
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
