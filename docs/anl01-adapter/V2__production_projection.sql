-- REVIEW CANDIDATE ONLY: outside runtime migrations. No installation authorized.
-- V1 remains unchanged. Analytics owns all these tables.
CREATE TABLE anl_collection (
 collection_id uuid PRIMARY KEY, estate_id uuid NOT NULL, block_id uuid NOT NULL,
 collection_date date NOT NULL, estate_timezone varchar(100) NOT NULL,
 version bigint NOT NULL CHECK(version>=0), event_id uuid NOT NULL,
 source_at timestamptz NOT NULL, fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
 status varchar(16) NOT NULL CHECK(status IN ('PENDING_DRC','CALCULATED','VOIDED')),
 data_provenance varchar(16) NOT NULL CHECK(data_provenance='SIMULATED'),
 wet_kg numeric(9,3) NOT NULL CHECK(wet_kg>0 AND wet_kg<=999999.999),
 dry_kg numeric(9,3) CHECK(dry_kg>=0 AND dry_kg<=wet_kg),
 CHECK((status='PENDING_DRC' AND dry_kg IS NULL) OR (status='CALCULATED' AND dry_kg IS NOT NULL) OR status='VOIDED')
);
CREATE INDEX anl_collection_scope_day ON anl_collection(estate_id,block_id,collection_date,collection_id);
CREATE TABLE anl_seen_version (
 collection_id uuid NOT NULL REFERENCES anl_collection(collection_id),
 version bigint NOT NULL CHECK(version>=0), event_id uuid NOT NULL,
 PRIMARY KEY(collection_id,version)
);
CREATE TABLE anl_receipt (
 event_id uuid PRIMARY KEY, collection_id uuid NOT NULL, version bigint NOT NULL CHECK(version>=0),
 estate_id uuid NOT NULL, block_id uuid NOT NULL,
 fingerprint char(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'), processed_at timestamptz NOT NULL,
 outcome varchar(20) NOT NULL CHECK(outcome IN ('APPLIED','OLDER_VERSION','CONFLICT','UNSUPPORTED_REAL'))
);
CREATE TABLE anl_issue (
 estate_id uuid NOT NULL, block_id uuid NOT NULL, event_id uuid NOT NULL,
 collection_id uuid NOT NULL,
 reason varchar(24) NOT NULL CHECK(reason IN ('EVENT_ID_CONFLICT','REVISION_CONFLICT','UNSUPPORTED_REAL')),
 PRIMARY KEY(estate_id,block_id,event_id,reason)
);
CREATE TABLE anl_scope (
 estate_id uuid NOT NULL, block_id uuid NOT NULL, last_processed_at timestamptz,
 latest_source_at timestamptz, PRIMARY KEY(estate_id,block_id)
);
CREATE TABLE anl_daily (
 estate_id uuid NOT NULL, block_id uuid NOT NULL, collection_date date NOT NULL,
 active_count bigint NOT NULL CHECK(active_count>=0), pending_count bigint NOT NULL CHECK(pending_count>=0),
 calculated_count bigint NOT NULL CHECK(calculated_count>=0), voided_count bigint NOT NULL CHECK(voided_count>=0),
 wet_kg numeric NOT NULL CHECK(wet_kg>=0), pending_wet_kg numeric NOT NULL CHECK(pending_wet_kg>=0),
 calculated_wet_kg numeric NOT NULL CHECK(calculated_wet_kg>=0), known_dry_kg numeric CHECK(known_dry_kg>=0),
 PRIMARY KEY(estate_id,block_id,collection_date),
 CHECK(active_count=pending_count+calculated_count), CHECK(wet_kg=pending_wet_kg+calculated_wet_kg),
 CHECK((calculated_count=0 AND known_dry_kg IS NULL) OR (calculated_count>0 AND known_dry_kg IS NOT NULL AND known_dry_kg<=calculated_wet_kg)),
 CHECK(wet_kg::text NOT IN ('NaN','Infinity','-Infinity') AND pending_wet_kg::text NOT IN ('NaN','Infinity','-Infinity')
  AND calculated_wet_kg::text NOT IN ('NaN','Infinity','-Infinity') AND (known_dry_kg IS NULL OR known_dry_kg::text NOT IN ('NaN','Infinity','-Infinity')))
);
