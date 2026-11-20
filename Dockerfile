# ---- Etapa 1: compilar (imagen con JDK + Maven wrapper) ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Primero solo lo necesario para resolver dependencias: Docker cachea esta capa y, mientras el pom
# no cambie, no vuelve a descargar medio internet en cada build.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
# Las pruebas corren en CI (necesitan Docker para Testcontainers, que no existe dentro de este build).
RUN ./mvnw -B -q -DskipTests package && mv target/payments-service-*.jar target/app.jar

# ---- Etapa 2: ejecutar (solo JRE, sin compilador ni código fuente) ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# No correr como root: si alguien explota la app, no tiene privilegios sobre el contenedor.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /workspace/target/app.jar app.jar

EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "app.jar"]
