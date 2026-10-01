# 09: Process discovered challenges sequentially

## What to build

- Connect track discovery, worker results, sample testing, and guarded submission for one attempt per challenge.

## Acceptance criteria

- [ ] Fake-worker and browser integration tests process multiple challenges in discovery order.
- [ ] Only one challenge is active at a time, and workflow transitions are persisted.
- [ ] Unsupported challenges and approval rejection continue to the next challenge.
- [ ] Worker outage and login or access blocks pause safely.
- [ ] The summary distinguishes accepted, skipped, and unresolved outcomes.
- [ ] Sample success is never reported as acceptance.

## Blocked by

- [02](02-solve-one-job-through-local-agent-subprocess.md) supplies solver results.
- [05](05-discover-ordered-java-track-challenges.md) supplies the ordered challenge queue.
- [08](08-gate-submission-and-record-platform-verdicts.md) supplies guarded browser submission.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T9.

## Decisions and constraints

- Use one browser process, one worker, and one active challenge initially.
- Continue in discovery order unless a safety or access condition requires a pause.