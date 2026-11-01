# atlas-catalogo

Servicio backend (Java + Spring Boot) responsable de mantener la copia local autoritativa de categorías, profesionales y horarios semanales, sincronizada contra el servicio central de la cátedra mediante snapshot REST y actualizaciones incrementales vía Redis y Kafka. Es la única fuente local vigente para búsqueda y filtrado de profesionales, consumida por hermes-app y cronos-turnos mediante un contrato propio protegido con JWT, nunca por acceso directo a su base de datos.

## Estado

Versión básica de la Isla 1 · Eolia. **Hoy hace:**

- Guarda el catálogo en su propia base PostgreSQL (migraciones con Flyway).
- Lo inicializa y lo reconstruye desde el snapshot REST de la cátedra.
- Lo mantiene al día con los cambios incrementales de Redis, avisado por Kafka y con una verificación periódica.
- Se recupera solo de las fallas de la cátedra, de Redis y de Kafka, sin dejar la copia a medias.
- Informa su estado en `GET /api/sync/status` (apagado por defecto hasta que exista la seguridad base).
- Busca profesionales sobre la copia local por categoría, nombre, estado habilitado y disponibilidad, con detalle y horarios (`GET /api/professionals`; apagado por defecto hasta que exista la seguridad base).
- Permite autenticar la conexión a Kafka (SASL) por configuración, sin tocar código.

**Todavía no hace:** la seguridad de la API (JWT) ni la API de lectura para `cronos-turnos`. El único endpoint siempre expuesto es `GET /actuator/health`; la búsqueda y el estado de la sincronización están apagados por defecto. El avance y lo que falta están en el [backlog de la isla](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/backlog/isla-1-eolia.md).

## Arquitectura

El servicio lee de la cátedra (snapshot por REST, metadata y cambios en Redis, avisos por Kafka), guarda la copia local en su propia base y expone búsqueda y agenda por una API protegida con JWT (esta parte todavía no está implementada).

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

## Requisitos

