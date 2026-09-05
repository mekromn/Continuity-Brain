# Continuity Brain — Authoritative Feature Matrix

This file preserves the full intended product scope so implementation does not drift toward only the easiest features. **Implemented** means the core feature exists in source. **Partial** means a useful foundation exists but the complete intended behavior is not yet present. **Not yet** means it remains active scope.

Private ChatGPT data, derived personal records, embeddings, backups, attachments, and keys are never stored in this public repository.

## Archive, storage, and updates

| Capability | Status | Current implementation |
|---|---|---|
| Import official ChatGPT export ZIP | **Implemented** | Streaming ZIP/JSON importer preserves conversation graph, roles, parents, timestamps and content types. |
| Re-import newer exports incrementally | **Implemented** | Archive SHA-256 dedupe plus per-message revision/content hashing; unchanged data is skipped. |
| Periodic export-backup updates | **Partial** | Persisted Android `JobScheduler` backend and safe SAF folder scanner are implemented; Vault folder-picker/status UI is the remaining integration step. |
| Live Continuity ingestion between exports | **Implemented** | Authenticated localhost `/v1/live/message`; later official exports reconcile matching provisional live rows. |
| Encrypt all sensitive persistent content | **Implemented** | AES-256-GCM under non-exportable Android Keystore keys. |
| Keep searchable words out of plaintext SQLite | **Implemented** | HMAC-SHA-256 blind lexical indexes; title and message indexes are revisioned separately. |
| Encrypted attachment archive | **Implemented** | Attachments stream into the private app directory encrypted at rest. |
| Portable encrypted Brain backup/restore | **Implemented** | `.cbbrain` streaming logical backup, PBKDF2-HMAC-SHA256 + AES-256-GCM, independent of device Keystore. |
| Permanent archive independent of ChatGPT | **Partial** | `.cbbrain` is portable now; human-readable Markdown/JSON/SQLite export packs remain to be added. |

## Search and retrieval

| Capability | Status | Current implementation |
|---|---|---|
| Fully offline exact search | **Implemented** | Private blind lexical index with stemming/prefix terms and current conversation-title index. |
| Semantic search | **Implemented** | Optional local string-input `.tflite` embedder via standalone LiteRT; no cloud embedding API. |
| Encrypted semantic vectors | **Implemented** | Quantized vectors AES-GCM encrypted; only a 64-bit similarity signature remains queryable for shortlist selection. |
| Hybrid exact + semantic ranking | **Implemented** | Reciprocal-rank fusion preserves exact technical strings while admitting conceptually related evidence. |
| Local RAG / context packs | **Implemented** | Evidence-aware retrieval includes primary matches, neighboring turns, project facts and explicit contradictions. |
| Large-archive ANN/vector acceleration | **Partial** | Signature shortlist avoids decrypting every vector, but a full HNSW/ANN index is still future optimization. |

## Project Brain

| Capability | Status | Current implementation |
|---|---|---|
| Recover lost project state | **Implemented** | Projects reconstruct from conversation/repository evidence instead of a single chat. |
| Master project database | **Partial** | Structured conversations/messages/projects/insights/builds/tests/bugs/TODO/code/artifacts exist; extraction breadth will continue improving. |
| Project/repo detection | **Implemented** | GitHub repository URLs plus conversation-title signals seed project identity. |
| Requirements, decisions and hard invariants | **Implemented** | Evidence-linked local extractor plus project analyzer. |
| User authority over assistant suggestions | **Implemented** | Canonical project state prefers user-authored historical instructions; assistant suggestions never silently become user requirements. |
| Latest authoritative requirement resolution | **Implemented** | Latest same-subject evidence wins within the same authority tier; contradictions remain visible. |
| Contradiction detection | **Implemented** | Opposite-polarity same-subject insights produce explicit graph edges and report pairs. |
| Definitive canonical project specification | **Implemented** | `ProjectAnalyzer` emits copyable canonical Markdown with evidence IDs, invariants, requirements, rejected items and unresolved work. |
| “What still needs doing?” | **Implemented** | Requirements/TODOs without later completion/rejection evidence are surfaced as unresolved candidates. |
| Abandoned good ideas | **Implemented** | Conservative heuristic flags old unresolved ideas with no later same-subject implementation/test/decision evidence; explicitly labeled candidates. |
| Deduplicate repeated ideas | **Partial** | Derived subject hashing/canonicalization deduplicates many repeated facts; semantic clustering/merge UI remains. |
| Dependency / decision graph | **Partial** | Project↔conversation and contradiction edges exist; richer dependency kinds and interactive graph visualization remain. |
| Permanent concise + full project memory files | **Partial** | Canonical Markdown is generated/copyable; one-tap multi-file project memory export remains. |

## Builds, tests, regressions, and engineering intelligence

| Capability | Status | Current implementation |
|---|---|---|
| Extract APK/build references | **Implemented** | Build/APK signals become evidence-linked structured insights/artifacts. |
| Bug/test matrix | **Implemented** | Builds are paired with nearby test observations in project reports. |
| Reconstruct build timelines | **Implemented** | Cross-chat timestamps plus build/test evidence appear chronologically. |
| Find likely regression points | **Implemented** | Same-subject working→later-failure sequences are surfaced as regression candidates. |
| Recurring failure-mode analysis | **Implemented** | Initial pattern buckets include signing/install, startup/runtime crash, black screen, save failures, freezes and missing UI. |
| Preventative build checklists | **Partial** | Failure patterns exist; automatically generated project-specific checklists remain. |
| Lab notebook hypothesis→build→test→observation→conclusion | **Partial** | Evidence types/timeline provide the source data; dedicated experiment grouping/UI remains. |

