FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY --from=build /app/target/*.jar app.jar

# Run as an unprivileged user (least privilege — audit finding: container was
# running as root). Same fixed UID/GID convention as the other MoiPay services.
RUN addgroup -g 1001 appuser && \
    adduser -D -u 1001 -G appuser appuser && \
    chown -R appuser:appuser /app
USER appuser

EXPOSE 8087
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=70.0", \
  "-XX:InitialRAMPercentage=50.0", \
  "-XX:TieredStopAtLevel=1", \
  "-jar", "app.jar"]
