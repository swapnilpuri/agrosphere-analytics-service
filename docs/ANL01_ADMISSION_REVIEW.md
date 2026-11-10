# ANL-01 finite admission boundary review

Prepared 2026-11-05 (America/Chicago). **PENDING: one additional bounded decision, not repeat approval of ANL-01 runtime/domain/contracts.** No contract bytes or parser budgets are changed by this document. Default runtime remains disabled; existing stacks are excluded.

## Observed incompatibility

The accepted ANL01_ADAPTER_REVIEW.md says: “if the reviewed schema can exceed a ceiling, stop and revise rather than silently drop valid input.” Its byte/depth/member budgets are 262144/64/10000. Full maximum bounded evidence strings in adjusted/void fixtures fit. However, timestamp patterns allow an unbounded `0*` suffix after microseconds, with no maxLength; JSON whitespace/numeric spellings also have no finite transport bound. A finite consumer cannot claim universal acceptance of all such encodings.

A concrete SIMULATED adjusted fixture replaces only envelope `timestamp` 2026-10-27T14:00:00Z with the same instant spelled using 262145 fractional zero digits. Independent Draft202012Validator + FormatChecker accepts it; Python datetime confirms the same instant as collection.updatedAt. Body: 264909 UTF-8 bytes, SHA-256 a3a847b46a1f44495f1edfa13e826740339c58828d50056b34ffb91fc6d4f8f5. Exact pinned adjusted schema SHA-256 985f6d66815682f06ad6e743cea517dcc8e96c99dd91f72f0234c86d7fb32fe1. Current parser rejects the body by the reviewed byte ceiling. No real data or raw body is retained in evidence.

## Proposed single decision

Accept an explicit **generated local demonstration admission profile**, keeping public 0.11.0 schemas unchanged: maximum 262144 UTF-8 bytes, depth 64, 10000 container members, numeric token length 1000 and absolute raw numeric exponent at most 1000. These are resource/admission limits, not new business/domain semantics or measured producer throughput. Normal generated Operations serialization and maximum bounded evidence fixtures must fit; over-limit valid encodings remain unsupported by this finite profile and do not establish source completeness. This does not remove the broader contract-compatibility requirement: broader producer support needs reviewed transport limits/streaming design and independent contract coordination before activation.

After acceptance, distinguish `ADMISSION_LIMIT` from `INVALID_ENVELOPE` in private sanitized diagnostics, retaining only full-body hash/reason (no inferred scope or raw body). Bound exponent expansion before BigDecimal arithmetic/canonicalization. Do not alter fingerprints for accepted messages or public wire responses. Out-of-profile input causes no projection effect, is diagnosed with positive confirm before ACK, and pauses unacknowledged if diagnosis fails. Do not silently discard or relabel it as a malformed schema.

## Bounded exit criteria and preserved authority

Add byte/number/exponent boundaries, a schema-valid oversized timestamp fixture generated in memory, and negative/zero/numeric-equivalence checks. Verify maximum full adjusted/void bounded strings, no number-expansion exhaustion, exact fingerprints within the accepted profile, diagnostic classification/confirmation/pause and no projection mutation. Rerun affected Analytics checks and generated drill; retain exact pins and existing contracts. Record the finite-profile limitation in Web/source completeness and runtime documentation; owner connected functional/M4 acceptance stays OPEN.

The alternative is to require acceptance of every schema-valid encoding; that needs a separately bounded transport/streaming design before runtime closure. This proposal changes only this generated profile's admission/security boundary. It does not authorize existing-target migration, REAL, new public schemas/roles, deployment, Git or AEO actions. All satisfied prior approvals remain satisfied.
