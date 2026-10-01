package io42630.rankbot.workflow;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import io42630.rankbot.browser.BrowserPause;
import io42630.rankbot.browser.ChallengePage;
import io42630.rankbot.browser.TrackPage;
import io42630.rankbot.jobs.JobContracts.Request;
import io42630.rankbot.jobs.JobContracts.Result;
import io42630.rankbot.jobs.JobContracts.ResultStatus;

import static io42630.rankbot.workflow.RunCheckpoint.Approval;
import static io42630.rankbot.workflow.RunCheckpoint.Entry;
import static io42630.rankbot.workflow.RunCheckpoint.State;

public final class ChallengeRunner {
	public interface Browser {
		ChallengePage.Extraction extract(String url);

		ChallengePage.Sample test(String source, String starter);

		ChallengePage.Verdict submit(SubmissionGuard.Intent intent);

		ChallengePage.Verdict reconcile(String url, SubmissionGuard.Intent intent);
	}

	public interface Solver {
		boolean available() throws IOException;

		void dispatch(Request request) throws IOException;

		Optional<Request> request(String jobId) throws IOException;

		Result await(String jobId) throws IOException, InterruptedException;
	}

	public record Summary(String challengeId, String outcome, int attempts, String feedback) {
	}

	public record Report(RunCheckpoint checkpoint, String pauseReason) {
		public boolean paused() {
			return !pauseReason.isBlank();
		}

		public List<Summary> summary() {
			return checkpoint.queue().stream().map(entry -> new Summary(
					entry.challengeId(),
					entry.terminal()
							? entry.state().name().toLowerCase(Locale.ROOT)
							: "unresolved", entry.attempt(), entry.feedback()
			)).toList();
		}
	}

	private RunCheckpoint checkpoint;
	private final Path path;
	private final Browser browser;
	private final Solver solver;
	private final Predicate<Entry> approve;
	private final Duration jobTimeout;
	private final Duration delay;

	public ChallengeRunner(
			RunCheckpoint checkpoint, Path path, Browser browser, Solver solver,
			Predicate<Entry> approve, Duration jobTimeout, Duration delay
	) {
		if (jobTimeout.isNegative() || jobTimeout.isZero() || delay.isNegative()) {
			throw new IllegalArgumentException("Invalid workflow timing");
		}
		this.checkpoint = checkpoint;
		this.path = path;
		this.browser = browser;
		this.solver = solver;
		this.approve = approve;
		this.jobTimeout = jobTimeout;
		this.delay = delay;
	}

	public static RunCheckpoint start(
			String track,
			List<TrackPage.Challenge> challenges,
			int maxAttempts,
			Approval approval
	) {
		return new RunCheckpoint(
				1, UUID.randomUUID().toString(), track, maxAttempts, approval, challenges.stream()
				.map(challenge -> new Entry(
						challenge.id(), challenge.url(), State.DISCOVERED, 0, "", "", "", null,
						""
				))
				.toList()
		);
	}

