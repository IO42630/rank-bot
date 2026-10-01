# 10: Retry with feedback and skip exhausted challenges

## What to build

- Retry solver, sample, and unsuccessful-submission failures within one persisted attempt budget, then continue the
  queue.

## Acceptance criteria

- [x] The default maximum is three attempts and configuration accepts two or three.
- [x] Attempts are persisted before dispatch and shared across solver, sample, and submission failures.
- [x] Retries include available failure feedback.
- [x] Worker crashes consume their recorded attempt.
- [x] Tests prove regeneration is bounded and the queue continues after exhaustion.
- [x] Summaries distinguish exhausted from skipped or unresolved outcomes.

## Implementation status

Offline acceptance verified by `ChallengeRunnerTest`, `JobStoreTest`, and `CliTest`.
Shared attempt budgets of both two and three, retry feedback, abandoned jobs, and continuation
after exhaustion are covered; outage/access/unknown outcomes pause instead of unsafe retries.

## Blocked by

- [09](09-process-discovered-challenges-sequentially.md) supplies persisted sequential orchestration.
- [03](03-recover-abandoned-worker-jobs-safely.md) supplies bounded worker expiry and stale-result protection.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T10.

## Decisions and constraints

- Use a total per-challenge attempt limit; retries must not create unbounded agent invocations.
- Continue to later practice challenges after a challenge is exhausted.
