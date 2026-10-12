# atlas-catalogo

Servicio backend (Java + Spring Boot) responsable de mantener la copia local autoritativa de categorías, profesionales y horarios semanales, sincronizada contra el servicio central de la cátedra mediante snapshot REST y actualizaciones incrementales vía Redis y Kafka. Es la única fuente local vigente para búsqueda y filtrado de profesionales, consumida por hermes-app y cronos-turnos mediante un contrato propio protegido con JWT, nunca por acceso directo a su base de datos.

Documentación, contratos y plantillas: [alejandria-docs](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/tree/main/alejandria-docs).

## Arquitectura

El servicio lee de la cátedra (snapshot por REST, metadata y cambios en Redis, avisos por Kafka), guarda la copia local en su propia base y expone búsqueda y agenda por una API protegida con JWT.

```text
   ┌─────────────── Cátedra ───────────────┐
   │     REST        Redis        Kafka    │
   └─────┬──────────────┬──────────────┬───┘
         │ snapshot     │ metadata y   │ CatalogUpdated
         │ completo     │ cambios      │ aviso de versión nueva
         ▼              ▼              ▼
   ┌───────────────────────────────────────┐
   │             atlas-catalogo            │
   │   sync completa  ·  sync incremental  │
   │ búsqueda y filtros sobre datos locales│
   └─────┬─────────────────────────────┬───┘
         │                             │ API protegida con JWT
         ▼                             ├───────► hermes-app (búsqueda)
   PostgreSQL propia                   └───────► cronos-turnos (agenda)
```

Vista de conjunto de los cuatro repos: [alejandria-docs](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/tree/main/alejandria-docs).

## Configuración

Las variables de entorno se declaran en `.env.example` (versionado, con placeholders). Para trabajar en local:

```bash
cp .env.example .env   # .env está ignorado por git
```

Completar los valores con la respuesta de la cuenta técnica de la cátedra. Los dos backends comparten el mismo set de variables `CATEDRA_*`. Nunca commitear `.env`, tokens ni IPs. Convención completa en `alejandria-docs`.

## Base de datos local

PostgreSQL con Docker Compose. Requiere las variables `ATLAS_DB_*` del `.env`.

```bash
docker compose up -d      # levanta la base (puerto 5433 en localhost)
docker compose ps         # el estado debe pasar a "healthy"
docker compose down       # apaga y conserva los datos (-v los borra)
```

La base solo escucha en `127.0.0.1`. No usar H2, SQLite ni bases embebidas. Las migraciones de este servicio son propias y no tocan la base del otro.

## Requisitos

- JDK 25 (el build usa un toolchain de Java 25).
- Docker (o Podman) para la base local y para los tests con Testcontainers.
- No hace falta instalar Gradle: se usa el wrapper (`./gradlew`).

## Ejecutar

```bash
cp .env.example .env      # una sola vez; completar ATLAS_DB_PASSWORD
docker compose up -d      # base local
./gradlew bootRun         # arranca la aplicación
curl localhost:8080/actuator/health   # {"status":"UP", ...}
```

La aplicación lee el `.env` local (`spring.config.import`) o, si no existe, las variables de entorno. La base se conecta con `ATLAS_DB_*`; el único endpoint de Actuator expuesto es `/actuator/health`. El esquema lo crea Flyway al arrancar, desde las migraciones de `src/main/resources/db/migration` (`V1__esquema_catalogo.sql`: `professional_category`, `professional`, `weekly_schedule` y `sync_state`, según el modelo de datos de `alejandria-docs`). Hibernate corre con `ddl-auto=validate`: si una entidad no coincide con el esquema, la aplicación no arranca. Las entidades están en `domain` y los repositorios en `application.repository`.

### Cliente REST de la cátedra

La aplicación pide el snapshot del catálogo a la cátedra (`GET /api/synchronization/snapshot`) con el JWT técnico como `Bearer`. Sin las dos variables obligatorias no arranca y el mensaje dice cuál falta:

| Variable | Obligatoria | Descripción |
|---|---|---|
| `CATEDRA_REST_BASE_URL` | sí | URL base de la API de la cátedra (`http` o `https`). |
| `CATEDRA_ID_TOKEN` | sí | JWT técnico de la cuenta de la cátedra. No se escribe en logs ni mensajes. Si vence, se rota en el `.env`. |
| `CATEDRA_REST_CONNECT_TIMEOUT` | no | Timeout de conexión. Por defecto `3s`. |
| `CATEDRA_REST_READ_TIMEOUT` | no | Timeout de lectura. Por defecto `30s`: el snapshot es una sola respuesta que puede ser grande. |
| `CATEDRA_REST_MAX_RETRIES` | no | Reintentos tras el primer intento. Por defecto `2`, con espera de 500 ms que se duplica hasta 5 s. |

Solo se reintenta ante fallas transitorias (error de red, timeout y `503`). Un `401` o `403` indica que el token es inválido o venció y no se reintenta. No se siguen redirecciones, para no reenviar el token a otro destino. El resto de las variables `CATEDRA_*` (Redis y Kafka) las declara cada cliente cuando se implementa.

### Cliente Redis de la cátedra

