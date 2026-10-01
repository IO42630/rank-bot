# 06: Extract complete Java solving inputs

## What to build

- Extract a challenge statement into a complete structured solver request with its selected Java variant and starter
  contract.

## Acceptance criteria

- [x] Fixtures preserve the statement, input/output formats, constraints, and examples.
- [x] The selected Java variant and starter class or method signatures are preserved.
- [x] Missing Java support or incomplete extraction produces a structured unsupported or error outcome.
- [x] Incomplete content is never sent as a misleading solver request.

## Implementation status

Offline acceptance verified by `BrowserFixtureTest` and `JavaContractsTest`.
Standard select controls and observable editor APIs are supported; current HackerRank custom
language controls and extraction selectors require authorized inspection before live readiness.
Incomplete extraction pauses orchestration rather than spending attempts on guessed inputs.

## Blocked by

- [04](04-open-browser-with-manual-authentication-handoff.md) supplies browser navigation; track discovery is not
  required for fixture URLs.

## External gates

- Live extraction checks require an authorized practice session and access to the current HackerRank UI.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T6.

## Decisions and constraints

- Preserve the platform's required Java class names and method signatures.
- Do not assume Java 25; use the runtime selected by HackerRank.
