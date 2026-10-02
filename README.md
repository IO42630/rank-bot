# rank-bot

Java-first HackerRank **practice-only** automation with a separate local solver worker.
Never use for contests or hiring assessments, or to bypass login, CAPTCHA, access restrictions, or throttling.

- Requires Java 25 and Maven 3.9.11 (`.tool-versions`).
- Build with `mvn verify`.
The executable jar exposes `browser --help` and `worker --help` via
`java -jar bot/target/rank-bot-0.1.0-SNAPSHOT.jar`.

The Maven reactor contains `bot` (browser/worker CLI) and `solver` (standalone
Quarkus app in `com.olexyn.rank.bot.solver`). The solver has no dependency on the
bot and can be built independently with `mvn -f solver/pom.xml verify` on Java 21+.

### Olexyn repository conventions

- Maven coordinates are `com.olexyn:rank-bot:0.1.0-SNAPSHOT`.
- The parent is `com.olexyn:min-root:25.0.3`, matching Tabdriver.
  - Java settings and managed compiler/test plugin versions come from the parent.
- Dependency management imports `com.olexyn:min-bom:25.1.1`, matching Tabdriver.
  - Dependencies outside that BOM retain explicit versions.
- Selenium uses Tabdriver's `xx.selenium.version` convention and version `4.49.0`.
- The intended Java package is `com.olexyn.rankbot`.
  - Package migration is pending because the required package-refactoring operation was unavailable.
  - The existing `io42630.rankbot` entry point remains valid until that migration.
- Browser sessions are created and owned by `com.olexyn:tabdriver:1.5.1`.
  - Configuration extends `DefaultTabDriverConfig`, following the upstream test example.
  - Selenium's driver discovery supplies the executable path required by Tabdriver.
  - `switchTo().defaultContent()` provides the owned WebDriver for explicit waits and editor scripts.
  - Tabdriver's script methods remain stubs in this release; scripts use the owned WebDriver instead.
  - Certificate validation stays enabled, implicit waits are reset to zero, and shutdown calls wrapper `quit()` once.

Defaults: `--jobs runtime/jobs`, `--lease-seconds 30`; browser approval is `required`,
and `--max-attempts` accepts only 2 or 3 (default 3). Runtime files and local tools
are ignored. Share the job directory only on a local filesystem, with one browser
and one worker. Complete JSON snapshots are atomically renamed under an OS file
lock; claims have tokens and bounded leases. Expired jobs become terminal timeouts
and cannot be reclaimed or overwritten by late results.

Browser profiles contain sensitive session data: keep all runtime data private,
out of version control. Only the text sent to the solver is forwarded to DeepSeek;
do not include credentials or private data in that text.

Browser fixture tests use the same Tabdriver-backed startup as the application and require
Chrome/ChromeDriver (Selenium Manager can locate them).
For explicit binaries, pass `-Drankbot.chrome=/path/to/chrome` and
`-Dwebdriver.chrome.driver=/path/to/chromedriver` to Maven. Tests use headless Chrome
only for local HTML; the application always opens a visible browser.

Selectors are centralized in `browser/Selectors.java`. Native editor detection uses
Monaco, Ace, or CodeMirror where observable, with a textarea fallback. Local fixtures
use `data-rankbot-*` markers. Candidate HackerRank selectors are **not live verified**:
unsupported controls or incomplete statements stop extraction rather than guessing.
Production custom language controls may require adjustment after authorized inspection.
No screenshots, credentials, cookies, or page dumps are logged automatically.

### Standalone DeepSeek solver

Build and start the solver (no browser or job store required):

```sh
mvn -f solver/pom.xml verify
# For a fresh checkout, copy solver/template.env to solver/.env.
# Set DEEPSEEK_API_KEY in solver/.env before starting.
sh solver/start.sh
```

- The startup script exports settings from `solver/.env` using shell syntax.
  - Values in the file override existing environment values.
  - Without `.env`, exported variables still work.
- `solver/.env` is ignored by Git.
- `solver/template.env` lists the available settings and defaults.
- Run `solver/solver.http` in the IDE HTTP client for a smoke test with a valid DeepSeek key.
  - It also includes a direct Flash request with thinking enabled and high reasoning effort.
  - The direct request reads `DEEPSEEK_API_KEY` from the IDE process environment,
    not `solver/.env`; the local solver request needs only the running app.

The JVM flag is needed by Quarkus thread cleanup on Java 24+; it is harmless on
Java 21. Keep the complete `solver/target/quarkus-app` directory when deploying.

Send any text block as the request body; the response is the reply string, not a
job envelope or JSON:

```sh
curl --fail-with-body http://127.0.0.1:8080/solve \
  -H 'Content-Type: text/plain; charset=utf-8' \
  --data-binary 'Explain how Java records work.'
```

The app makes one non-streaming DeepSeek chat-completions request with the text
as a user message. Defaults are `deepseek-flash`,
`https://api.deepseek.com/chat/completions`, and a 120-second timeout. Override with
`DEEPSEEK_MODEL`, `DEEPSEEK_URL`, or `DEEPSEEK_TIMEOUT_SECONDS`. Blank input returns
HTTP 400, a missing API key 503, upstream errors/invalid replies 502, and timeouts
504. No retries, subprocesses, tools, or conversation state are involved.
The endpoint binds to loopback by default and has no authentication; keep it local.

For the existing browser job workflow, start the worker in another terminal:

