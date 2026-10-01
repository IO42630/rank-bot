# 05: Discover an ordered Java-track challenge queue

## What to build

- Discover stable challenge identities from an accessible Java track and preserve their page order.

## Acceptance criteria

- [x] Fixtures verify ordered discovery across pagination or lazy loading.
- [x] Duplicate challenge links are removed by stable identity.
- [x] Empty or inaccessible tracks and bounded navigation failures are reported explicitly.
- [x] Discovery does not introduce contest or assessment navigation.

## Implementation status

Offline acceptance verified by `BrowserFixtureTest`: pagination plus lazy loading, stable order,
duplicate removal, empty-track pause, and practice URL restrictions. Live track selectors and
full coverage remain unverified pending an authorized session.

## Blocked by

- [04](04-open-browser-with-manual-authentication-handoff.md) supplies an authenticated browser session.

## External gates

- Live discovery verification requires an authorized practice session and access to the current HackerRank UI.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T5.

## Decisions and constraints

- Process accessible practice challenges from the supplied Java-track URL.
- Respect access blocks and throttling; pause rather than repeatedly retry.