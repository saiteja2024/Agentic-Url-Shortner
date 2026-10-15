-- Run metadata needed by the API and report (scenario, request, branch, why it stopped).
ALTER TABLE runs ADD COLUMN scenario    VARCHAR(50)   NOT NULL DEFAULT 'unknown';
ALTER TABLE runs ADD COLUMN request     TEXT          NOT NULL DEFAULT '';
ALTER TABLE runs ADD COLUMN branch      VARCHAR(120)  NOT NULL DEFAULT '';
ALTER TABLE runs ADD COLUMN stop_reason TEXT;
CREATE INDEX runs_started_at_ix ON runs (started_at DESC);
