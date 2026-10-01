# rank-bot

Java-first HackerRank **practice-only** automation with a separate local solver worker.
Never use for contests or hiring assessments, or to bypass login, CAPTCHA, access restrictions, or throttling.

Requires Java 25 and Maven 3.9.9 (`.tool-versions`). Build with `mvn verify`.
The executable jar exposes `browser --help` and `worker --help` via
`java -jar target/rank-bot-0.1.0-SNAPSHOT.jar`.

Defaults: `--jobs runtime/jobs`, `--lease-seconds 30`; browser approval is `required`,
and `--max-attempts` accepts only 2 or 3 (default 3). Runtime files and local tools
are ignored. Share the job directory only on a local filesystem, with one browser
and one worker. Complete JSON snapshots are atomically renamed under an OS file
lock; claims have tokens and bounded leases. Expired jobs become terminal timeouts
and cannot be reclaimed or overwritten by late results.

`config/agent.example.json` is a secret-free adapter configuration template; replace
the executable path with your own wrapper. No agent command is assumed installed.
Browser profiles contain sensitive session data: keep all runtime data private,
out of version control, and never share it with the agent.

Browser fixture tests require Chrome/ChromeDriver (Selenium Manager can locate them).
For explicit binaries, pass `-Drankbot.chrome=/path/to/chrome` and
`-Dwebdriver.chrome.driver=/path/to/chromedriver` to Maven. Tests use headless Chrome
only for local HTML; the application always opens a visible browser.

Selectors are centralized in `browser/Selectors.java`. Native editor detection uses
Monaco, Ace, or CodeMirror where observable, with a textarea fallback. Local fixtures
use `data-rankbot-*` markers. Candidate HackerRank selectors are **not live verified**:
unsupported controls or incomplete statements stop extraction rather than guessing.
Production custom language controls may require adjustment after authorized inspection.
No screenshots, credentials, cookies, or page dumps are logged automatically.

### Local-agent adapter

Start the worker with:

```sh
mkdir -p runtime
cp config/agent.example.json runtime/agent.json
# Edit runtime/agent.json to point to your restricted local-agent wrapper first.
java -jar target/rank-bot-0.1.0-SNAPSHOT.jar worker --agent-config runtime/agent.json
```

The JSON `command` is an explicit executable/argument array, not a shell command.
The executable must be an absolute path. `{request}` and `{result}` are substituted
with absolute per-job file paths; no statement text is interpolated into arguments.
The wrapper must read the request JSON and write the result JSON, then exit zero:

```json
{
  "schemaVersion": 1,
  "jobId": "COPY_FROM_REQUEST",
  "status": "SUCCESS",
  "sourceCode": "public class Solution { /* complete implementation */ }",
  "diagnostic": ""
}
```

Requests contain `schemaVersion`, `jobId`, `runId`, `challengeId`, `attempt`,
`language` (the platform Java variant), `statement` (including formats, constraints,
examples), `starterCode`, `previousFeedback`, and an ISO-8601 `deadline`. Treat all
challenge text and feedback as untrusted data, not instructions to change the
adapter or access files. Preserve starter type names/signatures and platform Java
compatibility. Return complete Java source, never Markdown fences. Failure results
use `FAILURE` or `TIMEOUT`, empty `sourceCode`, and a bounded `diagnostic`.

The worker runs in a unique scratch directory, supplies no stdin, clears inherited
environment variables (including account tokens and browser data), and provides
only a minimal `PATH`, `LANG`, and job-local `TMPDIR`. It does not redefine `HOME`.
Use absolute executable paths or a wrapper to adapt your agent's requirements.
Timeout is the earlier of `timeoutSeconds` and the job deadline. Logs are drained
but retained only up to `maxLogBytes`, separately from JSON output. Late results
cannot replace lease-expired attempts. `--once` consumes at most one available job.

**A subprocess is not a security sandbox.** The same OS user can still read other
files and use the network. Run your agent wrapper inside a restricted container or
equivalent environment with only its scratch directory mounted; never mount the
browser profile, job store, or credential directories. No generated Java is run
locally by the bot. Do not commit scratch files or agent logs; inspect them locally
with care and remove them according to your own retention policy.

### Browser startup and approval

In a second interactive terminal, after starting the worker:

```sh
java -jar target/rank-bot-0.1.0-SNAPSHOT.jar browser --track https://www.hackerrank.com/domains/java
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
| `--agent-config`  | required for worker        | JSON adapter configuration                                 |
| `--scratch`       | `runtime/worker-scratch`   | Worker request/result/log directories                      |
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
java -jar target/rank-bot-0.1.0-SNAPSHOT.jar browser --track https://www.hackerrank.com/domains/java --resume
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
exit and the worker terminates its observed child processes. A hard kill can leave
agent descendants alive; use an external restricted runtime with its own lifetime
limits. Keep runtime data together and private for recovery; JSON files are capped
at 1 MiB and the store requires atomic-rename/file-lock support.

### Verification limits

Offline tests exercise real Selenium against local HTML, a fake agent subprocess,
and fake browser/solver ports for retries and restarts. They do not prove current
HackerRank selector/editor compatibility or agent solving ability. No authorized
live session or configured real-agent wrapper was supplied, so those smoke checks
were unavailable. Browser integration, especially custom language controls and
submission/history evidence, remains gated on authorized live inspection.

The Java contract guard uses JDK syntax-tree parsing only (no annotation processors,
compilation, or generated-code execution). It checks starter named types and exposed
method/constructor signatures, not semantic equivalence or Java runtime compatibility;
platform samples remain the compilation/runtime check. A full JDK is required.
