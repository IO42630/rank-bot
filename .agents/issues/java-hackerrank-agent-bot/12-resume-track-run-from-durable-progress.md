# 12: Resume a track run from durable progress

## What to build

- Restore a discovered queue, attempt accounting, and unfinished workflow safely after restart.

## Acceptance criteria

- [ ] Restart tests preserve queue order and attempt limits.
- [ ] Terminal challenges are skipped on resume.
- [ ] Orphaned jobs do not accept stale results or reset the attempt budget.
- [ ] Restart during submission invokes reconciliation.
- [ ] Restart during approval cannot silently switch to automatic submission.
- [ ] `README.md` documents both process startups, flags, adapter configuration, summaries, and recovery.
- [ ] Documentation covers restricted-agent expectations and practice-only limitations.
- [ ] Maven tests run, and the availability of permitted live verification is reported.

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