# atlas-catalogo: imagen de la aplicación. Dos etapas: compila con JDK 25 y ejecuta con un JRE 25.
# No lleva secretos: la configuración entra por variables de entorno en tiempo de ejecución (ver compose.yaml).

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Primero lo que cambia poco, para que Docker reutilice la capa de dependencias.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --console=plain dependencies > /dev/null

COPY src src
# Los tests se corren aparte (necesitan Testcontainers); acá solo se arma el ejecutable.
RUN ./gradlew --no-daemon --console=plain bootJar -x test \
    && cp build/libs/*-SNAPSHOT.jar /workspace/app.jar

FROM eclipse-temurin:25-jre
# curl solo para el healthcheck del compose (la imagen base no lo trae).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system atlas && useradd --system --gid atlas --no-create-home atlas
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER atlas
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
