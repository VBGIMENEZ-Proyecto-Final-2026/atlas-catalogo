package ar.edu.um.atlas.adapters.out.redis;

import ar.edu.um.atlas.application.port.out.CatalogChangeSource;
import ar.edu.um.atlas.application.port.out.CatalogMetadata;
import ar.edu.um.atlas.application.port.out.CatalogSourceException;
import ar.edu.um.atlas.application.port.out.CatedraCredentialRejectedException;
import ar.edu.um.atlas.application.port.out.CatedraInvalidResponseException;
import ar.edu.um.atlas.application.port.out.CatedraUnavailableException;
import ar.edu.um.atlas.application.port.out.VersionChanges;
import ar.edu.um.atlas.domain.Professional;
import ar.edu.um.atlas.domain.ProfessionalCategory;
import ar.edu.um.atlas.domain.WeeklySchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Adaptador de salida de los cambios incrementales: lee de Redis con {@link StringRedisTemplate}, reintenta
 * solo ante fallas transitorias de conexión y traduce los errores. Solo hace lecturas puntuales sobre
 * {@code catedra:sync:*}: nunca {@code KEYS}/{@code SCAN}, escrituras ni {@code catedra:holds:*}. Nunca
 * registra la contraseña.
 */
public class CatedraRedisChangeSource implements CatalogChangeSource {

    static final String METADATA_KEY = "catedra:sync:metadata";
    static final String CHANGES_KEY_PREFIX = "catedra:sync:changes:";
    static final String CATEGORIES_KEY = "catedra:sync:professional-categories";
    static final String PROFESSIONALS_KEY = "catedra:sync:professionals";
    static final String SCHEDULES_KEY = "catedra:sync:weekly-schedules";

    /** Máximo de campos por {@code HMGET}, para no armar comandos enormes. */
    static final int BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(CatedraRedisChangeSource.class);

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final StringRedisTemplate redis;
    private final RetryTemplate retryTemplate;

    public CatedraRedisChangeSource(StringRedisTemplate redis, RetryTemplate retryTemplate) {
        this.redis = redis;
        this.retryTemplate = retryTemplate;
    }

