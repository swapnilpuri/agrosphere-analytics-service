# ANL-01 runtime and verification



Continuation authority: Architecture ANALYTICS_POC_ADAPTER_APPROVAL.md, exact accepted review SHA-256 855fe1894c530cf7942d3175e7a3eae850d0b34abff976a5e6bf7ba87a5618f3. This guide describes implementation; actual check results live in Architecture's adapter verification, and owner acceptance stays OPEN. The immutable accepted package's PREPARED label is historical.



The closed full-envelope parser validates the independent four v2 schemas plus identity, provenance, date/time, DRC arithmetic and adjustment semantics. ANL_JSON_DECIMAL_V1 fingerprints every validated field without retaining personal fields. JDBC commits immutable minimal receipts, highest SIMULATED roots, seen versions, scoped issues/cursors and exact daily aggregates together. Scope locks serialize changes; repeatable-read history reads only Analytics-owned materialization. Duplicate and lower versions cannot add a second contribution; missing versions and conflicts remain explicit. REAL is unsupported and contributes nothing.



Private durable queue `agrosphere.analytics.production.v2` has four exact bindings, limit 10000, manual ACK/prefetch 1/one consumer. Generated restricted principal cannot configure topology or publish shared business events. Initial attempt plus three retries (1/2/4 seconds) precede a fail-closed pause without ACK. Diagnostics contain only body SHA-256/reason and optional validated event ID, require positive confirm/no mandatory return before ACK, and use a private direct exchange/1000-message queue. No full-body logging or raw diagnostic DLQ. An uncertain commit is replayed safely; duplicate processing still confirms a required diagnostic before ACK. Explicit restart of only the owned container resumes after recovery.



`GET /api/v1/analytics/estates/{estateId}/blocks/{blockId}/production/daily?from=YYYY-MM-DD&to=YYYY-MM-DD` uses the unchanged 0.11.0 API. Inclusive maximum 367 dates. It distinguishes NONE/null, pending unknown dry mass, measured zero, voided roots and observed-only/incomplete evidence. Decimal strings retain exact three places; source references sort/cap at 100 with truncation metadata. Scope-wide issues can concern dates outside the query. No complete-stream claim.



`OwnedRebuildMain` is an explicit internal CLI with no public endpoint or arbitrary scope/SQL arguments. Invocation inside an owned container: `java -Dloader.main=com.agrosphere.analytics.OwnedRebuildMain -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher`. It verifies manifest/hash, lifetime, database URL/principals/marker and source scopes before rebuilding only its daily cache from its own highest ledger under scope locks. It preserves receipts/issues/seen versions and does not reconstruct upstream omissions or certify disaster recovery.



Integration tests use actual authenticated restricted PostgreSQL sessions, signed RSA JWT/JWKS HTTP, synthetic parsed correction across days, concurrent duplicates, rollback at each write boundary and cache rebuild. Infrastructure `python scripts/verify_analytics.py` verifies actual generated Operations/broker/Gateway/Web behavior, source outage, duplicate replay, sanitized conflict/poison, unconfirmed diagnostic pause/recovery, internal rebuild, negative authorization/SQL and owned cleanup. Requires Docker and an independently built Web dist plus installed browser tooling; no existing stack/config is read. Browser traces stay off; generated credentials are transient and sanitized logs retain no bodies.



The finite [admission profile](ANL01_ADMISSION_REVIEW.md) is approved; see [implementation](ANL01_ADMISSION_IMPLEMENTATION.md). ADMISSION_LIMIT is a private resource/admission diagnosis before full validation, with no trusted scope/event ID or projection effect. Numeric exponents are checked in raw tokens before decimal conversion. Resource limits are not unconditional schema-encoding compatibility. All M1/M2, owner M4/M0–M5, field Android/offline/English–French and REAL/production gates persist. No changes to AEO or existing data/config/indexes are authorized.

