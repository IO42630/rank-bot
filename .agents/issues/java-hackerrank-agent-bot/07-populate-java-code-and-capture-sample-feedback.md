# 07: Populate Java code and capture sample feedback

## What to build

- Insert supplied Java source into the challenge editor, verify it, and report sample-test outcomes without submitting.

## Acceptance criteria

- [ ] Editor-native insertion or Selenium fallback inserts source and verifies editor contents.
- [ ] Fixtures distinguish sample pass, compile error, runtime error, and wrong answer.
- [ ] Available failure feedback is captured for a later retry.
- [ ] Missing editors and sample timeouts fail within bounded waits.
- [ ] Sample execution never triggers submission.

## Blocked by

- [06](06-extract-complete-java-solving-inputs.md) supplies the selected Java runtime and challenge/editor context.

## External gates

- Live editor checks require an authorized practice session and access to the current HackerRank UI.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T7.

## Decisions and constraints

- Preserve starter contracts and verify inserted code before running samples.
- Do not run generated code through an unsandboxed local execution engine.