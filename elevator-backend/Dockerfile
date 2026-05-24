# ── Stage 1: Build ──────────────────────────────────────────────────────────
# Build context is the repo ROOT (Railway uses root even when Dockerfile is
# inside a subdirectory). All COPY paths must be relative to repo root.
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Copy Maven wrapper + pom first (layer cache — only re-downloads on pom change)
COPY elevator-backend/pom.xml .
COPY elevator-backend/mvnw .
COPY elevator-backend/.mvn .mvn

# Download dependencies (cached unless pom.xml changes)
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

# Copy source and build
COPY elevator-backend/src ./src
RUN ./mvnw package -DskipTests -q

# ── Stage 2: Run ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

# Railway injects PORT env var
ENV SERVER_PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
