package io42630.rankbot.cli;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.Callable;

import io42630.rankbot.browser.BrowserPause;
import io42630.rankbot.browser.ChallengePage;
import io42630.rankbot.browser.HackerRankSession;
import io42630.rankbot.browser.PracticeUrls;
import io42630.rankbot.browser.TrackPage;
import io42630.rankbot.jobs.JsonFiles;
import io42630.rankbot.workflow.ChallengeRunner;
import io42630.rankbot.workflow.FileSolver;
import io42630.rankbot.workflow.RunCheckpoint;
import io42630.rankbot.workflow.RunCheckpoint.Approval;
import io42630.rankbot.workflow.SubmissionGuard;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "browser", mixinStandardHelpOptions = true, description = "Visible practice browser")
public final class BrowserCommand implements Callable<Integer> {
	@Mixin
	public CommonOptions common;
	@Option(names = "--track", required = true)
	public String track;
	@Option(names = "--approval", defaultValue = "required")
	public Approval approval;
	@Option(names = "--max-attempts", defaultValue = "3")
	public int maxAttempts;
	@Option(names = "--checkpoint", defaultValue = "runtime/checkpoint.json")
	public Path checkpoint;
	@Option(names = "--profile", defaultValue = "runtime/browser-profile")
	public Path profile;
	@Option(names = "--wait-seconds", defaultValue = "20")
	public long waitSeconds;
	@Option(names = "--job-seconds", defaultValue = "240")
	public long jobSeconds;
	@Option(names = "--delay-seconds", defaultValue = "2")
	public long delaySeconds;
	@Option(names = "--resume")
	public boolean resume;
	@Option(names = "--summary", defaultValue = "runtime/summary.json")
	public Path summary;
	@Option(names = "--chrome-binary")
	public Path chromeBinary;

	@Override
	public Integer call() throws Exception {
		if (maxAttempts != 2 && maxAttempts != 3) {
			throw new IllegalArgumentException("max-attempts must be 2 or 3");
		}
		if (waitSeconds < 1 || waitSeconds > 300) {
			throw new IllegalArgumentException("wait-seconds must be 1..300");
		}
		if (jobSeconds < 1 || jobSeconds > 3600 || delaySeconds < 1 || delaySeconds > 300) {
			throw new IllegalArgumentException("job-seconds must be 1..3600 and delay-seconds 1..300");
		}
		PracticeUrls.validate(track, false);
		if (!java.net.URI.create(track).getPath().startsWith("/domains/java")) {
			throw new IllegalArgumentException("A Java-track URL is required");
		}
		if (!resume && Files.exists(checkpoint)) {
			throw new IllegalArgumentException("Checkpoint exists; use --resume or a different checkpoint path");
		}
		RunCheckpoint restored = resume
				? RunCheckpoint.load(checkpoint)
				: null;
		if (restored != null) {
			if (!restored.trackUrl().equals(track)
				|| restored.maxAttempts() != maxAttempts
				|| restored.approval() != approval) {
				throw new IllegalArgumentException("Resume must retain track, max-attempts, and approval policy");
			}
			for (var entry : restored.queue()) {
				PracticeUrls.validate(entry.url(), false);
			}
		}
		var store = common.store();
		try (var channel = FileChannel.open(
				common.jobs.resolve(".browser.lock"),
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE
		);
			 var lock = channel.tryLock()) {
			if (lock == null) {
				throw new IllegalStateException("Only one browser may use this job directory");
			}
			try (var session = HackerRankSession.open(
					profile, Duration.ofSeconds(waitSeconds), () -> {
						var console = System.console();
						return console != null && "continue".equals(console.readLine(
								"Handle login/access checks in the browser, then type continue (anything else pauses):"
								+ " "));
					}, chromeBinary
			)) {
				var initial = restored != null
						? restored
						: ChallengeRunner.start(
								track,
								new TrackPage(session, 100).discover(track),
								maxAttempts,
								approval
						);
				var page = new ChallengePage(session);
				var browser = new ChallengeRunner.Browser() {
					public ChallengePage.Extraction extract(String url) {
						return page.extract(url);
					}

					public ChallengePage.Sample test(String source, String starter) {
						return page.runSamples(source, starter);
					}

					public ChallengePage.Verdict submit(SubmissionGuard.Intent intent) {
						return page.submit(intent);
					}

					public ChallengePage.Verdict reconcile(String url, SubmissionGuard.Intent intent) {
						return page.reconcile(url, intent);
					}
				};
				var runner = new ChallengeRunner(
						initial, checkpoint, browser, new FileSolver(store), entry -> {
					var console = System.console();
					if (console == null) {
						throw new BrowserPause("Approval needs an interactive terminal; resume with the same policy");
					}
					return "yes".equals(console.readLine(
							"Samples passed for %s. Submit? Type yes to approve (anything else skips): ",
							entry.challengeId()
					));
				}, Duration.ofSeconds(jobSeconds), Duration.ofSeconds(delaySeconds)
				);
				var report = runner.run();
				JsonFiles.write(summary, report.summary());
				report.summary().forEach(item -> System.out.printf(
						"%s: %s (%d attempts)%n",
						item.challengeId(),
						item.outcome(),
						item.attempts()
				));
				if (report.paused()) {
					System.out.println("Paused: " + report.pauseReason());
					return 2;
				}
			}
		} catch (BrowserPause exception) {
			System.out.println("Paused: " + exception.getMessage());
			return 2;
		}
		return 0;
	}
}