La aplicación lee de Redis lo que la cátedra publica para la sincronización incremental: la metadata de versiones (`catedra:sync:metadata`), los cambios de cada versión (`catedra:sync:changes:{versión}`) y el estado vigente de las entidades afectadas (los Hashes `professional-categories`, `professionals` y `weekly-schedules`). Solo hace lecturas puntuales sobre `catedra:sync:*`; nunca escribe ni toca `catedra:holds:*` ni el namespace privado. El cliente entrega los datos tal cual: decidir qué hacer con ellos es tarea de la sincronización incremental. Con las cuatro variables obligatorias faltantes no arranca y el mensaje dice cuál falta; la conexión es perezosa, así que la aplicación arranca aunque Redis no responda (y `/actuator/health` no incluye a Redis, para que una caída de la cátedra no marque al servicio como caído).

| Variable | Obligatoria | Descripción |
|---|---|---|
| `CATEDRA_REDIS_HOST` | sí | Host de Redis de la cátedra. |
| `CATEDRA_REDIS_PORT` | sí | Puerto (1 a 65535). |
| `CATEDRA_REDIS_USERNAME` | sí | Usuario ACL de la integración técnica. |
| `CATEDRA_REDIS_PASSWORD` | sí | Contraseña. No se escribe en logs ni mensajes. Si se rota, se cambia en el `.env`. |
| `CATEDRA_REDIS_CONNECT_TIMEOUT` | no | Timeout de conexión. Por defecto `3s`. |
| `CATEDRA_REDIS_COMMAND_TIMEOUT` | no | Timeout de cada comando. Por defecto `5s`. |
| `CATEDRA_REDIS_MAX_RETRIES` | no | Reintentos tras el primer intento. Por defecto `2`, con espera de 200 ms que se duplica hasta 2 s. |

Solo se reintenta ante fallas de conexión y de tiempo. Una credencial o un permiso rechazado (`WRONGPASS`, `NOAUTH`, `NOPERM`) no se reintenta. No se usa TLS: la referencia de integración no lo contempla. Las demás variables `CATEDRA_REDIS_*` del `.env.example` (los namespaces) las usará quien escriba en el namespace privado, que este cliente no hace.

### Sincronización completa al arrancar

Con la base vacía (sin versión local en `sync_state`), al terminar de arrancar la aplicación ejecuta `ApplySnapshot` con el motivo `BASE_VACIA`: pide el snapshot a la cátedra y lo aplica en una sola transacción, con la fila de `sync_state` tomada con `FOR UPDATE` (dos corridas no se pisan). Lo que ya no viene en el snapshot se deshabilita, nunca se borra, y la versión local queda igual a la del snapshot. Si algo falla, la transacción se revierte, `sync_state` queda en `ERROR` con un código (`CATEDRA_NO_DISPONIBLE`, `CATEDRA_CREDENCIAL_RECHAZADA`, `CATEDRA_RESPUESTA_INVALIDA` o `SNAPSHOT_NO_APLICABLE`) y la aplicación sigue arrancada. Con una versión local no se pide nada.

| Variable | Obligatoria | Descripción |
|---|---|---|
| `ATLAS_SYNC_ON_STARTUP` | no | `true` por defecto. Con `false` la aplicación no pide el snapshot al arrancar (propiedad `atlas.sync.on-startup`). Los tests de contexto la apagan. |

## Tests

```bash
./gradlew build           # compila, corre los tests y las reglas de arquitectura
```

- `AtlasCatalogoApplicationTests`: arranca la aplicación contra PostgreSQL con Testcontainers y consulta `/actuator/health`. Necesita un motor de contenedores.
- `PersistenciaCatalogoTest` y `CandadoSyncStateTest`: esquema, entidades, *upserts* y candado contra PostgreSQL real.
- `CatedraRestSnapshotSourceTest` y `CatedraPropertiesTest`: el cliente REST contra un servidor local que simula a la cátedra (snapshot, errores, reintentos, timeouts) y la validación de las variables al arrancar. No llaman a la cátedra real.
- `CatedraRedisChangeSourceTest` y `CatedraRedisPropertiesTest`: el cliente Redis contra un Redis real (Testcontainers) con un usuario ACL que solo lee `catedra:sync:*` (metadata, cambios, entidades, datos inválidos, Redis caído, credencial y permisos rechazados) y la validación de las variables al arrancar.
- `SincronizacionCompletaTest` y `ArranqueConSincronizacionTest`: la sincronización completa contra PostgreSQL real con la cátedra simulada (inicialización, reconstrucción, bajas, repetición, *rollback*, errores por código, corridas en paralelo y arranque con y sin versión local).
- `ArquitecturaTest`: reglas de dependencia de ArchUnit (ADR 0003).

Con Podman rootless hay que apuntar Testcontainers al socket y desactivar Ryuk:

```bash
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

## Estructura de paquetes

Paquete base `ar.edu.um.atlas`, según el [ADR 0003](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/adr/0003-arquitectura.md):

| Paquete | Contenido |
|---|---|
| `domain` | Entidades JPA y reglas puras; sin Spring |
| `application` | Casos de uso (`sync`); `port.out` (puertos hacia la cátedra: snapshot y cambios) y `repository` (Spring Data) |
| `adapters.in` | Arranque (`startup`), web y Kafka |
| `adapters.out` | Clientes REST (`rest`) y Redis (`redis`) de la cátedra |
| `config` | Configuración de Spring |
