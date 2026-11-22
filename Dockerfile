# 1) build the React dashboard
FROM node:20-alpine AS web
WORKDIR /web
COPY dashboard/package*.json ./
RUN npm ci
COPY dashboard/ ./
RUN npm run build

# 2) fetch Guice, compile and test the Java service
FROM maven:3.9-eclipse-temurin-21 AS jvm
WORKDIR /app
COPY pom.xml ./
COPY scripts/fetch-deps.sh ./scripts/
RUN ./scripts/fetch-deps.sh
COPY src ./src
COPY build.sh ./
RUN ./build.sh && java -cp "out/main:out/test:lib/*" dev.pointledger.TestMain

# 3) small runtime image
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=jvm /app/out/main ./out/main
COPY --from=jvm /app/lib ./lib
COPY --from=web /web/dist ./dashboard/dist
ENV PORT=8080 COMMAND_LOG=/data/commands.log FSYNC=true
VOLUME /data
EXPOSE 8080
CMD ["java", "-XX:MaxRAMPercentage=75", "-cp", "out/main:lib/*", "dev.pointledger.App"]
