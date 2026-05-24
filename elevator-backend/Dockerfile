# ── Stage 1: Build ──────────────────────────────────────────────────────────
# Use the official Maven + JDK image — no need for mvnw wrapper
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Copy pom first (layer cache — only re-downloads deps when pom changes)
COPY elevator-backend/pom.xml .

# Download dependencies (cached unless pom.xml changes)
RUN mvn dependency:go-offline -q

# Copy source and build
COPY elevator-backend/src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Run ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

# Railway injects PORT env var
ENV SERVER_PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
