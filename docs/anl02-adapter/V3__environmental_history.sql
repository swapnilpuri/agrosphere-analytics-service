-- REVIEW CANDIDATE ONLY. Outside automatic migrations; no installation authorized.
-- Preserve V1/V2 and all existing targets. New generated owned databases only after approval.
CREATE TABLE anl_environment_receipt (
 event_id uuid PRIMARY KEY, observation_id uuid NOT NULL, estate_id uuid NOT NULL, block_id uuid NOT NULL,
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 processed_at timestamptz NOT NULL, outcome varchar(16) NOT NULL CHECK(outcome IN ('APPLIED','CONFLICT'))
);
CREATE TABLE anl_environment_observation (
 observation_id uuid PRIMARY KEY, event_id uuid UNIQUE NOT NULL REFERENCES anl_environment_receipt(event_id),
 device_id uuid NOT NULL, estate_id uuid NOT NULL, block_id uuid NOT NULL,
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 data_provenance varchar(16) NOT NULL CHECK(data_provenance='SIMULATED'),
 observed_at timestamptz NOT NULL, received_at timestamptz NOT NULL,
 kind varchar(24) NOT NULL CHECK(kind IN ('AIR_TEMPERATURE','SOIL_MOISTURE','INTERVAL_RAINFALL')),
 unit varchar(16) NOT NULL, value_text varchar(20) NOT NULL
  CHECK(value_text ~ '^-?(0|[1-9][0-9]{0,11})(\.[0-9]{1,6})?$'),
 interval_start timestamptz, interval_end timestamptz,
 CHECK(observed_at<=received_at+interval '5 minutes'),
 CHECK((kind='AIR_TEMPERATURE' AND unit='CELSIUS') OR
       (kind='SOIL_MOISTURE' AND unit='PERCENT' AND value_text::numeric BETWEEN 0 AND 100) OR
       (kind='INTERVAL_RAINFALL' AND unit='MILLIMETRE' AND value_text::numeric>=0)),
 CHECK((kind='INTERVAL_RAINFALL' AND interval_start IS NOT NULL AND interval_end IS NOT NULL
         AND interval_start<interval_end AND interval_end<=observed_at) OR
       (kind<>'INTERVAL_RAINFALL' AND interval_start IS NULL AND interval_end IS NULL))
);
CREATE INDEX anl_environment_window ON anl_environment_observation(estate_id,block_id,observed_at,observation_id);
CREATE TABLE anl_environment_issue (
 estate_id uuid NOT NULL, block_id uuid NOT NULL, event_id uuid NOT NULL, observation_id uuid NOT NULL,
 reason varchar(32) NOT NULL CHECK(reason IN ('EVENT_ID_CONFLICT','OBSERVATION_ID_CONFLICT')),
 PRIMARY KEY(estate_id,block_id,event_id,observation_id,reason)
);
CREATE TABLE anl_environment_scope (
 estate_id uuid NOT NULL, block_id uuid NOT NULL, last_processed_at timestamptz NOT NULL,
 latest_source_at timestamptz NOT NULL, PRIMARY KEY(estate_id,block_id)
);
-- Runtime grants (identifier is an unprovisioned review placeholder):
-- GRANT SELECT,INSERT ON anl_environment_receipt,anl_environment_observation,anl_environment_issue TO <owned_runtime>;
-- GRANT SELECT,INSERT,UPDATE ON anl_environment_scope TO <owned_runtime>;
-- No UPDATE/DELETE on immutable facts/receipts/issues; no DDL/TRUNCATE/PUBLIC grants.