```sh
java -jar bot/target/rank-bot-0.1.0-SNAPSHOT.jar worker --solver-url http://127.0.0.1:8080/solve
```

The worker builds a Java-source prompt from the statement, language, starter code,
and previous feedback. It keeps the job lease alive while waiting, enforces the
job deadline, and validates the returned source before storing a result.
`--once` consumes at most one available job. The former `--agent-config` and
`--scratch` options and subprocess wrapper protocol have been removed.

### Browser startup and approval

In a second interactive terminal, after starting the worker:

```sh
java -jar bot/target/rank-bot-0.1.0-SNAPSHOT.jar browser --track https://www.hackerrank.com/domains/java
```

Use a dedicated local profile, not your personal Chrome profile. Complete login or
access checks yourself in the visible browser and type `continue` in the terminal.
If the restriction remains, the run pauses; never bypass it. Each sample-passing
solution needs a typed `yes` before submission; other responses skip that challenge.
Without an interactive console, required approval pauses rather than approving or
silently rejecting the queue. For explicitly authorized unattended practice, start
a **new** run with `--approval automatic`; samples still must pass.

Options (both roles support `--help`):

| Option            | Default                    | Purpose                                                    |
|-------------------|----------------------------|------------------------------------------------------------|
| `--jobs`          | `runtime/jobs`             | Shared local job directory, same for both roles            |
| `--lease-seconds` | `30`                       | Worker heartbeat lease (3–3600 seconds)                    |
| `--solver-url`    | `http://127.0.0.1:8080/solve` | Standalone solver endpoint                              |
| `--once`          | off                        | Worker processes at most one available job                 |
| `--track`         | required for browser       | HTTPS HackerRank Java-track URL                            |
| `--approval`      | `required`                 | `required` or `automatic`                                  |
| `--max-attempts`  | `3`                        | Total per-challenge budget, only 2 or 3                    |
| `--checkpoint`    | `runtime/checkpoint.json`  | Durable ordered run state                                  |
| `--summary`       | `runtime/summary.json`     | Per-challenge outcomes and attempt counts                  |
| `--profile`       | `runtime/browser-profile`  | Private persistent Chrome profile                          |
| `--chrome-binary` | Chrome located by Selenium | Optional explicit Chrome executable                        |
| `--wait-seconds`  | `20`                       | Bounded page/editor/sample/verdict wait (1–300)            |
| `--job-seconds`   | `240`                      | Total worker job deadline (1–3600)                         |
| `--delay-seconds` | `2`                        | Conservative workflow delay (1–300)                        |
| `--resume`        | off                        | Restore the specified checkpoint rather than rediscovering |

Set ChromeDriver explicitly using `java -Dwebdriver.chrome.driver=/path/to/chromedriver -jar ...`
if needed. The application does not add anti-detection flags or automate credentials.
Only one browser and one worker may hold their role lock for a job directory.

### Recovery and summaries

```sh
java -jar bot/target/rank-bot-0.1.0-SNAPSHOT.jar browser --track https://www.hackerrank.com/domains/java --resume
```

Resume with the **same** track, approval policy, maximum attempts, job directory,
and checkpoint path. An existing checkpoint is never silently replaced by a new
run. To start an independent run, select a different checkpoint and job directory.
Queue order and terminal entries are retained. Solver/sample/confirmed submission
failures share one budget; attempts are saved before dispatch. A missing job after
that write consumes the recorded attempt rather than replaying it indefinitely.
Expired worker claims become terminal timeouts; late results are rejected. Worker
outage pauses, and resuming waits for or consumes the existing attempt, not a new
unbounded regeneration. Testing and approval restarts reinsert/read back the saved
source and rerun samples; required approval is requested again.

Submission intent is saved before clicking. Restart in `SUBMITTING` **only reconciles**;
pending/unknown evidence never causes another click. The fixture adapter requires a
unique submission ID and challenge identity; when no ID was captured, reconciliation
also requires matching source SHA-256 and submission time at/after intent. The live
history UI must be inspected and adapted to provide equivalent evidence. Until then,
unknown cases pause for manual resolution. Do not change an uncertain checkpoint to
`DISCOVERED` to force a retry. Inspect platform history first; after confirming the
outcome, a knowledgeable operator may back up and repair the checkpoint to the
corresponding terminal state, preserving identity and attempts. There is no automated
manual-resolution command in this version.

Console and JSON summaries distinguish `accepted`, `skipped`, `exhausted`, and
`unresolved`. Samples passing is never reported as acceptance. Exit status `2` means
paused; `1` means configuration/I/O failure. Ctrl+C releases role locks on process
exit. Keep runtime data together and private for recovery; JSON files are capped
at 1 MiB and the store requires atomic-rename/file-lock support.

### Verification limits

Offline tests exercise real Selenium against local HTML, local HTTP fixtures for
the worker and DeepSeek, and fake browser/solver ports for retries and restarts.
They do not prove current HackerRank selector/editor compatibility or model solving
ability. No authorized live session or DeepSeek key was supplied, so those smoke
checks were unavailable. Browser integration, especially custom language controls and
submission/history evidence, remains gated on authorized live inspection.

The Java contract guard uses JDK syntax-tree parsing only (no annotation processors,
compilation, or generated-code execution). It checks starter named types and exposed
method/constructor signatures, not semantic equivalence or Java runtime compatibility;
platform samples remain the compilation/runtime check. A full JDK is required.
