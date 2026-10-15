-- Orchestrator schema (specs/orchestrator/requirements.md → Data model).
-- Flyway creates the `orchestrator` schema and runs this inside it.

CREATE TABLE runs (
    id                UUID PRIMARY KEY,
    pipeline_name     VARCHAR(100) NOT NULL,
    pipeline_version  VARCHAR(50)  NOT NULL,
    mode              VARCHAR(20)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    started_at        TIMESTAMPTZ  NOT NULL,
    finished_at       TIMESTAMPTZ
);

CREATE TABLE nodes (
    run_id          UUID         NOT NULL REFERENCES runs (id),
    node_id         VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    attempt         INT          NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    failure_reason  TEXT,
    PRIMARY KEY (run_id, node_id)
);

CREATE TABLE artifacts (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id         UUID         NOT NULL REFERENCES runs (id),
    node_id        VARCHAR(100) NOT NULL,
    name           VARCHAR(255) NOT NULL,
    content        JSONB        NOT NULL,
    content_hash   CHAR(64)     NOT NULL,
    parent_hashes  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT artifacts_hash_format_ck CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT artifacts_parents_array_ck CHECK (jsonb_typeof(parent_hashes) = 'array')
);
CREATE INDEX artifacts_run_node_ix ON artifacts (run_id, node_id);
CREATE INDEX artifacts_content_hash_ix ON artifacts (content_hash);

CREATE TABLE events (
    seq          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id       UUID         NOT NULL REFERENCES runs (id),
    run_seq      BIGINT       NOT NULL,
    node_id      VARCHAR(100),
    type         VARCHAR(40)  NOT NULL,
    from_status  VARCHAR(20),
    to_status    VARCHAR(20),
    payload      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    occurred_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT events_run_seq_uk UNIQUE (run_id, run_seq)
);
CREATE INDEX events_run_ix ON events (run_id, seq);

-- ---- Append-only audit log (ORCH-013) -------------------------------------------------
-- 1) A trigger rejects UPDATE/DELETE/TRUNCATE for everyone, including the table owner the
--    prototype connects as. Removing it is itself a schema change that needs approval.
CREATE FUNCTION events_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'orchestrator.events is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER events_no_update_delete
    BEFORE UPDATE OR DELETE ON events
    FOR EACH ROW EXECUTE FUNCTION events_append_only();

CREATE TRIGGER events_no_truncate
    BEFORE TRUNCATE ON events
    FOR EACH STATEMENT EXECUTE FUNCTION events_append_only();

-- 2) Least-privilege application role for production: it may read and append, never rewrite.
DO
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'orchestrator_app') THEN
        CREATE ROLE orchestrator_app NOLOGIN;
    END IF;
END;
$$;

GRANT USAGE ON SCHEMA orchestrator TO orchestrator_app;
GRANT SELECT, INSERT, UPDATE ON runs, nodes TO orchestrator_app;
GRANT SELECT, INSERT ON artifacts, events TO orchestrator_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA orchestrator TO orchestrator_app;
REVOKE UPDATE, DELETE, TRUNCATE ON events FROM PUBLIC, orchestrator_app;
