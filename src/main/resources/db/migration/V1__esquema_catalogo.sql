-- Esquema inicial de atlas-catalogo (modelo-datos/atlas-catalogo.md).
-- Los IDs vienen de la cátedra y no se generan. Las únicas restricciones son NOT NULL,
-- las claves foráneas y los CHECK de day_of_week y sync_state (decisiones G y H).

CREATE TABLE professional_category (
    id          bigint      PRIMARY KEY,
    name        text        NOT NULL,
    description text,
    enabled     boolean     NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

CREATE TABLE professional (
    id          bigint      PRIMARY KEY,
    category_id bigint      NOT NULL REFERENCES professional_category (id),
    first_name  text        NOT NULL,
    last_name   text        NOT NULL,
    enabled     boolean     NOT NULL,
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

CREATE INDEX professional_category_id_idx ON professional (category_id);

CREATE TABLE weekly_schedule (
    id                    bigint      PRIMARY KEY,
    professional_id       bigint      NOT NULL REFERENCES professional (id),
    day_of_week           text        NOT NULL
        CHECK (day_of_week IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    start_time            time        NOT NULL,
    end_time              time        NOT NULL,
    slot_duration_minutes integer     NOT NULL,
    enabled               boolean     NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL
);

CREATE INDEX weekly_schedule_professional_idx ON weekly_schedule (professional_id);
CREATE INDEX weekly_schedule_day_professional_enabled_idx
    ON weekly_schedule (day_of_week, professional_id) WHERE enabled;

-- Una sola fila: control del sync y candado (SELECT ... FOR UPDATE).
CREATE TABLE sync_state (
    id                   smallint    PRIMARY KEY CHECK (id = 1),
    local_version        bigint,
    status               text        NOT NULL,
    last_result          text,
    last_snapshot_reason text,
    last_sync_at         timestamptz,
    last_error_code      text,
    last_error_message   text,
    last_error_at        timestamptz,
    updated_at           timestamptz NOT NULL
);

INSERT INTO sync_state (id, local_version, status, updated_at) VALUES (1, NULL, 'IDLE', now());
