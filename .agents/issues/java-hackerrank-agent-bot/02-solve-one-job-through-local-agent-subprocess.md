# 02: Solve one job through a local-agent subprocess

## What to build

- Have the worker invoke a configured local command and return validated Java source or a bounded failure.
- Pass job input and output through documented per-job files.

## Acceptance criteria

- [ ] A fake agent receives the statement, starter code, platform Java version, deadline, and previous feedback.
- [ ] A fake agent can return valid Java source without Markdown fences.
- [ ] Explicit executable and argument configuration avoids shell interpolation.
- [ ] Malformed or missing output, nonzero exit, and hangs produce terminal outcomes with bounded logs.
- [ ] Timeout handling cleans up the child process.
- [ ] Documentation explains the adapter, scratch directory, and limited environment.
- [ ] Documentation states that a subprocess is not a security sandbox and browser credentials are not passed to it.

## Blocked by

- [01](01-launch-cli-roles-and-exchange-validated-jobs.md) supplies the worker CLI and validated job protocol.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T2.

## Decisions and constraints

- Java-first practice use only; no contest or hiring-assessment automation.
- Do not bypass CAPTCHA or access controls, add a direct AI API, or execute generated code locally.