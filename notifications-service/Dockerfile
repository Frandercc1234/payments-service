# ---- Etapa 1: compilar (imagen con JDK + Maven wrapper) ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Primero solo lo necesario para resolver dependencias: Docker cachea esta capa mientras el pom no cambie.
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Reintentos: la primera llamada a mvnw descarga Maven y las dependencias; un 404/timeout pasajero del
# servidor de descargas no debe tumbar el build. Pausa creciente entre intentos (5 s, 10 s, ... 25 s).
RUN chmod +x mvnw && \
    for i in 1 2 3 4 5; do \
      ./mvnw -B -q dependency:go-offline && exit 0; \
      echo "Descarga fallida (intento $i de 5), reintentando..." >&2; \
      sleep $((i * 5)); \
    done; \
    exit 1

COPY src src
# Las pruebas corren en CI (necesitan Docker para Testcontainers, que no existe dentro de este build).
RUN ./mvnw -B -q -DskipTests package && mv target/notifications-service-*.jar target/app.jar

# ---- Etapa 2: ejecutar (solo JRE, sin compilador ni código fuente) ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# No correr como root.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /workspace/target/app.jar app.jar

EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "app.jar"]
