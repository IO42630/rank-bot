# 04: Open a browser with manual authentication handoff

## What to build

- Open a visible browser and resume navigation after user-assisted login or other access checks.

## Acceptance criteria

- [ ] Local fixtures verify authenticated navigation and manual-login handoff.
- [ ] Session expiry pauses the workflow for user assistance.
- [ ] CAPTCHA, access restrictions, and throttling pause without bypass or aggressive retries.
- [ ] Shutdown is clean, and profiles or diagnostics do not expose credentials or cookies.

## Blocked by

- [01](01-launch-cli-roles-and-exchange-validated-jobs.md) supplies browser CLI configuration and startup.

## External gates

- Live selector checks require an authorized practice session and access to the current HackerRank UI.

## Source

- `.junie/plans/java-hackerrank-agent-bot.md`, T4.

## Decisions and constraints

- Do not automate credentials, bypass CAPTCHA, or circumvent access restrictions.
- Use practice challenges only; do not navigate to contests or hiring assessments.