- **JDK 25** (el build usa un toolchain de Java 25).
- **Docker o Podman**, con Compose, para la base local y para los tests (Testcontainers levanta PostgreSQL, Redis y Kafka).
- No hace falta instalar Gradle: se usa el wrapper (`./gradlew`).
- Para ejecutar contra la cátedra real, la cuenta técnica de la cátedra (ver [Configuración](#configuración)). Para solo compilar y correr los tests no hace falta.

## Inicio rápido desde cero

```bash
git clone https://github.com/VBGIMENEZ-Proyecto-Final-2026/atlas-catalogo.git
cd atlas-catalogo
git checkout dev                 # rama de integración

cp .env.example .env             # una sola vez; .env está ignorado por git
# Editar .env: ATLAS_DB_PASSWORD y los valores CATEDRA_* de la cuenta técnica

docker compose up -d             # base local; esperar a que figure "healthy" (docker compose ps)
./gradlew build                  # compila, corre los tests y las reglas de arquitectura
./gradlew bootRun                # arranca la aplicación (puerto 8080)

curl localhost:8080/actuator/health   # {"status":"UP", ...}
```

Al arrancar con la base vacía, la aplicación pide el snapshot a la cátedra y arma la copia; con una versión local, verifica si hay versiones nuevas. Para comprobarlo:

```bash
docker exec atlas-db psql -U atlas -d atlas \
  -c "SELECT local_version, status, last_result, last_snapshot_reason FROM sync_state;"
```

`local_version` debe quedar con un número, `status` en `IDLE` y `last_result` en `SNAPSHOT` (primera carga) o `NOOP` (copia al día). Si algo falló, `last_error_code` y `last_error_message` dicen qué.

**Con Podman rootless** hay que apuntar a su socket, tanto para `docker compose` como para los tests (Testcontainers), y desactivar Ryuk:

```bash
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

**Apagar la base:** `docker compose down` conserva los datos; `docker compose down -v` los borra. La base solo escucha en `127.0.0.1` (puerto 5433 por defecto). No usar H2, SQLite ni bases embebidas: las migraciones de este servicio son propias y no tocan la base del otro.

## Configuración

Las variables de entorno se declaran en `.env.example` (versionado, con placeholders). La aplicación lee el `.env` local (`spring.config.import`) o, si no existe, las variables de entorno reales. Los valores `CATEDRA_*` salen de la respuesta de la cuenta técnica de la cátedra y son los mismos en los dos backends. **Nunca commitear `.env`, tokens ni IPs.** Si falta una variable obligatoria, la aplicación no arranca y el mensaje dice cuál. Las variables del `.env.example` que no aparecen acá (`CATEDRA_GROUP_ID`, los namespaces de Redis y los topics de reservas) no las usa este servicio.

### Obligatorias

| Variable | Descripción |
|---|---|
| `ATLAS_DB_PASSWORD` | Contraseña de la base local. |
| `CATEDRA_REST_BASE_URL` | URL base de la API de la cátedra (`http` o `https`). |
| `CATEDRA_ID_TOKEN` | JWT técnico de la cátedra. No se escribe en logs ni mensajes; si vence, se rota en el `.env`. |
| `CATEDRA_REDIS_HOST`, `CATEDRA_REDIS_PORT`, `CATEDRA_REDIS_USERNAME`, `CATEDRA_REDIS_PASSWORD` | Redis de la cátedra (usuario ACL de la integración, solo lectura). |
| `CATEDRA_KAFKA_BOOTSTRAP_SERVERS`, `CATEDRA_KAFKA_CATALOG_TOPIC`, `CATEDRA_KAFKA_CONSUMER_GROUP_ID` | Kafka de la cátedra. Solo se exigen con el consumidor encendido (`ATLAS_SYNC_KAFKA_ENABLED`). |

### Opcionales (tienen valor por defecto)

| Variable | Por defecto | Descripción |
|---|---|---|
| `ATLAS_DB_HOST`, `ATLAS_DB_PORT`, `ATLAS_DB_NAME`, `ATLAS_DB_USER` | `localhost`, `5433`, `atlas`, `atlas` | Conexión a la base local. |
| `CATEDRA_REST_CONNECT_TIMEOUT` | `3s` | Timeout de conexión del cliente REST. |
| `CATEDRA_REST_READ_TIMEOUT` | `30s` | Timeout de lectura: el snapshot es una sola respuesta que puede ser grande. |
| `CATEDRA_REST_MAX_RETRIES` | `2` | Reintentos tras el primer intento (espera de 500 ms que se duplica hasta 5 s). |
| `CATEDRA_REDIS_CONNECT_TIMEOUT` | `3s` | Timeout de conexión a Redis. |
| `CATEDRA_REDIS_COMMAND_TIMEOUT` | `5s` | Timeout de cada comando de Redis. |
| `CATEDRA_REDIS_MAX_RETRIES` | `2` | Reintentos tras el primer intento (espera de 200 ms que se duplica hasta 2 s). |
| `CATEDRA_KAFKA_MAX_ATTEMPTS` | `3` | Intentos totales por mensaje antes de descartarlo (espera de 1 s que se duplica). |
| `CATEDRA_KAFKA_START_RETRY_INTERVAL` | `10s` | Espera entre intentos de arrancar el consumidor si los servidores no se resuelven. |
| `ATLAS_SYNC_ON_STARTUP` | `true` | Sincroniza al terminar de arrancar. |
| `ATLAS_SYNC_PERIODIC_CHECK` | `true` | Verificación periódica de la versión. |
| `ATLAS_SYNC_CHECK_INTERVAL` | `60s` | Espera entre verificaciones (fija: nunca se superponen). |
| `ATLAS_SYNC_MAX_VERSIONS_PER_RUN` | `1000` | Máximo de versiones que se aplican en una corrida; si faltan más, la siguiente verificación continúa. |
| `ATLAS_SYNC_KAFKA_ENABLED` | `true` | Consumidor del aviso `CatalogUpdated`. Con `false` no se crea y no se exigen sus variables. |
| `ATLAS_SEARCH_ENDPOINT` | `false` | Con `true` expone la búsqueda (`GET /api/professionals`, `GET /api/professionals/{id}` y `GET /api/categories`); apagado, las rutas responden `404`. Mismo cuidado que el estado: no está protegido hasta la seguridad base. |
| `ATLAS_SYNC_STATUS_ENDPOINT` | `false` | Con `true` expone `GET /api/sync/status`; apagado, la ruta responde `404`. No encenderlo donde pueda llegar alguien que no deba ver el estado: no está protegido hasta la seguridad base. |
| `CATEDRA_KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` | Solo se admite `PLAINTEXT` o `SASL_SSL`; lo normal es no definirla y activar el perfil `kafka-sasl`. |
| `CATEDRA_KAFKA_SASL_JAAS_CONFIG` | — | Obligatoria solo con `SASL_SSL`: `org.apache.kafka.common.security.plain.PlainLoginModule required username="..." password="...";`. Solo por variable de entorno o `.env`. |

**Autenticación de Kafka (opcional).** La cátedra no documenta una y la conexión real funcionó sin credenciales dentro de la red privada, así que por defecto es `PLAINTEXT`. Si la exigiera: `SPRING_PROFILES_ACTIVE=kafka-sasl` (`SASL_SSL` con mecanismo `PLAIN`) y definir `CATEDRA_KAFKA_SASL_JAAS_CONFIG`. El perfil sin JAAS, un JAAS sin el perfil y cualquier otro protocolo (mTLS incluido) impiden el arranque con un mensaje claro. El JAAS no aparece en `toString` ni en el log del cliente. Está probado hasta la configuración que recibe el cliente, no contra un Kafka con autenticación real.

Los tests de contexto usan valores ficticios (`src/test/resources/application.properties`) y apagan los tres disparadores: **nunca llaman a la cátedra real**.

## Cómo funciona la sincronización

Un solo caso de uso, `SyncCatalog`, mantiene la copia al día. Tiene tres disparadores (el arranque, una verificación periódica y el aviso `CatalogUpdated` de Kafka) y un planificador puro que decide qué hacer comparando la versión local con la metadata de Redis:

- **Sin copia** (base vacía), o si no se puede continuar con los cambios: reconstruye con el **snapshot** REST, en una sola transacción y deshabilitando lo que ya no viene (nunca borra).
- **Atrasada:** aplica los **cambios incrementales** versión por versión, cada una en su propia transacción; la versión local avanza solo si todo salió bien.
- **Al día:** no hace nada y lo registra.
- **Si la cátedra, Redis o Kafka fallan:** no se escala a snapshot; queda el error en `sync_state` y la siguiente verificación reintenta. La copia nunca queda a medias.
- **Idempotente:** repetir un snapshot, una versión o un aviso deja la copia igual. Una sola corrida a la vez (candado en la base).

El detalle (planificador, transacciones, idempotencia, matriz de fallas y recuperación, estado y limitaciones) está en [**la estrategia de sincronización**](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/sincronizacion-atlas-catalogo.md).

## Búsqueda de profesionales

Se resuelve **solo con la copia local**: ninguna búsqueda consulta a la cátedra (una regla de ArchUnit lo comprueba: `application.search` no puede depender de los clientes ni de la sincronización). Se enciende con `ATLAS_SEARCH_ENDPOINT=true`.

| Ruta | Qué devuelve |
|---|---|
| `GET /api/professionals` | Página de profesionales con su categoría. |
| `GET /api/professionals/{id}` | Un profesional con todos sus horarios semanales (habilitados o no, cada uno con su estado), ordenados por día y hora. También devuelve a un profesional deshabilitado, con `enabled: false`. |
| `GET /api/categories` | Categorías por nombre, para elegir una al filtrar. |

Filtros de `GET /api/professionals`, todos opcionales y combinables con "y":

| Parámetro | Efecto |
|---|---|
| `categoryId` | Profesionales de esa categoría. |
| `name` | Texto contenido en "nombre apellido", sin distinguir mayúsculas (hasta 100 caracteres). No ignora tildes, y los comodines `%` y `_` se tratan como texto. |
| `enabled` | `true` o `false`: estado habilitado del profesional (filtro literal). |
| `available` | `true`: disponibles (profesional y categoría habilitados y al menos un horario semanal habilitado). `false`: los que no lo están. Es independiente de `enabled`. |
| `dayOfWeek` | `MONDAY` ... `SUNDAY`; solo junto con `available`: el horario habilitado debe ser de ese día. |
| `page`, `size` | Página desde 0 y tamaño de 1 a 100 (por defecto `0` y `20`). El orden es fijo: apellido, nombre e ID. |

"Disponible" significa que el profesional atiende, no que le queden turnos libres: eso depende de las ocupaciones de la cátedra y se resuelve en `cronos-turnos`. Un parámetro inválido responde `400` con código `BUSQUEDA_INVALIDA`, un ID inexistente `404` (`PROFESIONAL_NO_ENCONTRADO`) y la base caída `503` (`CATALOGO_NO_DISPONIBLE`), siempre como `application/problem+json` y sin repetir el valor recibido.

```bash
curl 'localhost:8080/api/professionals?name=ana&available=true&dayOfWeek=MONDAY&size=10'
```

## Base de datos

El esquema lo crea Flyway al arrancar, desde `src/main/resources/db/migration` (`V1__esquema_catalogo.sql`: `professional_category`, `professional`, `weekly_schedule` y `sync_state`, según el [modelo de datos](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/modelo-datos/atlas-catalogo.md)). Hibernate corre con `ddl-auto=validate`: si una entidad no coincide con el esquema, la aplicación no arranca.

## Tests

```bash
./gradlew build           # compila, corre los tests y las reglas de arquitectura
```

Los tests de integración usan PostgreSQL, Redis y Kafka reales en contenedores (Testcontainers): hace falta Docker o Podman. No hay integración continua configurada: `./gradlew build` se corre a mano antes de cada PR.

| Tests | Qué cubren |
|---|---|
| `AtlasCatalogoApplicationTests` | La aplicación arranca contra PostgreSQL y responde `/actuator/health`. |
| `PersistenciaCatalogoTest`, `CandadoSyncStateTest` | Esquema, entidades, *upserts* y candado contra PostgreSQL real. |
| `CatedraRestSnapshotSourceTest`, `CatedraPropertiesTest` | El cliente REST contra un servidor local que simula a la cátedra (snapshot, errores, reintentos, timeouts) y la validación de las variables. |
| `CatedraRedisChangeSourceTest`, `CatedraRedisPropertiesTest` | El cliente Redis contra un Redis real con un usuario ACL de solo lectura sobre `catedra:sync:*` (datos válidos e inválidos, Redis caído, credencial rechazada) y la validación de las variables. |
| `SyncPlannerTest` | Una prueba por cada fila de la tabla del planificador y sus bordes, sin Spring ni base de datos. |
| `SincronizacionCompletaTest`, `ArranqueConSincronizacionTest` | Snapshot: inicialización, reconstrucción, bajas, repetición, *rollback*, errores por código y arranque. |
| `SincronizacionIncrementalTest`, `SincronizacionConRedisRealTest`, `VerificacionPeriodicaTest` | Sincronización incremental: versiones en orden, idempotencia, bajas, escaladas a snapshot, fallas, corridas en paralelo, disparadores y punta a punta con Redis real. |
| `CatalogUpdatedParserTest`, `ResilientConsumerLifecycleTest`, `CatedraKafkaPropertiesTest`, `PerfilKafkaSaslTest`, `ConsumidorKafkaTest`, `ConsumidorKafkaInaccesibleTest` | El consumidor de Kafka: interpretación del mensaje, arranque resiliente, variables y, contra un Kafka real, eventos nuevos, duplicados, corruptos, fallas y Kafka inaccesible. |
| `EstadoSincronizacionTest`, `RecuperacionSincronizacionTest`, `SyncStatusControllerTest` | Estado de la sincronización (`GET /api/sync/status`, `503` si la base no responde) y falla y recuperación de punta a punta. |
| `DisponibilidadTest` | Filtro de disponibilidad: sin horarios, horarios deshabilitados, otro día y sin duplicados. |
| `BusquedaProfesionalesTest`, `ProfessionalSearchCriteriaTest` | Búsqueda contra PostgreSQL real: cada filtro y sus combinaciones, comodines, orden y paginación, detalle, categorías y validación de parámetros. |
| `ProfessionalsControllerTest`, `BusquedaApagadaPorDefectoTest` | Los parámetros, el JSON y los errores `400`, `404` y `503` de la búsqueda, y que apagada responde `404`. |
| `ArquitecturaTest` | Reglas de dependencia de ArchUnit ([ADR 0003](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/adr/0003-arquitectura.md)). |

## Estructura de paquetes

Paquete base `ar.edu.um.atlas`, hexagonal liviana ([ADR 0003](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/adr/0003-arquitectura.md)):

| Paquete | Contenido |
|---|---|
| `domain` | Entidades JPA y reglas puras; sin Spring |
| `application` | Casos de uso: `search` (búsqueda, detalle, categorías y filtro de disponibilidad), `sync` (`SyncCatalog`, `ApplySnapshot`, `ApplyIncremental`) con el planificador puro en `sync.plan`; `port.out` (puertos hacia la cátedra: snapshot y cambios) y `repository` (Spring Data) |
| `adapters.in` | Disparadores: arranque (`startup`), periódico (`schedule`) y aviso de Kafka (`kafka`); y la API web (`web`): búsqueda y estado de la sincronización |
| `adapters.out` | Clientes de la cátedra: REST (`rest`) y Redis (`redis`) |
| `config` | Configuración de Spring: propiedades, clientes y disparadores |

## Seguridad

Los secretos salen del entorno, ninguno se escribe en logs ni mensajes, el cliente REST no sigue redirecciones, Redis se usa en solo lectura y los datos que vienen de afuera se validan al entrar. **La seguridad de la API (JWT, CORS, 401/403) todavía no está implementada**: por eso la búsqueda y `GET /api/sync/status` están apagados por defecto. Decisiones, estado y limitaciones en [**seguridad de atlas-catalogo**](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/seguridad-atlas-catalogo.md).

## Documentación

Todo vive en [alejandria-docs](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/tree/main/alejandria-docs):

| Documento | Qué cuenta |
|---|---|
| [ADR 0001](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/adr/0001-configuracion-y-secretos.md) | Configuración y secretos |
| [ADR 0002](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/adr/0002-stack.md) | Stack tecnológico |
| [ADR 0003](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/adr/0003-arquitectura.md) | Arquitectura interna y lógica de reconciliación |
| [Modelo de datos](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/modelo-datos/atlas-catalogo.md) | Tablas, reglas y decisiones A a L |
| [Estrategia de sincronización](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/sincronizacion-atlas-catalogo.md) | Cómo funciona, idempotencia y recuperación |
| [Seguridad](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/seguridad-atlas-catalogo.md) | Decisiones, estado y limitaciones |
| [Evolución de la implementación](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/implementacion/atlas-catalogo.md) | Qué se decidió al implementar cada issue, con porqué y costo |
| [Evidencias](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/tree/main/alejandria-docs/evidencias) | Verificaciones contra la cátedra real, sin secretos |
