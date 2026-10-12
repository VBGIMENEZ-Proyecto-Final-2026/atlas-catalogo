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

### Sincronización con la cátedra

El caso de uso `SyncCatalog` mantiene la copia local al día. Compara la versión local (`sync_state.local_version`) con la metadata de Redis y un planificador (función pura, sin Spring) decide qué hacer:

| Situación | Plan |
|---|---|
| No hay versión local (base vacía) | `Snapshot(BASE_VACIA)`; no lee Redis |
| Metadata ausente o incoherente | `Snapshot(NO_VERIFICABLE)` |
| Versión local mayor que la vigente | `Snapshot(LOCAL_ADELANTADA)` |
| Versión local igual a la vigente | `Noop` |
| Versión local menor que la más antigua disponible | `Snapshot(FUERA_DE_VENTANA)` |
| En otro caso | `Incremental`: aplica de `local + 1` a la vigente |

- **Snapshot.** `ApplySnapshot` pide el snapshot a la cátedra y lo aplica en una sola transacción, con la fila de `sync_state` tomada con `FOR UPDATE` (dos corridas no se pisan). Lo que ya no viene se deshabilita, nunca se borra, y la versión local queda igual a la del snapshot.
- **Incremental.** `ApplyIncremental` aplica cada versión en su propia transacción: lee `changes:{v}` y el estado vigente de cada ID afectado en Redis, hace *upsert* en orden (categorías, profesionales, horarios) y avanza la versión local solo si todo salió bien. Repetir una versión deja la copia igual. Un ID de `changes` que ya no está en Redis se deshabilita localmente (y se avisa en el log); con un segundo ID ausente en la corrida, falta `changes:{v}`, una referencia rota o datos de Redis que no se pueden interpretar, la versión en curso se revierte y se reconstruye con un snapshot.
- **Fallas de integración.** Si Redis o la cátedra no responden o rechazan la credencial, no se escala a snapshot: `sync_state` queda en `ERROR` con un código (`CATEDRA_NO_DISPONIBLE`, `CATEDRA_CREDENCIAL_RECHAZADA`, `CATEDRA_RESPUESTA_INVALIDA`, `SNAPSHOT_NO_APLICABLE` o `INCREMENTAL_NO_APLICABLE`), la copia queda como estaba y la siguiente verificación lo reintenta sola. Un éxito posterior limpia el error.
- **Disparadores.** Al terminar de arrancar la aplicación corre la sincronización (con la base vacía arma la copia; con versión local recoge lo que se perdió mientras estaba apagada), después una verificación periódica de la versión, que además recupera avisos perdidos, y el aviso `CatalogUpdated` de Kafka (ver abajo). Si la cátedra falla en el arranque, la aplicación arranca igual. Una verificación sin cambios se registra en `sync_state` como `NOOP`.

| Variable | Obligatoria | Descripción |
|---|---|---|
| `ATLAS_SYNC_ON_STARTUP` | no | `true` por defecto. Con `false` la aplicación no sincroniza al arrancar (propiedad `atlas.sync.on-startup`). Los tests de contexto la apagan. |
| `ATLAS_SYNC_PERIODIC_CHECK` | no | `true` por defecto. Con `false` no hay verificación periódica (`atlas.sync.periodic-check`). Los tests de contexto la apagan. |
| `ATLAS_SYNC_CHECK_INTERVAL` | no | Espera entre verificaciones, `60s` por defecto. Es una espera fija entre el fin de una corrida y el comienzo de la siguiente: nunca se superponen. |
| `ATLAS_SYNC_MAX_VERSIONS_PER_RUN` | no | Máximo de versiones que se aplican en una corrida, `1000` por defecto; si faltan más, la siguiente verificación continúa. |
| `ATLAS_SYNC_KAFKA_ENABLED` | no | `true` por defecto. Con `false` no se crea el consumidor de Kafka y no se exigen sus variables (`atlas.sync.kafka.enabled`). Los tests de contexto lo apagan. |

### Consumidor de Kafka (aviso de versión nueva)

La cátedra publica `CatalogUpdated` en el topic del catálogo cada vez que hay una versión nueva. El consumidor lo usa como un disparador más: cada evento ejecuta el mismo caso de uso de sincronización, así que una versión nueva se aplica en segundos en vez de esperar la próxima verificación periódica. El evento **solo avisa**: el plan sale de comparar la versión local con la metadata de Redis, nunca del `newVersion` del evento. Por eso un evento duplicado, atrasado o adelantado termina en `Noop` y no se guarda ningún `eventId`.