    @Override
    public Optional<CatalogMetadata> fetchMetadata() {
        Map<Object, Object> hash = read("la metadata", () -> redis.opsForHash().entries(METADATA_KEY));
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CatalogMetadata(
                number(hash, "currentVersion"),
                number(hash, "oldestAvailableVersion"),
                instant(hash.get("publishedAt"))));
    }

    @Override
    public Optional<VersionChanges> fetchChanges(long version) {
        String json = read("los cambios de la versión " + version,
                () -> redis.opsForValue().get(CHANGES_KEY_PREFIX + version));
        if (json == null) {
            return Optional.empty();
        }
        RedisJson.Changes changes = parse(json, RedisJson.Changes.class, "changes:" + version);
        if (changes.version() == null || changes.version() != version) {
            throw invalid("changes:" + version + " no corresponde a la versión pedida");
        }
        RedisJson.ChangeIds ids = required(changes.changes(), "changes:" + version, "changes");
        return Optional.of(new VersionChanges(version,
                ids(ids.professionalCategories(), version, "professionalCategories"),
                ids(ids.professionals(), version, "professionals"),
                ids(ids.weeklySchedules(), version, "weeklySchedules")));
    }

    @Override
    public List<ProfessionalCategory> fetchCategories(Collection<Long> ids) {
        return entities(CATEGORIES_KEY, ids, RedisJson.Category.class, RedisJson.Category::id,
                RedisEntityMapper::toCategory);
    }

    @Override
    public List<Professional> fetchProfessionals(Collection<Long> ids) {
        return entities(PROFESSIONALS_KEY, ids, RedisJson.Professional.class, RedisJson.Professional::id,
                RedisEntityMapper::toProfessional);
    }

    @Override
    public List<WeeklySchedule> fetchWeeklySchedules(Collection<Long> ids) {
        return entities(SCHEDULES_KEY, ids, RedisJson.Schedule.class, RedisJson.Schedule::id,
                RedisEntityMapper::toSchedule);
    }

    private <J, E> List<E> entities(String key, Collection<Long> ids, Class<J> type, Function<J, Long> idOf,
                                    Function<J, E> mapper) {
        List<Long> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
        List<E> result = new ArrayList<>();
        for (int from = 0; from < distinct.size(); from += BATCH_SIZE) {
            List<Long> batch = distinct.subList(from, Math.min(from + BATCH_SIZE, distinct.size()));
            List<String> fields = batch.stream().map(String::valueOf).toList();
            List<Object> values = read("las entidades de " + key,
                    () -> redis.opsForHash().multiGet(key, new ArrayList<Object>(fields)));
            for (int i = 0; i < batch.size(); i++) {
                Object value = values.get(i);
                if (value == null) {
                    continue;
                }
                J json = parse(value.toString(), type, key + "[" + batch.get(i) + "]");
                if (!batch.get(i).equals(idOf.apply(json))) {
                    throw invalid(key + "[" + batch.get(i) + "] trae un id distinto del field");
                }
                result.add(mapper.apply(json));
            }
        }
        return result;
    }

    /** Ejecuta una lectura con reintentos solo ante fallas transitorias y traduce los errores. */
    private <T> T read(String what, java.util.function.Supplier<T> action) {
        try {
            return retryTemplate.execute(action::get);
        } catch (RetryException e) {
            throw translate(e.getLastException(), what);
        }
    }

    /** Solo se reintentan las fallas de conexión y de tiempo; una credencial rechazada no. */
    public static boolean isTransient(Throwable t) {
        return !isCredentialRejection(t)
                && (t instanceof RedisConnectionFailureException
                || t instanceof QueryTimeoutException
                || t instanceof DataAccessResourceFailureException);
    }

    private CatalogSourceException translate(Throwable t, String what) {
        if (t instanceof CatalogSourceException e) {
            return e;
        }
        if (isCredentialRejection(t)) {
            log.warn("Redis rechazó la credencial al leer {}", what);
            return new CatedraCredentialRejectedException(
                    "Redis rechazó la credencial o los permisos; se rotan en el .env", null, null, t);
        }
        if (t instanceof DataAccessException) {
            log.warn("No se pudo leer {} de Redis: {}", what, t.getClass().getSimpleName());
            return new CatedraUnavailableException(
                    "Redis de la cátedra no está disponible (error de conexión o timeout)", null, null, t);
        }
        throw new IllegalStateException("Falla inesperada al leer de Redis", t);
    }

    /** Redis responde {@code WRONGPASS}, {@code NOAUTH} o {@code NOPERM} cuando la credencial o la ACL no sirven. */
    private static boolean isCredentialRejection(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String message = c.getMessage();
            if (message != null && (message.contains("WRONGPASS") || message.contains("NOAUTH")
                    || message.contains("NOPERM") || message.contains("invalid username-password"))) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private static <T> T parse(String json, Class<T> type, String where) {
        try {
            T value = JSON.readValue(json, type);
            return required(value, where, "cuerpo");
        } catch (JacksonException e) {
            throw invalid(where + " no es un JSON válido");
        }
    }

    private static long number(Map<Object, Object> hash, String field) {
        Object value = hash.get(field);
        if (value == null) {
            throw invalid("La metadata no trae el campo " + field);
        }
        try {
            return Long.parseLong(value.toString().strip());
        } catch (NumberFormatException e) {
            throw invalid("La metadata trae un valor no numérico en " + field);
        }
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value.toString().strip());
        } catch (DateTimeParseException e) {
            throw invalid("La metadata trae un publishedAt inválido");
        }
    }

    private static List<Long> ids(List<Long> ids, long version, String field) {
        List<Long> checked = required(ids, "changes:" + version, field);
        checked.forEach(id -> required(id, "changes:" + version, field));
        return checked;
    }

    private static <T> T required(T value, String where, String field) {
        if (value == null) {
            throw invalid(where + " no trae el campo obligatorio " + field);
        }
        return value;
    }

    static CatedraInvalidResponseException invalid(String message) {
        return new CatedraInvalidResponseException(message, null, null, null);
    }
}
