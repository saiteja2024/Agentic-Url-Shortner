-- Shortener schema (specs/shortener/requirements.md → Data model).
-- Flyway creates the `shortener` schema and runs this inside it.

CREATE TABLE urls (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(32)   NOT NULL,
    long_url    VARCHAR(2048) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    expires_at  TIMESTAMPTZ,
    -- The unique constraint is the arbiter for collision retry and alias races.
    CONSTRAINT urls_code_uk UNIQUE (code),
    CONSTRAINT urls_code_format_ck CHECK (code ~ '^[A-Za-z0-9_-]{3,32}$'),
    CONSTRAINT urls_expiry_ck CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE TABLE clicks (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    url_id      BIGINT        NOT NULL REFERENCES urls (id) ON DELETE CASCADE,
    clicked_at  TIMESTAMPTZ   NOT NULL,
    referrer    VARCHAR(2048)
);

CREATE INDEX clicks_url_id_clicked_at_ix ON clicks (url_id, clicked_at);
