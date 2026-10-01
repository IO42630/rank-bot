# 12: Resume a track run from durable progress

## What to build

- Restore a discovered queue, attempt accounting, and unfinished workflow safely after restart.

## Acceptance criteria

- [x] Restart tests preserve queue order and attempt limits.
- [x] Terminal challenges are skipped on resume.
- [x] Orphaned jobs do not accept stale results or reset the attempt budget.
- [x] Restart during submission invokes reconciliation.
- [x] Restart during approval cannot silently switch to automatic submission.
- [x] `README.md` documents both process startups, flags, adapter configuration, summaries, and recovery.
- [x] Documentation covers restricted-agent expectations and practice-only limitations.
- [x] Maven tests run, and the availability of permitted live verification is reported.

## Implementation status

Offline acceptance verified by `ChallengeRunnerTest`, `RunCheckpointTest`, `CliTest`, and
the complete Maven suite: 98 tests, zero failures/errors/skips; executable CLI help smoke checks pass.
Live verification was unavailable: no authorized practice session or real-agent wrapper was supplied.
Current HackerRank language/editor/verdict/history adapters require authorized inspection/adaptation;
offline completion does not claim live readiness or successful challenge solving.

## Blocked by

- [10](10-retry-with-feedback-and-skip-exhausted-challenges.md) supplies persisted sequential retries.
- [11](11-reconcile-interrupted-or-uncertain-submissions.md) supplies uncertain-submission reconciliation.

## External gates

- Live smoke checks require an authorized practice session and access to the current HackerRank UI.
- A real-agent smoke check requires a configured local agent command or wrapper.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T12.

## Decisions and constraints

- Resume must not repeat accepted or skipped work or blindly repeat an uncertain submission.
- Generated code is tested through HackerRank, not an unsandboxed local execution engine.