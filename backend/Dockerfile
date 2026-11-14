
# Etapa 1 - Compilar o MAIA
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B clean package -DskipTests

# Etapa 2 - Executar o backend
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
