# atlas-catalogo

Servicio backend (Java + Spring Boot) responsable de mantener la copia local autoritativa de categorías, profesionales y horarios semanales, sincronizada contra el servicio central de la cátedra mediante snapshot REST y actualizaciones incrementales vía Redis y Kafka. Es la única fuente local vigente para búsqueda y filtrado de profesionales, consumida por hermes-app y cronos-turnos mediante un contrato propio protegido con JWT, nunca por acceso directo a su base de datos.

## Estado

Versión básica de la Isla 1 · Eolia. **Hoy hace:**

- Guarda el catálogo en su propia base PostgreSQL (migraciones con Flyway).
- Lo inicializa y lo reconstruye desde el snapshot REST de la cátedra.
- Lo mantiene al día con los cambios incrementales de Redis, avisado por Kafka y con una verificación periódica.
- Se recupera solo de las fallas de la cátedra, de Redis y de Kafka, sin dejar la copia a medias.
- Informa su estado en `GET /api/sync/status` (solo para administradores).
- Busca profesionales sobre la copia local por categoría, nombre, estado habilitado y disponibilidad, con detalle y horarios (`GET /api/professionals`).
- Permite autenticar la conexión a Kafka (SASL) por configuración, sin tocar código.
- Protege la API: valida los JWT que emite `cronos-turnos` (RS256, clave pública), exige un rol por ruta y responde `401` y `403` como `problem+json`; CORS por lista de orígenes.

- Corre en contenedores: Dockerfile y servicio `app` en el `compose.yaml` (ver "Todo en contenedores").
- Tiene su contrato con `cronos-turnos` documentado: [operaciones, DTO, autenticación y errores](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/contratos/atlas-catalogo-api.md).

**Todavía no hace:** emitir tokens (los emite `cronos-turnos`, que todavía no existe: para probar se usa una clave de desarrollo, ver "Probar la API"). Fuera de `GET /actuator/health`, todo exige un token válido. El avance y lo que falta están en el [backlog de la isla](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/backlog/isla-1-eolia.md).

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
# Editar .env: ATLAS_DB_PASSWORD, los valores CATEDRA_* de la cuenta técnica y la clave pública JWT
# (ATLAS_JWT_PUBLIC_KEY_LOCATION; para desarrollo, ver "Probar la API")

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

## Todo en contenedores

Además de la base, la aplicación puede correr en un contenedor (imagen de dos etapas: compila con JDK 25 y ejecuta con un JRE 25, como usuario sin privilegios). El servicio `app` del `compose.yaml` solo arranca con el perfil `app`, así el flujo de desarrollo de arriba no cambia.

```bash
# En .env hace falta ATLAS_JWT_PUBLIC_KEY_LOCATION con la ruta de la clave PÚBLICA (un archivo .pem).
docker compose --profile app up -d --build     # compila la imagen y levanta la base y la aplicación
docker compose ps                              # atlas-app debe figurar "healthy" (tarda unos 40 s)
curl localhost:8080/actuator/health            # {"status":"UP", ...}
docker compose --profile app down              # apaga ambos; los datos se conservan
```

- La aplicación lee el mismo `.env`; el compose solo pisa lo que cambia dentro de la red de Compose (`ATLAS_DB_HOST=db`, `ATLAS_DB_PORT=5432`) y monta la clave pública en `/run/secrets/jwt-public.pem` (solo lectura). La imagen no lleva secretos: `.dockerignore` excluye `.env` y las claves.
- Con el perfil `app` el puerto 8080 queda ocupado: no se puede correr a la vez `./gradlew bootRun`.
- El montaje de la clave lleva `:z` para hosts con SELinux (Fedora); etiqueta el `.pem` público y donde no hay SELinux se ignora.
- Solo funciona con la clave en un archivo; la variante `ATLAS_JWT_PUBLIC_KEY` (el PEM en la variable) no aplica al contenedor.
- Los tests no corren dentro de la imagen (necesitan Testcontainers): se corren con `./gradlew build` en la máquina.

## Probar la API

Todo lo que no sea `GET /actuator/health` exige un JWT firmado por `cronos-turnos`, que atlas solo puede verificar con su **clave pública**. Mientras `cronos-turnos` no exista, se usa un par de claves de **desarrollo**, que nunca se commitea ni se usa fuera de la máquina local:

```bash
mkdir -p ~/.atlas-dev && cd ~/.atlas-dev
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out dev-private.pem   # solo para firmar tokens de prueba
openssl pkey -in dev-private.pem -pubout -out dev-public.pem                          # esta es la que lee atlas
# En .env:  ATLAS_JWT_PUBLIC_KEY_LOCATION=/home/<usuario>/.atlas-dev/dev-public.pem

cd -                                                                                  # de vuelta en atlas-catalogo
./gradlew bootRun &                                                                   # o en otra terminal

TOKEN=$(scripts/dev-token.sh ~/.atlas-dev/dev-private.pem ana ROLE_USER)             # usuario con ROLE_USER
ADMIN=$(scripts/dev-token.sh ~/.atlas-dev/dev-private.pem root ROLE_ADMIN)           # administrador

curl -i localhost:8080/api/categories                                  # 401 NO_AUTENTICADO (sin token)
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/categories    # 200
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/sync/status   # 403 SIN_PERMISO (pide ROLE_ADMIN)
curl -i -H "Authorization: Bearer $ADMIN" localhost:8080/api/sync/status   # 200
```