## Code, knowledge, and documentation

| Capability | Status | Current implementation |
|---|---|---|
| Extract code from conversations | **Implemented** | Fenced code is stored encrypted as evidence-linked `CODE` artifacts with language labels when available. |
| Organize extracted code into reusable files | **Not yet** | Requires filename inference/dedup/versioning/export UI. |
| Extract repeated prompts/instructions/invariants | **Partial** | Hard invariants and recurring requirements are structured; dedicated reusable-instructions library remains. |
| Personal technical knowledge base | **Partial** | Search/RAG/project evidence already act as a KB; topic-note synthesis and editable notes remain. |
| Self-documenting GitHub repos | **Not yet** | Planned project pack: README/CHANGELOG/ROADMAP/KNOWN_ISSUES/DESIGN_DECISIONS generated from evidence. |
| Human-readable project export packs | **Not yet** | Planned Markdown/JSON/SQLite/code ZIP export. |

## ChatGPT / Continuity access

| Capability | Status | Current implementation |
|---|---|---|
| ChatGPT can retrieve private Brain evidence | **Implemented** | User-enabled foreground bridge binds **only** to `127.0.0.1:8765` with a rotatable 256-bit bearer token. |
| Private search from ChatGPT page | **Implemented** | Continuity floating Brain panel searches through the extension service worker; token is not exposed to page JavaScript. |
| Enrich current prompt with relevant history | **Implemented** | Context pack is inserted into the composer with an explicit historical-evidence/current-request boundary; never auto-submitted. |
| Load complete canonical project state into ChatGPT | **Implemented** | Continuity project picker calls `/v1/project/report` and inserts the canonical project brain into the current prompt. |
| Keep current conversations flowing into Brain | **Partial** | APK live-ingestion API exists; automatic DOM/event capture from Continuity still needs final capture integration. |
| Direct native ChatGPT app integration | **Not yet** | Browser Continuity path works; Android-app surface would require a supported local integration mechanism or explicit share/import workflow. |

## Privacy, audit, and public-repo safety

| Capability | Status | Current implementation |
|---|---|---|
| Public repo contains code only | **Implemented** | No archive content is required to build. |
| Repository privacy guard | **Implemented** | CI script rejects common exports, DB/vault/backup/key files and obvious private-key/API-secret patterns. |
| Disable Android cloud backup/device transfer of vault | **Implemented** | Application backup/data-extraction disabled. |
| No analytics/ads/account SDK/cloud DB | **Implemented** | None are dependencies of the APK. |
| Avoid embedding-framework telemetry | **Implemented** | Standalone LiteRT used instead of MediaPipe Tasks after documented telemetry review. |
| Archive privacy/secret audit | **Not yet** | Planned local-only scanner for secrets/account identifiers/private URLs before sharing sanitized exports. |
| Backup passphrase never stored | **Implemented** | Collected only for the operation and not persisted. |

## UI, visualization, and reporting

| Capability | Status | Current implementation |
|---|---|---|
| Polished AMOLED-first phone UI | **Implemented** | Brain/Search/Projects/Timeline/Vault surfaces with responsive Compose UI. |
| Project detail dashboard | **Implemented** | Canonical state, invariants, remaining work, contradictions, regressions, build matrix, failure patterns and assets. |
| Cross-project dashboard | **Partial** | Core counts/recent projects exist; richer health/priorities/activity dashboard remains. |
| Statistics | **Partial** | Core counts exist; activity trends/topic frequencies/build success rates and historical charts remain. |
| Dependency/timeline/relationship visualizations | **Partial** | Timeline UI exists; interactive graph/charts remain. |

## Build and distribution

| Capability | Status | Current implementation |
|---|---|---|
| Reproducible public Android build | **Configured, not yet proven by surfaced CI run** | AGP 9.4.0 / Gradle 9.6 / JDK 17 / API 37 workflow exists. The GitHub connector has not surfaced an Actions run for connector-authored pushes, so this must not be represented as compile-validated yet. |
| Debug APK CI artifact | **Configured, not yet proven by surfaced CI run** | Workflow uploads `app/build/outputs/apk/debug/*.apk` when GitHub Actions executes. |
| Release signing without committed keys | **Implemented in build config** | Release signing reads keystore path/password/alias/password from environment/secrets only. |

## Next priority order

1. Finish the periodic export-folder UI and exercise the first real import/update loop on-device.
2. Complete Continuity automatic live-message capture into `/v1/live/message`.
3. Obtain a real compiler/lint result and downloadable APK artifact; fix every build issue found.
4. Add one-tap project memory/documentation export packs.
5. Add interactive project graph + richer statistics/visualizations.
6. Add privacy audit/sanitized sharing workflow.
7. Add dedicated lab-notebook and generated engineering checklists.
8. Continue improving local extraction/semantic clustering while preserving evidence provenance and user authority.
