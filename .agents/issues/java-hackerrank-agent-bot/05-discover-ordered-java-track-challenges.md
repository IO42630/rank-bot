# 05: Discover an ordered Java-track challenge queue

## What to build

- Discover stable challenge identities from an accessible Java track and preserve their page order.

## Acceptance criteria

- [ ] Fixtures verify ordered discovery across pagination or lazy loading.
- [ ] Duplicate challenge links are removed by stable identity.
- [ ] Empty or inaccessible tracks and bounded navigation failures are reported explicitly.
- [ ] Discovery does not introduce contest or assessment navigation.

## Blocked by

- [04](04-open-browser-with-manual-authentication-handoff.md) supplies an authenticated browser session.

## External gates

- Live discovery verification requires an authorized practice session and access to the current HackerRank UI.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T5.

## Decisions and constraints

- Process accessible practice challenges from the supplied Java-track URL.
- Respect access blocks and throttling; pause rather than repeatedly retry.