`scripts/dev-token.sh` firma con RS256 un token con `sub`, `auth` (roles separados por espacio, como JHipster), `iat` y `exp` (una hora; `DEV_TOKEN_TTL=<segundos>` lo cambia). Cada rechazo queda en el log con el método, la ruta y, en un `403`, el usuario; nunca con el token.

## Configuración

Las variables de entorno se declaran en `.env.example` (versionado, con placeholders). La aplicación lee el `.env` local (`spring.config.import`) o, si no existe, las variables de entorno reales. Los valores `CATEDRA_*` salen de la respuesta de la cuenta técnica de la cátedra y son los mismos en los dos backends. **Nunca commitear `.env`, tokens ni IPs.** Si falta una variable obligatoria, la aplicación no arranca y el mensaje dice cuál. Las variables del `.env.example` que no aparecen acá (`CATEDRA_GROUP_ID`, los namespaces de Redis y los topics de reservas) no las usa este servicio.

### Obligatorias

| Variable | Descripción |
|---|---|
| `ATLAS_DB_PASSWORD` | Contraseña de la base local. |
| `ATLAS_JWT_PUBLIC_KEY_LOCATION` o `ATLAS_JWT_PUBLIC_KEY` | Clave **pública** RSA (PEM X.509, 2048 bits o más) con la que `cronos-turnos` firma los JWT: una ruta de archivo (`file:`, `classpath:` o ruta simple) o el PEM en la variable, con los saltos de línea como `\n`. Solo una de las dos. Sin clave la aplicación no arranca. |
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
| `ATLAS_SEARCH_ENDPOINT` | `true` | Expone la búsqueda (`GET /api/professionals`, `GET /api/professionals/{id}` y `GET /api/categories`); con `false` las rutas no existen: `404` con un token válido y `401` sin token. |
| `ATLAS_SYNC_STATUS_ENDPOINT` | `true` | Expone `GET /api/sync/status` (solo `ROLE_ADMIN`); con `false` la ruta no existe (`404` para un administrador). |
| `ATLAS_JWT_AUTHORITIES_CLAIM` | `auth` | Claim del token con las autoridades (texto con espacios o lista). `auth` es el de JHipster. |
| `ATLAS_JWT_CLOCK_SKEW` | `60s` | Tolerancia de reloj al validar `exp` y `nbf`, hasta 5 minutos. |
| `ATLAS_CORS_ALLOWED_ORIGINS` | vacío | Orígenes permitidos, separados por coma, cada uno `esquema://host[:puerto]` sin comodines. Vacío: no se acepta ninguno. Solo `GET` y `OPTIONS`, sin credenciales. |
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

Se resuelve **solo con la copia local**: ninguna búsqueda consulta a la cátedra (una regla de ArchUnit lo comprueba: `application.search` no puede depender de los clientes ni de la sincronización). Pide un token con `ROLE_USER` o `ROLE_ADMIN` (ver "Seguridad") y se puede apagar con `ATLAS_SEARCH_ENDPOINT=false`.

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
curl -H "Authorization: Bearer $TOKEN" 'localhost:8080/api/professionals?name=ana&available=true&dayOfWeek=MONDAY&size=10'
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
| `ProfessionalsControllerTest`, `BusquedaApagadaTest` | Los parámetros, el JSON y los errores `400`, `404` y `503` de la búsqueda, y que apagada responde `404` (sin filtros de seguridad: eso se prueba aparte). |
| `SeguridadApiTest` | La seguridad con la aplicación completa y firmas RS256 reales: `401` sin token y con tokens inválidos (vencido, firma ajena, payload adulterado, `HS256` con la clave pública como secreto, `alg: none`, sin `exp` o `sub`), `403` por rol, rutas desconocidas denegadas y el rechazo en el log sin el token. |
| `CorsApiTest`, `CorsPorDefectoTest` | CORS: solo los orígenes configurados, solo lectura, sin credenciales; sin configuración no se acepta ninguno. |
| `SecurityPropertiesTest`, `JwtPublicKeysTest` | Las propiedades de seguridad y la carga de la clave pública (archivo, variable, clave privada o corta rechazadas). |
| `ArquitecturaTest` | Reglas de dependencia de ArchUnit ([ADR 0003](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/adr/0003-arquitectura.md)). |

## Estructura de paquetes

Paquete base `ar.edu.um.atlas`, hexagonal liviana ([ADR 0003](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/adr/0003-arquitectura.md)):

