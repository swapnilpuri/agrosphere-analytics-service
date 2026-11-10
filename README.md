# agrosphere-analytics-service

Independent Java 25 / Spring Boot 4.0.9 service; version 0.1.0. ANL-01 materializes SIMULATED daily production from four independently pinned Operations collection-v2 events. No cross-service SQL, shared models or source-service/browser summation. Contracts 0.12.0 now pins the accepted extended API and unchanged Telemetry v1 input alongside four retained collection-v2 schemas. Previous API/metadata are archived; environmental adapters remain unimplemented. See [runtime guide](docs/ANL01_RUNTIME.md), [accepted package](docs/ANL01_ADAPTER_REVIEW.md) and Architecture's continuation/evidence record. Original foundation/preparation reports remain historical evidence.

## Build and test

Run `./mvnw verify` (Windows `.\mvnw.cmd verify`) on JDK 25. Run `./mvnw verify -Pintegration` with Docker for newly owned PostgreSQL/JWKS/HTTP fixtures. Then `python scripts/validate_contract_pins.py --jar target/agrosphere-analytics-service-0.1.0.jar`. Independent Docker packaging runs `verify`; integration tests run separately with Docker, never inside the image build. Existing JUnit 5 tests deliberately manage Spring contexts without SpringExtension (Spring 7 expects JUnit 6 APIs).

## Runtime boundary

Default ingestion and business reads are disabled; default Flyway targets V1, preserving the additive V2 gate. Activation requires an explicit short-lived generated ownership manifest/hash, matching database URL/principals, database marker and source scope. Use only Infrastructure's generated `verify_analytics.py` harness. It provisions separate migration/runtime principals and private bounded AMQP topology in a new labelled project; it never loads an existing private environment file. No existing-stack activation, migration, account enablement or production readiness is implied.

Only health is public. Business JWTs require issuer, signature, expiry, audience and accepted role/trusted estate scope; active Estate/block validation forwards the caller token. Private optional metrics require PLATFORM_ADMIN/PLANTATION_ADMIN and are not routed through the Gateway business API.

The [finite admission profile](docs/ANL01_ADMISSION_REVIEW.md) is owner-approved; Architecture ANALYTICS_POC_ADMISSION_APPROVAL.md records the exact acceptance. Limits apply to this generated profile, which cannot accept every encoding permitted by the public schema. See docs/ANL01_ADMISSION_IMPLEMENTATION.md for limits and actual check evidence. Owner functional acceptance and original M4 completion remain separate. No REAL data, AI, forecasting, hosting, deployment or Git publication is delivered.

## ANL-02 pure preparation â€” 2026-11-05

[Pure rules/ports](docs/ANL02_PURE_PREPARATION.md) preserve exact per-device environmental evidence, immutable conflicts, explicit half-open UTC windows and bounded observed-history reads. Nine new tests; no runtime registration or parser/storage/consumer/endpoint. Contracts 0.11.0 pins remain unchanged; exact executable release and [runtime review](docs/ANL02_ADAPTER_REVIEW.md) remain separate gates.

## ANL-02 accepted independent pins — 2026-11-05

Owner exact executable acceptance/local release is recorded in Architecture ANALYTICS_ENVIRONMENT_CONTRACT_APPROVAL.md. Six API/input files pin local Contracts 0.12.0; old API/RELEASE metadata lives in docs/contracts-previous-0.11.0. Standalone/JAR validation also checks this archive. Run `python -m unittest discover -s scripts -p test_contract_pins.py -v`. Historical preparation/pending labels are satisfied for this exact release only. Environmental runtime/SQL/queue/security/provisioning remains separately gated.

## ANL-02 runtime — 2026-11-05

[Environmental runtime guide](docs/ANL02_RUNTIME.md): immutable SIMULATED history, strict Telemetry v1 consumer, bounded UTC read API and guarded rebuild are implemented. Default disabled; only the approved newly owned generated fixture may activate V3. Independent Contracts 0.12.0 pins remain exact; owner/M4 acceptance stays open.
