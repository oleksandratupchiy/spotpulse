# Build context: repository root. Only the PUBLIC analytics ID is a build argument; never pass credentials.
FROM node:24-bookworm-slim AS frontend
WORKDIR /build/spotpulse-frontend
COPY spotpulse-frontend/package.json spotpulse-frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY spotpulse-frontend/index.html spotpulse-frontend/tsconfig.json spotpulse-frontend/vite.config.ts ./
COPY spotpulse-frontend/src/ ./src/
ARG VITE_GA_MEASUREMENT_ID=""
RUN VITE_GA_MEASUREMENT_ID="$VITE_GA_MEASUREMENT_ID" npm run build:deployment

FROM eclipse-temurin:25-jdk AS backend
WORKDIR /build/spotpulse-backend
COPY spotpulse-backend/.mvn/ .mvn/
COPY spotpulse-backend/mvnw spotpulse-backend/pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY spotpulse-backend/src/main/ src/main/
COPY --from=frontend /build/spotpulse-frontend/dist/ /build/spotpulse-frontend/dist/
# CI runs the complete tests separately; this stage packages only production sources and the built SPA.
RUN ./mvnw -B -ntp -Pspa -Dmaven.test.skip=true package \
    && jar tf target/spotpulse-0.0.1-SNAPSHOT.jar | grep -q 'BOOT-INF/classes/static/app/index.html'

FROM eclipse-temurin:25-jre AS runtime
RUN groupadd --gid 10001 spotpulse \
    && useradd --uid 10001 --gid spotpulse --create-home --home-dir /home/spotpulse spotpulse
WORKDIR /app
COPY --from=backend --chown=spotpulse:spotpulse /build/spotpulse-backend/target/spotpulse-0.0.1-SNAPSHOT.jar /app/app.jar
ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:InitialRAMPercentage=20.0"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