| Paquete | Contenido |
|---|---|
| `domain` | Entidades JPA y reglas puras; sin Spring |
| `application` | Casos de uso: `search` (búsqueda, detalle, categorías y filtro de disponibilidad), `sync` (`SyncCatalog`, `ApplySnapshot`, `ApplyIncremental`) con el planificador puro en `sync.plan`; `port.out` (puertos hacia la cátedra: snapshot y cambios) y `repository` (Spring Data) |
| `adapters.in` | Disparadores: arranque (`startup`), periódico (`schedule`) y aviso de Kafka (`kafka`); la API web (`web`): búsqueda y estado de la sincronización; y los rechazos de seguridad (`security`) |
| `adapters.out` | Clientes de la cátedra: REST (`rest`) y Redis (`redis`) |
| `config` | Configuración de Spring: propiedades, clientes, disparadores y seguridad (`SecurityConfig`) |

## Seguridad

atlas es un **servidor de recursos**: valida los JWT que emite `cronos-turnos` con su clave pública (RS256) y no guarda usuarios ni puede firmar tokens (ADR de autenticación entre servicios, opción A: se propaga el JWT del usuario).

| Ruta | Acceso |
|---|---|
| `GET /actuator/health` | Público, sin detalles. |
| `GET /api/professionals`, `GET /api/professionals/{id}`, `GET /api/categories` | `ROLE_USER` o `ROLE_ADMIN`. |
| `GET /api/sync/status` | `ROLE_ADMIN`: muestra errores internos de la sincronización. |
| Todo lo demás (otros métodos, rutas desconocidas, otros endpoints de Actuator) | Denegado: `401` sin token, `403` con uno válido. |

- **Qué se valida:** firma RS256 con la clave pública (se rechazan `HS256`, `none` y cualquier otro algoritmo), vigencia (`exp` y `nbf`, con tolerancia de 60 s) y que existan `sub` y `exp`. Los permisos salen del claim `auth` (texto con espacios o lista, como JHipster) y se toman tal cual: ya traen `ROLE_`.
- **Respuestas:** `401 NO_AUTENTICADO` (con `WWW-Authenticate: Bearer`) y `403 SIN_PERMISO` como `application/problem+json`. No dicen por qué se rechazó el token ni qué rol faltaba. Cada rechazo queda en el log con el método y la ruta, y el usuario en el `403`; nunca el token.
- **Sin sesión ni cookies:** el token va en `Authorization: Bearer`, por eso CSRF no aplica y está desactivado. Ninguna decisión depende de un identificador enviado por el cliente.
- **CORS:** solo los orígenes de `ATLAS_CORS_ALLOWED_ORIGINS`, solo `GET` y `OPTIONS`, sin credenciales. La app Android no es un navegador y no lo necesita.
- **Secretos:** la clave pública no es secreta; la privada solo la tiene `cronos-turnos`. Los secretos salen del entorno, ninguno se escribe en logs ni mensajes, el cliente REST no sigue redirecciones, Redis se usa en solo lectura y los datos que vienen de afuera se validan al entrar.
- **Cifrado del canal (TLS):** por defecto atlas escucha en HTTP y solo en la red local, y el compose publica el puerto en `127.0.0.1`. Para HTTPS no hace falta tocar código: Spring Boot lo activa con un almacén de claves PKCS12, por variables de entorno (verificado en el contenedor con TLS 1.3; el puerto responde `400` a una petición HTTP plana).

  ```bash
  keytool -genkeypair -alias atlas -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore keystore.p12 \
    -dname "CN=localhost" -ext "san=dns:localhost,ip:127.0.0.1" -validity 30   # certificado de prueba
  # En .env (con bootRun):
  # SERVER_SSL_KEY_STORE=/ruta/a/keystore.p12
  # SERVER_SSL_KEY_STORE_PASSWORD=<la contraseña del almacén>
  # SERVER_SSL_KEY_STORE_TYPE=PKCS12
  curl --cacert <certificado> https://localhost:8080/actuator/health
  ```

  El almacén y su contraseña son secretos: no se commitean (`*.p12` y `*.jks` se ignoran). En producción el certificado lo firmaría una autoridad reconocida; el de prueba es autofirmado.
- **Límites:** no se valida el emisor (`iss`) ni la audiencia (`aud`), y rotar la clave exige reiniciar atlas. Decisiones y limitaciones completas en [**seguridad de atlas-catalogo**](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/arquitectura/seguridad-atlas-catalogo.md).

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
| [Contrato con cronos-turnos](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/contratos/atlas-catalogo-api.md) | Operaciones, DTO, autenticación y errores |
| [Evolución de la implementación](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/blob/main/alejandria-docs/implementacion/atlas-catalogo.md) | Qué se decidió al implementar cada issue, con porqué y costo |
| [Evidencias](https://github.com/VBGIMENEZ-Proyecto-Final-2026/biblioteca-alejandria/tree/main/alejandria-docs/evidencias) | Verificaciones contra la cátedra real, sin secretos |
