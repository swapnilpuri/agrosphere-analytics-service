# Repository instructions

Work only within this independent Git repository. No parent repository, submodules, shared persistence models, cross-service SQL or multi-service Maven parent.

Current scope: Milestone 0 foundation only. Do not add business APIs, GIS functionality, AI models or Unreal gameplay/assets without a new milestone request.

Ownership: Service-owned materialized KPI projections, productivity/yield summaries, dashboards and historical comparisons. No cross-database SQL.

Use ports/adapters and service-owned migrations. APIs and events are designed in agrosphere-contracts; coordinate breaking changes through a versioned contract release, never a source path dependency. UUID estateId/blockId remain stable across clients. Mark data REAL, SIMULATED, ESTIMATED or PREDICTED. GeoJSON is WGS84 longitude/latitude (RFC 7946). Procedural trees are illustrative.

Never commit credentials, local environment files, generated datasets or large geospatial assets. Use environment examples containing placeholders only. No remote creation, push or cloud deployment is authorized.

Run repository checks documented in README.md. Report actual checks separately from unavailable checks. Version independently using SemVer and CHANGELOG.md; changes to 0.x contracts can still break consumers and require review.


## Owner-approved ANL-01 preparation — 2026-11-05

Architecture ANALYTICS_POC_APPROVAL.md records acceptance of exact review SHA-256 3f4b8ee7c521502e0a29196ab647d84598dcb09aa91700856ef1bdc36e452342. Isolated Contracts candidate/validation and Analytics pure domain/ports/tests only, SIMULATED. Preserve reviewed/released bytes, V1, configuration/data/indexes and independent builds. Exact executable acceptance/local release precedes promotion/pins; runtime SQL/consumer/queue/security/provisioning needs subsequent bounded review. No adapters, running API/broker/database, existing-stack/role/credential/private configuration, REAL, Git publication/deployment or AEO action.


## ANL-01 exact local release/pins approved — 2026-11-05

Architecture ANALYTICS_POC_CONTRACT_APPROVAL.md accepts exact manifest 43d9e5f7b328002c735cdf85da3f6ed5e0c9ff41a2f74aaccdcc0b8e8a396998 for ten artifacts/four unchanged inputs, local Contracts 0.11.0 archive/promotion and independent Analytics pins only. Preserve old bytes/reviews/data/config/indexes. Concrete runtime SQL/consumer/queue/security/provisioning review remains required; no runtime/Gateway/Web/existing-stack/REAL/Git/deployment/AEO authority. Do not repeat satisfied preparation/executable approvals.


## ANL-01 bounded runtime continuation — 2026-11-05

Owner instructed "please resume" immediately after the bounded runtime/dashboard approval request. Architecture ANALYTICS_POC_ADAPTER_APPROVAL.md records that continuation authority for exact review 855fe1894c530cf7942d3175e7a3eae850d0b34abff976a5e6bf7ba87a5618f3: SIMULATED materialized Analytics, Analytics-only Gateway route, read-only Web dashboard and new owned disposable verification. Existing 0.11.0 contracts/pins unchanged; exact SQL/grant engineering review precedes new-fixture installation. No existing-stack/private config/data/roles/credentials, REAL, production/Git/deployment/AEO action. Preserve indexes/prior features and original owner/field/milestone gates.


## ANL-01 finite admission approved

Owner approved exact admission review SHA-256 5c9a6787f0ebf2ad0d3d172e5fc3ac2ebb9d83ea9936c3664331fa81fe8629f8. Architecture ANALYTICS_POC_ADMISSION_APPROVAL.md records finite generated profile, bounded parser/diagnostic/test changes and new owned disposable verification. Prior approvals satisfied; preserve exact contracts, migrations, indexes/private config/data and all owner/field/REAL/AEO/Git/deployment exclusions.


## ANL-02 approved preparation — 2026-11-05

Architecture ANALYTICS_ENVIRONMENT_POC_APPROVAL.md accepts exact preparation review c15ac50638feb09b176a44b5b16033d50e1b7365cbbe4467550f25d5910a17e9. Owner resumed in the new session. Isolated Contracts environmental candidate and Analytics pure rules/ports/tests only, SIMULATED; no repeated preparation approval. Current 0.11.0 release/pins and old reviewed bytes remain unchanged. Exact executable/local release/pins and subsequent runtime SQL/queue/security/provisioning remain separate gates; no existing-target/private config/data/role/credential, REAL, Git publication/deployment or AEO authority. See current Architecture checkpoint/verification.


## ANL-02 exact local release/pins approved — 2026-11-05

Architecture ANALYTICS_ENVIRONMENT_CONTRACT_APPROVAL.md records owner acceptance of manifest 1759e5d95fb3602b623b995e4650f035b67dbb550760116587f46c031ca7d111: fourteen artifacts/three unchanged inputs, local independent Contracts 0.12.0 archive/promotion/release metadata and Analytics API/Telemetry input pins only. Preserve old candidates/reviews/releases, four collection-v2 inputs, V1/V2/config/data/indexes. Preparation/executable gates satisfied; runtime/SQL/AMQP/HTTP/Web/queue/security/provisioning remains separately gated under ANL02_ADAPTER_REVIEW.md. No existing-target/private config/account/role/credential, REAL, Git publication/deployment or AEO authority.


## ANL-02 bounded runtime approved — 2026-11-05

Architecture ANALYTICS_ENVIRONMENT_ADAPTER_APPROVAL.md records owner acceptance of exact runtime/SQL manifest afff8ff5a13e1167d53ab569f0ea28c5f003def01fab9318db455f291f537ea8. Implement SIMULATED environmental parser/immutable persistence/consumer/scoped API, read-only Web with independent 0.12.0 pin and new owned disposable verification; generated fixture credentials included. Exact SQL/grant engineering review before installation. No new producer/role or Gateway prefix widening. Preserve old artifacts/V1/V2/config/data/index and all original gates. No existing-stack/private config/account/role/credential, REAL, Git publication/deployment or AEO action. Historical pending labels are superseded only for this scope.