	public Report run() throws IOException, InterruptedException {
		checkpoint.save(path);
		try {
			for (int index = 0; index < checkpoint.queue().size(); index++) {
				while (!checkpoint.queue().get(index).terminal()) {
					Entry entry = checkpoint.queue().get(index);
					if (entry.state() == State.SUBMITTING) {
						var intent = new SubmissionGuard.Intent(
								entry.challengeId(),
								entry.sourceCode(),
								entry.submissionIntent(),
								entry.submissionId()
						);
						handle(index, SubmissionGuard.decision(browser.reconcile(entry.url(), intent)));
						continue;
					}
					if (entry.state() == State.DISCOVERED) {
						if (entry.attempt() >= checkpoint.maxAttempts()) {
							terminal(index, State.EXHAUSTED, entry.feedback());
							continue;
						}
						var extraction = browser.extract(entry.url());
						if (extraction.status() == ChallengePage.ExtractionStatus.UNSUPPORTED) {
							terminal(index, State.SKIPPED, extraction.diagnostic());
							continue;
						}
						if (extraction.status() != ChallengePage.ExtractionStatus.READY) {
							throw new BrowserPause(
									"Incomplete challenge extraction; inspect selectors/content before resuming");
						}
						if (!solver.available()) {
							throw new BrowserPause("Worker unavailable; start the worker and resume");
						}
						String jobId = UUID.randomUUID().toString();
						Request request = new Request(
								1,
								jobId,
								checkpoint.runId(),
								entry.challengeId(),
								entry.attempt() + 1,
								extraction.inputs().language(),
								extraction.inputs().statement(),
								extraction.inputs().starterCode(),
								entry.feedback(),
								Instant.now().plus(jobTimeout)
						);
						update(
								index,
								new Entry(
										entry.challengeId(),
										entry.url(),
										State.SOLVING,
										request.attempt(),
										jobId,
										"",
										entry.feedback(),
										null,
										""
								)
						);
						solver.dispatch(request);
						continue;
					}
					if (entry.state() == State.SOLVING) {
						var request = solver.request(entry.jobId());
						if (request.isEmpty()) {
							failed(index, "Recorded attempt has no job artifact; consumed without regeneration");
							continue;
						}
						verifyIdentity(entry, request.get());
						Result result = solver.await(entry.jobId());
						if (!entry.jobId().equals(result.jobId())) {
							throw new BrowserPause("Mismatched worker result rejected");
						}
						if (result.status() != ResultStatus.SUCCESS) {
							failed(index, result.diagnostic());
							continue;
						}
						update(
								index,
								new Entry(
										entry.challengeId(),
										entry.url(),
										State.TESTING,
										entry.attempt(),
										entry.jobId(),
										result.sourceCode(),
										"",
										null,
										""
								)
						);
						continue;
					}
					if (entry.state() == State.TESTING || entry.state() == State.AWAITING_APPROVAL) {
						var request = solver.request(entry.jobId());
						if (request.isEmpty()) {
							throw new BrowserPause("Testing input artifact missing; restore it before resuming");
						}
						verifyIdentity(entry, request.get());
						var extraction = browser.extract(entry.url());
						if (extraction.status() != ChallengePage.ExtractionStatus.READY
							|| !request.get().language().equals(extraction.inputs().language())) {
							throw new BrowserPause(
									"Challenge/runtime changed or extraction incomplete; manual inspection required");
						}
						var sample = browser.test(entry.sourceCode(), request.get().starterCode());
						if (sample.status() == ChallengePage.SampleStatus.UNKNOWN) {
							throw new BrowserPause(sample.feedback());
						}
						if (sample.status() != ChallengePage.SampleStatus.PASSED) {
							failed(index, sample.feedback());
							continue;
						}
						Entry awaiting = new Entry(
								entry.challengeId(),
								entry.url(),
								State.AWAITING_APPROVAL,
								entry.attempt(),
								entry.jobId(),
								entry.sourceCode(),
								sample.feedback(),
								null,
								""
						);
						update(index, awaiting);
						final int current = index;
						var decision = new SubmissionGuard().execute(
								entry.challengeId(), entry.sourceCode(), sample, checkpoint.approval(),
								() -> approve.test(awaiting), intent -> update(
										current, new Entry(
												entry.challengeId(),
												entry.url(),
												State.SUBMITTING,
												entry.attempt(),
												entry.jobId(),
												entry.sourceCode(),
												"",
												intent.createdAt(),
												""
										)
								), browser::submit
						);
						handle(index, decision);
					}
				}
				Thread.sleep(delay.toMillis());
			}
			return new Report(checkpoint, "");
		} catch (BrowserPause exception) {
			return new Report(checkpoint, exception.getMessage());
		} catch (org.openqa.selenium.WebDriverException exception) {
			return new Report(checkpoint, "Browser interaction interrupted; resume from the durable checkpoint");
		}
	}

	private void verifyIdentity(Entry entry, Request request) {
		if (!request.jobId().equals(entry.jobId()) || !request.runId().equals(checkpoint.runId())
			|| !request.challengeId().equals(entry.challengeId()) || request.attempt() != entry.attempt()) {
			throw new BrowserPause("Job/checkpoint identity mismatch; rejected");
		}
	}

	private void handle(int index, SubmissionGuard.Decision decision) throws IOException, InterruptedException {
		switch (decision.outcome()) {
			case ACCEPTED -> terminal(index, State.ACCEPTED, decision.feedback());
			case REJECTED -> terminal(index, State.SKIPPED, decision.feedback());
			case FAILED -> failed(index, decision.feedback());
			case PENDING, UNKNOWN -> {
				Entry entry = checkpoint.queue().get(index);
				update(
						index, new Entry(
								entry.challengeId(), entry.url(), State.SUBMITTING, entry.attempt(), entry.jobId(),
								entry.sourceCode(), decision.feedback(), entry.submissionIntent(),
								decision.submissionId().isBlank()
										? entry.submissionId()
										: decision.submissionId()
						)
				);
				throw new BrowserPause(
						"Submission pending or uncertain; reconcile or resolve manually before continuing");
			}
		}
	}

	private void failed(int index, String feedback) throws IOException, InterruptedException {
		Entry entry = checkpoint.queue().get(index);
		update(
				index, new Entry(
						entry.challengeId(),
						entry.url(),
						entry.attempt() >= checkpoint.maxAttempts()
								? State.EXHAUSTED
								: State.DISCOVERED,
						entry.attempt(),
						"",
						"",
						bounded(feedback),
						null,
						""
				)
		);
		Thread.sleep(delay.toMillis());
	}

	private void terminal(int index, State state, String feedback) throws IOException {
		Entry entry = checkpoint.queue().get(index);
		update(
				index,
				new Entry(
						entry.challengeId(),
						entry.url(),
						state,
						entry.attempt(),
						"",
						"",
						feedback.substring(0, Math.min(feedback.length(), 4000)),
						null,
						""
				)
		);
	}

	private void update(int index, Entry entry) throws IOException {
		ArrayList<Entry> queue = new ArrayList<>(checkpoint.queue());
		queue.set(index, entry);
		RunCheckpoint next = new RunCheckpoint(
				1,
				checkpoint.runId(),
				checkpoint.trackUrl(),
				checkpoint.maxAttempts(),
				checkpoint.approval(),
				queue
		);
		next.save(path);
		checkpoint = next;
	}

	private static String bounded(String feedback) {
		return feedback.substring(0, Math.min(feedback.length(), 100_000));
	}
}
