# 08: Gate submission and record platform verdicts

## What to build

- Submit sample-passing code only under the configured approval policy and record the resulting platform verdict.

## Acceptance criteria

- [ ] Approval is required by default.
- [ ] Approval rejection skips the challenge without submitting.
- [ ] Automatic mode submits only after successful samples.
- [ ] Submission intent is persisted before the submit action.
- [ ] Fixtures distinguish accepted, failed, pending, and unknown verdicts.
- [ ] Unknown outcomes pause without blind resubmission.

## Blocked by

- [07](07-populate-java-code-and-capture-sample-feedback.md) supplies verified editor contents and sample results.

## External gates

- Live submission verification requires an authorized practice session and user approval when approval mode is active.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T8.

## Decisions and constraints

- Default to `--approval required`; support `--approval automatic`.
- Practice use only; never automate contests or hiring assessments.