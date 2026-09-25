-- SmartChef AI - esquema PostgreSQL
-- Se ejecuta solo si spring.sql.init.mode=always (por defecto: never, Hibernate gestiona el DDL).

CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    email         VARCHAR(150) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(150),
    role          VARCHAR(20)  NOT NULL DEFAULT 'ROLE_USER',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS scan_sessions (
    id                           BIGSERIAL PRIMARY KEY,
    user_id                      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    detected_ingredients_summary TEXT,
    demo                         BOOLEAN,
    created_at                   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS recipes (
    id                BIGSERIAL PRIMARY KEY,
    scan_session_id   BIGINT NOT NULL REFERENCES scan_sessions (id) ON DELETE CASCADE,
    title             VARCHAR(200) NOT NULL,
    description       TEXT,
    prep_time_minutes INTEGER,
    difficulty        VARCHAR(10),
    calories_estimate INTEGER,
    steps_json        TEXT,
    chef_tip          TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS recipe_ingredients (
    id           BIGSERIAL PRIMARY KEY,
    recipe_id    BIGINT NOT NULL REFERENCES recipes (id) ON DELETE CASCADE,
    name         VARCHAR(150) NOT NULL,
    is_available BOOLEAN NOT NULL DEFAULT FALSE,
    quantity     VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS favorite_recipes (
    user_id   BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    recipe_id BIGINT NOT NULL REFERENCES recipes (id) ON DELETE CASCADE,
    saved_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, recipe_id)
);

CREATE INDEX IF NOT EXISTS idx_scan_sessions_user    ON scan_sessions (user_id);
CREATE INDEX IF NOT EXISTS idx_recipes_scan_session  ON recipes (scan_session_id);
CREATE INDEX IF NOT EXISTS idx_recipe_ingredients_rec ON recipe_ingredients (recipe_id);
