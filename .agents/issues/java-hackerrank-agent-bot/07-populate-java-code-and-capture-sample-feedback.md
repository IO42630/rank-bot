# 07: Populate Java code and capture sample feedback

## What to build

- Insert supplied Java source into the challenge editor, verify it, and report sample-test outcomes without submitting.

## Acceptance criteria

- [x] Editor-native insertion or Selenium fallback inserts source and verifies editor contents.
- [x] Fixtures distinguish sample pass, compile error, runtime error, and wrong answer.
- [x] Available failure feedback is captured for a later retry.
- [x] Missing editors and sample timeouts fail within bounded waits.
- [x] Sample execution never triggers submission.

## Implementation status

Offline acceptance verified by `BrowserFixtureTest` and `JavaContractsTest`, including exact
editor readback, fresh-result observation, stale-pass rejection, and starter signature checks.
Live editor APIs and sample feedback selectors remain unverified pending authorized inspection.

## Blocked by

- [06](06-extract-complete-java-solving-inputs.md) supplies the selected Java runtime and challenge/editor context.

## External gates

- Live editor checks require an authorized practice session and access to the current HackerRank UI.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T7.

## Decisions and constraints

- Preserve starter contracts and verify inserted code before running samples.
- Do not run generated code through an unsandboxed local execution engine.