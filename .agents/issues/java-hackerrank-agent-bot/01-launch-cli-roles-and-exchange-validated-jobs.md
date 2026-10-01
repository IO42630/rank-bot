# 01: Launch CLI roles and exchange validated jobs

## What to build

- Add Java/Maven browser and worker CLI roles that exchange versioned jobs through a local filesystem store.
- Keep this slice focused on the protocol; it does not invoke a solver agent.

## Acceptance criteria

- [x] Temporary-directory tests verify request and result round-trips with schema and identity validation.
- [x] Atomic publishing and claiming prevent partial reads and duplicate claims.
- [x] Invalid schemas, mismatched identities, oversized payloads, and empty or fenced successful source are rejected.
- [x] Invalid configuration reports a clear error.
- [x] A secret-free example configuration and runtime-data exclusions are documented.

## Implementation status

Offline acceptance verified by `JobStoreTest`, `CliTest`, and `RunCheckpointTest`.
Atomic snapshots are serialized under a local OS file lock; independent-JVM tests verify exclusive claims.

## Blocked by

- None.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T1.

## Decisions and constraints

- Java-first practice use only; no contest or hiring-assessment automation.
- Do not bypass CAPTCHA or access controls, add a direct AI API, or execute generated code locally.