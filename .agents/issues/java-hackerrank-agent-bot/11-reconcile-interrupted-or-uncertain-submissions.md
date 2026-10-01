# 11: Reconcile interrupted or uncertain submissions

## What to build

- Resolve a persisted submission intent against visible platform status or history without blindly submitting again.

## Acceptance criteria

- [x] Fixtures reconcile matching platform status or history to accepted, failed, or still-pending outcomes.
- [x] Missing or ambiguous evidence pauses for manual resolution.
- [x] Timeout and restart tests verify that reconciliation does not duplicate a submission.

## Implementation status

Offline acceptance verified by `BrowserFixtureTest` and `ChallengeRunnerTest`.
The live submission/history adapter is incomplete until authorized UI inspection maps unique
identity and source/time evidence. Missing or ambiguous evidence pauses; it never resubmits.

## Blocked by

- [08](08-gate-submission-and-record-platform-verdicts.md) supplies durable submission intent and verdict parsing.

## External gates

- Live reconciliation checks require an authorized practice session and visible platform submission history.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T11.

## Decisions and constraints

- Never blindly resubmit after a timeout or interruption.
- Keep pending and unknown outcomes distinct from failure.