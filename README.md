# Continuity Brain

**Continuity Brain** is a local-first Android knowledge system for importing, organizing, searching, and continuously updating a complete ChatGPT data export.

The repository is intentionally **code-only**. Real ChatGPT exports, conversations, attachments, derived knowledge, local indexes, encryption keys, embeddings, backups, and personal project records must never be committed here.

## Core goals

- Import an official ChatGPT export ZIP directly on-device.
- Re-import newer exports safely and incrementally: add new conversations/messages, update changed records, and skip unchanged data.
- Preserve the original conversation graph and timestamps while building a more useful canonical knowledge layer on top.
- Extract projects, requirements, decisions, bugs, test results, builds, code snippets, links, unresolved work, contradictions, and timelines.
- Search locally without uploading the archive.
- Build project-state/context packs suitable for feeding relevant private history into ChatGPT or the Continuity browser extension.
- Keep the public source repository reproducible without containing any user data.
- Provide encrypted portable Continuity Brain backups independent of the original ChatGPT export.

## Privacy boundary

Continuity Brain is designed so a public source checkout contains **zero personal archive data**.

Sensitive imported text is encrypted before persistent storage. Search uses keyed local indexes rather than requiring a plaintext copy of conversation text. Encryption material is generated on the device and protected by Android Keystore. The optional assistant bridge binds to loopback only and requires a session secret; it is off by default.

See [`docs/PRIVACY_ARCHITECTURE.md`](docs/PRIVACY_ARCHITECTURE.md) and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Project status

The initial Android foundation is being built now. The first milestone includes the encrypted local vault, export importer, incremental deduplication, local search, project/insight extraction foundations, a polished phone UI, and reproducible CI builds.

## Application ID

`com.mekromn.continuitybrain`

## License

No license has been selected yet. Until a license is added, normal copyright rules apply to the source code.
