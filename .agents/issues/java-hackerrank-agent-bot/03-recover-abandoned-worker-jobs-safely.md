# 03: Recover abandoned worker jobs safely

## What to build

- Add bounded worker leases and heartbeat handling so expired attempts cannot publish authoritative results.

## Acceptance criteria

- [ ] Heartbeats maintain active leases.
- [ ] Worker termination or a missed heartbeat produces a bounded terminal outcome.
- [ ] Crash and timeout tests reject late or mismatched results.
- [ ] An expired attempt cannot be reclaimed indefinitely.

## Blocked by

- [02](02-solve-one-job-through-local-agent-subprocess.md) supplies the worker lifecycle and subprocess cleanup.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T3.

## Decisions and constraints

- A crashed worker consumes its recorded attempt; recovery must not allow unlimited regeneration.
- Java-first practice use only; no contest or hiring-assessment automation.