FROM node:24.19.0-alpine AS web
WORKDIR /web
COPY apps/web/package*.json ./
RUN npm ci --no-audit --no-fund
COPY apps/web/ ./
RUN npm run build

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY services/api/pom.xml ./
COPY services/api/src ./src
COPY --from=web /web/dist ./src/main/resources/static
COPY infrastructure/Healthcheck.java ./Healthcheck.java
RUN javac Healthcheck.java && mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /src/target/skillhunters-api-0.1.0-SNAPSHOT.jar app.jar
COPY --from=build /src/Healthcheck.class /app/Healthcheck.class
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