- **Confirmación del *offset*.** Manual y un mensaje por vez: se confirma solo después de que la corrida terminó sin excepción.
- **Mensajes corruptos.** Un mensaje sin valor, que no es un JSON interpretable, de otro `eventType` o sin `newVersion` se registra (sin su cuerpo) y se descarta con su *offset* confirmado: no bloquea el consumo y la verificación periódica cubre el hueco. Los campos desconocidos se ignoran.
- **Si la sincronización falla** (Redis o la cátedra caídos, credencial rechazada), el mensaje se reintenta con espera creciente y, al agotar los intentos, se descarta con el *offset* confirmado: el error ya quedó en `sync_state` y la verificación periódica lo reintenta sola, así que no se pierde nada y el consumidor no se traba.
- **Si Kafka no responde**, la aplicación arranca igual: si los servidores existen pero no contestan, el cliente se conecta en segundo plano; si ni siquiera se resuelven, se reintenta arrancar el consumidor cada `CATEDRA_KAFKA_START_RETRY_INTERVAL`. El consumo se reanuda solo.
- **Grupo nuevo.** Empieza desde lo último (`latest`): el arranque ya sincroniza la copia. Con un grupo que ya tiene *offset* se retoma desde ahí y se recuperan los avisos que llegaron con la aplicación apagada.

| Variable | Obligatoria | Descripción |
|---|---|---|
| `CATEDRA_KAFKA_BOOTSTRAP_SERVERS` | sí (con el consumidor encendido) | Servidores de Kafka de la cátedra. |
| `CATEDRA_KAFKA_CATALOG_TOPIC` | sí (con el consumidor encendido) | Topic `catedra.catalog.<groupId>`. |
| `CATEDRA_KAFKA_CONSUMER_GROUP_ID` | sí (con el consumidor encendido) | Consumer group asignado a la integración. |
| `CATEDRA_KAFKA_MAX_ATTEMPTS` | no | Intentos totales por mensaje antes de descartarlo, `3` por defecto, con espera de 1 s que se duplica. |
| `CATEDRA_KAFKA_START_RETRY_INTERVAL` | no | Espera entre intentos de arrancar el consumidor si los servidores no se resuelven, `10s` por defecto. |

No se configura autenticación: la cátedra no documenta una para Kafka. Las demás variables `CATEDRA_KAFKA_*` del `.env.example` (los topics de reservas) no las usa este servicio.

## Tests

```bash
./gradlew build           # compila, corre los tests y las reglas de arquitectura
```

- `AtlasCatalogoApplicationTests`: arranca la aplicación contra PostgreSQL con Testcontainers y consulta `/actuator/health`. Necesita un motor de contenedores.
- `PersistenciaCatalogoTest` y `CandadoSyncStateTest`: esquema, entidades, *upserts* y candado contra PostgreSQL real.
- `CatedraRestSnapshotSourceTest` y `CatedraPropertiesTest`: el cliente REST contra un servidor local que simula a la cátedra (snapshot, errores, reintentos, timeouts) y la validación de las variables al arrancar. No llaman a la cátedra real.
- `CatedraRedisChangeSourceTest` y `CatedraRedisPropertiesTest`: el cliente Redis contra un Redis real (Testcontainers) con un usuario ACL que solo lee `catedra:sync:*` (metadata, cambios, entidades, datos inválidos, Redis caído, credencial y permisos rechazados) y la validación de las variables al arrancar.
- `CatalogUpdatedParserTest`, `ResilientConsumerLifecycleTest`, `CatedraKafkaPropertiesTest`, `ConsumidorKafkaTest` y `ConsumidorKafkaInaccesibleTest`: el consumidor de Kafka (interpretación del mensaje, arranque resiliente, variables, y contra un Kafka real con PostgreSQL real: evento nuevo, duplicados, atrasados, mensajes corruptos, falla de la sincronización con reintentos acotados y Kafka inaccesible).
- `SyncPlannerTest`: una prueba por cada fila de la tabla del planificador y sus bordes, sin Spring ni base de datos.
- `SincronizacionIncrementalTest`, `SincronizacionConRedisRealTest` y `VerificacionPeriodicaTest`: la sincronización incremental contra PostgreSQL real con la cátedra simulada (versiones en orden, idempotencia, bajas, escaladas a snapshot, *rollback*, fallas de Redis y de credencial, corridas en paralelo, arranque y verificación periódica con el planificador de tareas), y de punta a punta con un Redis real.
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
| `application` | Casos de uso (`sync`, con el planificador puro en `sync.plan`); `port.out` (puertos hacia la cátedra: snapshot y cambios) y `repository` (Spring Data) |
| `adapters.in` | Disparadores de arranque (`startup`), periódico (`schedule`) y por aviso de Kafka (`kafka`); web |
| `adapters.out` | Clientes REST (`rest`) y Redis (`redis`) de la cátedra |
| `config` | Configuración de Spring |
