package io42630.rankbot.workflow;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.openqa.selenium.WebDriverException;

import io42630.rankbot.browser.BrowserPause;
import io42630.rankbot.browser.ChallengePage.Extraction;
import io42630.rankbot.browser.ChallengePage.ExtractionStatus;
import io42630.rankbot.browser.ChallengePage.Inputs;
import io42630.rankbot.browser.ChallengePage.Sample;
import io42630.rankbot.browser.ChallengePage.SampleStatus;
import io42630.rankbot.browser.ChallengePage.Verdict;
import io42630.rankbot.browser.ChallengePage.VerdictStatus;
import io42630.rankbot.browser.TrackPage;
import io42630.rankbot.jobs.JobContracts.Request;
import io42630.rankbot.jobs.JobContracts.Result;
import io42630.rankbot.jobs.JobContracts.ResultStatus;

import static io42630.rankbot.workflow.RunCheckpoint.Approval;
import static io42630.rankbot.workflow.RunCheckpoint.Entry;
import static io42630.rankbot.workflow.RunCheckpoint.State;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeRunnerTest {
	private static final String SOURCE = "class Solution { public static void main(String[] args) {} }";
	private static final String STARTER = "class Solution {}";
	private static final Instant INTENT_TIME = Instant.parse("2026-01-01T00:00:00Z");
	@TempDir
	Path directory;

	@Test
	void startPreservesQueueOrderBudgetAndApproval() {
		var challenges = new ArrayList<>(List.of(challenge("b"), challenge("a")));
		var checkpoint = ChallengeRunner.start("track", challenges, 3, Approval.required);
		challenges.clear();
		assertEquals(1, checkpoint.schemaVersion());
		assertFalse(checkpoint.runId().isBlank());
		assertEquals("track", checkpoint.trackUrl());
		assertEquals(3, checkpoint.maxAttempts());
		assertEquals(Approval.required, checkpoint.approval());
		assertEquals(List.of(discovered("b", 0, ""), discovered("a", 0, "")), checkpoint.queue());
		assertThrows(UnsupportedOperationException.class, () -> checkpoint.queue().clear());
	}

	@Test
	void processesSequentiallyWithOneActiveChallengeAndDurableAttemptsBeforeDispatch() throws Exception {
		var fixture = new Fixture(fresh(2, Approval.automatic, "b", "a", "c"));
		Instant before = Instant.now();
		var report = fixture.run();
		Instant after = Instant.now();
		assertFalse(report.paused());
		assertEquals(
				List.of(
						"extract:b", "available", "dispatch:b:1", "request:b:1", "await:b:1",
						"request:b:1", "extract:b", "test:b", "submit:b",
						"extract:a", "available", "dispatch:a:1", "request:a:1", "await:a:1",
						"request:a:1", "extract:a", "test:a", "submit:a",
						"extract:c", "available", "dispatch:c:1", "request:c:1", "await:c:1",
						"request:c:1", "extract:c", "test:c", "submit:c"
				), fixture.events
		);
		assertEquals(List.of("b", "a", "c"), fixture.solver.dispatched.stream().map(Request::challengeId).toList());
		assertEquals(3, fixture.solver.dispatched.stream().map(Request::jobId).distinct().count());
		for (Request request : fixture.solver.dispatched) {
			assertEquals(report.checkpoint().runId(), request.runId());
			assertEquals(1, request.attempt());
			assertEquals("Java 17", request.language());
			assertEquals("Statement for " + request.challengeId(), request.statement());
			assertEquals(STARTER, request.starterCode());
			assertEquals("", request.previousFeedback());
			assertFalse(request.deadline().isBefore(before.plusSeconds(30)));
			assertFalse(request.deadline().isAfter(after.plusSeconds(30)));
		}
		assertEquals(List.of("accepted", "accepted", "accepted"), outcomes(report));
		assertEquals(report.checkpoint(), fixture.saved());
	}

	@ParameterizedTest
	@CsvSource({ "2,SOLVER", "3,SOLVER", "2,SAMPLE", "3,SAMPLE", "2,SUBMISSION", "3,SUBMISSION" })
	void eachFailureStageSharesTheTotalBudgetAndExhaustionContinuesTheQueue(int maximum, FailureStage stage)
			throws Exception {
		var fixture = new Fixture(fresh(maximum, Approval.automatic, "a", "b"));
		fixture.solver.result = request -> request.challengeId().equals("a") && stage == FailureStage.SOLVER
				? failure(request, "solver failed " + request.attempt())
				: success(request);
		fixture.browser.samples =
				(source, starter) -> fixture.browser.current.equals("a") && stage == FailureStage.SAMPLE
						? new Sample(SampleStatus.WRONG_ANSWER, "sample failed " + fixture.savedEntry("a").attempt())
						: passed();
		fixture.browser.submission = intent -> intent.challengeId().equals("a") && stage == FailureStage.SUBMISSION
				? new Verdict(
				VerdictStatus.FAILED,
				"submission failed " + fixture.savedEntry("a").attempt(),
				"failed-id"
		)
				: accepted();
		var report = fixture.run();
		assertFalse(report.paused());
		assertEquals(List.of("exhausted", "accepted"), outcomes(report));
		assertEquals(maximum, report.summary().getFirst().attempts());
		assertEquals(1, report.summary().get(1).attempts());
		var requests =
				fixture.solver.dispatched.stream().filter(request -> request.challengeId().equals("a")).toList();
		assertEquals(maximum, requests.size());
		for (int index = 0; index < maximum; index++) {
			assertEquals(index + 1, requests.get(index).attempt());
			assertEquals(
					index == 0
							? ""
							: stage.name().toLowerCase(Locale.ROOT) + " failed " + index,
					requests.get(index).previousFeedback()
			);
		}
		assertEquals(
				stage.name().toLowerCase(Locale.ROOT) + " failed " + maximum,
				report.summary().getFirst().feedback()
		);
		assertEquals(
				stage == FailureStage.SOLVER
						? 1
						: maximum + 1, fixture.browser.tested.size()
		);
		assertEquals(
				stage == FailureStage.SUBMISSION
						? maximum + 1
						: 1, fixture.browser.submitted.size()
		);
		assertEquals(report.checkpoint(), fixture.saved());
	}

	@ParameterizedTest
	@ValueSource(ints = { 2, 3 })
	void differentFailureStagesDoNotResetTheAttemptBudget(int maximum) throws Exception {
		var fixture = new Fixture(fresh(maximum, Approval.automatic, "a", "b"));
		fixture.solver.result = request -> request.challengeId().equals("a") && request.attempt() == 1
				? failure(request, "solver feedback")
				: success(request);
		fixture.browser.samples =
				(source, starter) -> fixture.browser.current.equals("a") && fixture.savedEntry("a").attempt() == 2
						? new Sample(SampleStatus.COMPILE_ERROR, "sample feedback")
						: passed();
		fixture.browser.submission = intent -> intent.challengeId().equals("a")
				? new Verdict(VerdictStatus.FAILED, "submission feedback", "failed-id")
				: accepted();
		var report = fixture.run();
		assertEquals(List.of("exhausted", "accepted"), outcomes(report));
		var requests =
				fixture.solver.dispatched.stream().filter(request -> request.challengeId().equals("a")).toList();
		assertEquals(maximum, requests.size());
		assertEquals("solver feedback", requests.get(1).previousFeedback());
		if (maximum == 3) {
			assertEquals("sample feedback", requests.get(2).previousFeedback());
		}
		assertEquals(
				maximum == 2
						? "sample feedback"
						: "submission feedback", report.summary().getFirst().feedback()
		);
	}

	@ParameterizedTest
	@EnumSource(value = ResultStatus.class, names = { "FAILURE", "TIMEOUT" })
	void failedWorkerResultFeedsTheNextRequest(ResultStatus status) throws Exception {
		var fixture = new Fixture(fresh(2, Approval.automatic, "a"));
		fixture.solver.result = request -> request.attempt() == 1
				? new Result(1, request.jobId(), status, "", "worker diagnostic")
				: success(request);
		var report = fixture.run();
		assertEquals(List.of("accepted"), outcomes(report));
		assertEquals(2, report.summary().getFirst().attempts());
		assertEquals("worker diagnostic", fixture.solver.dispatched.get(1).previousFeedback());
		assertEquals(1, fixture.browser.tested.size());
		assertEquals(1, fixture.browser.submitted.size());
	}

	@ParameterizedTest
	@EnumSource(value = SampleStatus.class,
				names = { "COMPILE_ERROR", "RUNTIME_ERROR", "WRONG_ANSWER", "INVALID_SOURCE" })
	void sampleFailuresRetryWithoutSubmittingFailedSource(SampleStatus status) throws Exception {
		var fixture = new Fixture(fresh(2, Approval.automatic, "a"));
		fixture.browser.samples = (source, starter) -> fixture.savedEntry("a").attempt() == 1
				? new Sample(status, "sample diagnostic")
				: passed();
		var report = fixture.run();
		assertEquals(List.of("accepted"), outcomes(report));
		assertEquals("sample diagnostic", fixture.solver.dispatched.get(1).previousFeedback());
		assertEquals(2, fixture.browser.tested.size());
		assertEquals(1, fixture.browser.submitted.size());
	}

	@Test
	void unsupportedChallengeSkipsWithoutWorkerDispatchAndContinues() throws Exception {
		var fixture = new Fixture(fresh(2, Approval.automatic, "a", "b"));
		fixture.browser.extraction = id -> id.equals("a")
				? new Extraction(ExtractionStatus.UNSUPPORTED, null, "No Java runtime")
				: ready(id);
		var report = fixture.run();
		assertFalse(report.paused());
		assertEquals(List.of("skipped", "accepted"), outcomes(report));
		assertEquals(0, report.summary().getFirst().attempts());
		assertEquals("No Java runtime", report.summary().getFirst().feedback());
		assertEquals(List.of("b"), fixture.solver.dispatched.stream().map(Request::challengeId).toList());
		assertEquals(1, fixture.solver.availabilityChecks);
	}

	@Test
	void rejectedApprovalSkipsWithoutSubmittingAndContinues() throws Exception {
		var fixture = new Fixture(fresh(2, Approval.required, "a", "b"));
		List<Entry> prompts = new ArrayList<>();
		fixture.approval = entry -> {
			prompts.add(entry);
			return entry.challengeId().equals("b");
		};
		var report = fixture.run();
		assertEquals(List.of("skipped", "accepted"), outcomes(report));
		assertEquals(List.of("a", "b"), prompts.stream().map(Entry::challengeId).toList());
		assertTrue(prompts.stream()
				.allMatch(entry -> entry.state() == State.AWAITING_APPROVAL && entry.attempt() == 1));
		assertEquals("Submission not approved", report.summary().getFirst().feedback());
		assertEquals(
				List.of("b"),
				fixture.browser.submitted.stream().map(SubmissionGuard.Intent::challengeId).toList()
		);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void unavailableWorkerNeverRecordsANewAttempt(boolean afterFailure) throws Exception {
		var fixture = new Fixture(fresh(2, Approval.automatic, "a", "b"));
		fixture.solver.available = () -> afterFailure && fixture.solver.availabilityChecks == 1;
		fixture.solver.result = request -> failure(request, "retained failure");
		var report = fixture.run();
		assertTrue(report.paused());
		assertTrue(report.pauseReason().contains("Worker unavailable"));
		assertEquals(
				afterFailure
						? 1
						: 0, report.summary().getFirst().attempts()
		);
		assertEquals(
				afterFailure
						? 1
						: 0, fixture.solver.dispatched.size()
		);
		assertEquals(
				afterFailure
						? "retained failure"
						: "", report.summary().getFirst().feedback()
		);
		assertEquals(State.DISCOVERED, report.checkpoint().queue().getFirst().state());
		assertEquals(discovered("b", 0, ""), report.checkpoint().queue().get(1));
		assertEquals(List.of("unresolved", "unresolved"), outcomes(report));
		assertEquals(report.checkpoint(), fixture.saved());
	}

	@ParameterizedTest
	@ValueSource(strings = { "Login required", "Access denied" })
	void loginOrAccessPauseDoesNotSpendAttemptsOrAdvanceQueue(String reason) throws Exception {
		var fixture = new Fixture(fresh(2, Approval.automatic, "a", "b"));
		fixture.browser.extraction = id -> {
			throw new BrowserPause(reason);
		};
		var report = fixture.run();
		assertEquals(reason, report.pauseReason());
		assertEquals(fixture.initial, report.checkpoint());
		assertEquals(fixture.initial, fixture.saved());
		assertTrue(fixture.solver.dispatched.isEmpty());
		assertEquals(0, fixture.solver.availabilityChecks);
		assertTrue(fixture.browser.submitted.isEmpty());
	}

	@Test
	void incompleteExtractionPausesRatherThanSkippingOrSpendingBudget() throws Exception {
		var fixture = new Fixture(fresh(2, Approval.automatic, "a", "b"));
		fixture.browser.extraction = id -> new Extraction(ExtractionStatus.INCOMPLETE, null, "missing statement");
		var report = fixture.run();
		assertTrue(report.paused());
		assertEquals(fixture.initial, report.checkpoint());
		assertTrue(fixture.solver.dispatched.isEmpty());
	}

	@Test
	void summaryDistinguishesAcceptedSkippedExhaustedAndUnresolved() throws Exception {
		var fixture = new Fixture(fresh(2, Approval.automatic, "a", "b", "c", "d"));
		fixture.browser.extraction = id -> switch (id) {
			case "b" -> new Extraction(ExtractionStatus.UNSUPPORTED, null, "unsupported");
			case "d" -> new Extraction(ExtractionStatus.INCOMPLETE, null, "incomplete");
			default -> ready(id);
		};
		fixture.solver.result = request -> request.challengeId().equals("c")
				? failure(request, "failed")
				: success(request);
		var report = fixture.run();
		assertTrue(report.paused());
		assertEquals(
				List.of(
						new ChallengeRunner.Summary("a", "accepted", 1, "Accepted"),
						new ChallengeRunner.Summary("b", "skipped", 0, "unsupported"),
						new ChallengeRunner.Summary("c", "exhausted", 2, "failed"),
						new ChallengeRunner.Summary("d", "unresolved", 0, "")
				), report.summary()
		);
		assertEquals(report.checkpoint(), fixture.saved());
	}

	@Test
	void solvingRestartUsesRecordedJobResultEvenAtLastAttempt() throws Exception {
		var entry = active("a", State.SOLVING, 2);
		var fixture = new Fixture(checkpoint(2, Approval.automatic, entry));
		fixture.solver.store(request(fixture.initial, entry));
		var report = fixture.resume();
		assertFalse(report.paused());
		assertEquals(List.of("accepted"), outcomes(report));
		assertEquals(2, report.summary().getFirst().attempts());
		assertTrue(fixture.solver.dispatched.isEmpty());
		assertEquals(0, fixture.solver.availabilityChecks);
		assertEquals(List.of(entry.jobId()), fixture.solver.awaited);
		assertEquals(List.of(SOURCE), fixture.browser.tested);
		assertEquals(SOURCE, fixture.browser.submitted.getFirst().sourceCode());
	}

	@Test
	void solvingRestartFailureAtLastAttemptExhaustsAndContinues() throws Exception {
		var entry = active("a", State.SOLVING, 2);
		var fixture = new Fixture(checkpoint(2, Approval.automatic, entry, discovered("b", 0, "")));
		fixture.solver.store(request(fixture.initial, entry));
		fixture.solver.result = request -> request.challengeId().equals("a")
				? failure(request, "stored failure")
				: success(request);
		var report = fixture.resume();
		assertEquals(List.of("exhausted", "accepted"), outcomes(report));
		assertEquals(List.of("b"), fixture.solver.dispatched.stream().map(Request::challengeId).toList());
		assertEquals("stored failure", report.summary().getFirst().feedback());
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 2 })
	void missingSolvingArtifactConsumesRecordedAttemptWithoutRegeneratingIt(int attempt) throws Exception {
		var entry = active("a", State.SOLVING, attempt);
		var fixture = new Fixture(checkpoint(2, Approval.automatic, entry, discovered("b", 0, "")));
		var report = fixture.resume();
		assertFalse(report.paused());
		assertEquals(
				List.of(
						attempt == 1
								? "accepted"
								: "exhausted", "accepted"
				), outcomes(report)
		);
		assertEquals(2, report.summary().getFirst().attempts());
		assertFalse(fixture.solver.awaited.contains(entry.jobId()));
		var retries = fixture.solver.dispatched.stream().filter(job -> job.challengeId().equals("a")).toList();
		if (attempt == 1) {
			assertEquals(1, retries.size());
			assertEquals(2, retries.getFirst().attempt());
			assertNotEquals(entry.jobId(), retries.getFirst().jobId());
			assertEquals(
					"Recorded attempt has no job artifact; consumed without regeneration",
					retries.getFirst().previousFeedback()
			);
		} else {
			assertTrue(retries.isEmpty());
			assertEquals(
					"Recorded attempt has no job artifact; consumed without regeneration",
					report.summary().getFirst().feedback()
			);
		}
	}

	@Test
	void testingRestartRerunsSamplesWithStoredSourceAndStarterWithoutSolverRegeneration() throws Exception {
		var entry = active("a", State.TESTING, 2);
		var fixture = new Fixture(checkpoint(2, Approval.automatic, entry));
		fixture.solver.store(request(fixture.initial, entry));
		fixture.browser.samples = (source, starter) -> {
			assertEquals(entry.sourceCode(), source);
			assertEquals(STARTER, starter);
			return passed();
		};
		var report = fixture.resume();
		assertEquals(List.of("accepted"), outcomes(report));
		assertEquals(2, report.summary().getFirst().attempts());
		assertEquals(List.of(SOURCE), fixture.browser.tested);
		assertTrue(fixture.solver.dispatched.isEmpty());
		assertTrue(fixture.solver.awaited.isEmpty());
		assertEquals(0, fixture.solver.availabilityChecks);
	}

	@Test
	void awaitingApprovalRestartRepromptsForSameSourceAndStoredRequiredPolicy() throws Exception {
		var fixture = new Fixture(fresh(2, Approval.required, "a"));
		List<Entry> prompts = new ArrayList<>();
		fixture.approval = entry -> {
			prompts.add(entry);
			throw new BrowserPause("approval interrupted");
		};
		var paused = fixture.run();
		assertEquals("approval interrupted", paused.pauseReason());
		Entry stored = fixture.savedEntry("a");
		assertEquals(State.AWAITING_APPROVAL, stored.state());
		assertEquals(Approval.required, fixture.saved().approval());
		assertTrue(fixture.browser.submitted.isEmpty());
		fixture.approval = entry -> {
			prompts.add(entry);
			return true;
		};
		var report = fixture.resume();
		assertFalse(report.paused());
		assertEquals(List.of("accepted"), outcomes(report));
		assertEquals(List.of(stored, stored), prompts);
		assertEquals(1, fixture.solver.dispatched.size());
		assertEquals(1, fixture.solver.awaited.size());
		assertEquals(2, fixture.browser.tested.size());
		assertEquals(1, fixture.browser.submitted.size());
	}

	@ParameterizedTest
	@EnumSource(value = State.class, names = { "TESTING", "AWAITING_APPROVAL" })
	void missingTestingArtifactPausesWithoutRegeneratingSourceOrSpendingBudget(State state) throws Exception {
		var entry = active("a", state, 1);
		var fixture = new Fixture(checkpoint(2, Approval.required, entry));
		var report = fixture.resume();
		assertTrue(report.paused());
		assertTrue(report.pauseReason().contains("Testing input artifact missing"));
		assertEquals(fixture.initial, report.checkpoint());
		assertTrue(fixture.solver.dispatched.isEmpty());
		assertTrue(fixture.solver.awaited.isEmpty());
		assertTrue(fixture.browser.tested.isEmpty());
		assertTrue(fixture.browser.submitted.isEmpty());
	}

	@ParameterizedTest
	@EnumSource(VerdictStatus.class)
	void submittingRestartReconcilesWithoutAnotherSubmitClick(VerdictStatus status) throws Exception {
		var entry = active("a", State.SUBMITTING, 2);
		var fixture = new Fixture(checkpoint(2, Approval.required, entry, discovered("b", 0, "")));
		fixture.browser.reconciliation = intent -> {
			assertEquals(new SubmissionGuard.Intent("a", SOURCE, INTENT_TIME, "existing-id"), intent);
			return new Verdict(status, "reconciled " + status, "observed-id");
		};
		var report = fixture.resume();
		assertEquals(
				List.of(new SubmissionGuard.Intent("a", SOURCE, INTENT_TIME, "existing-id")),
				fixture.browser.reconciled
		);
		assertFalse(fixture.browser.submitted.stream().anyMatch(intent -> intent.challengeId().equals("a")));
		assertFalse(fixture.solver.dispatched.stream().anyMatch(request -> request.challengeId().equals("a")));
		assertEquals(2, report.summary().getFirst().attempts());
		assertEquals("reconciled " + status, report.summary().getFirst().feedback());
		if (status == VerdictStatus.ACCEPTED || status == VerdictStatus.FAILED) {
			assertFalse(report.paused());
			assertEquals(
					List.of(
							status == VerdictStatus.ACCEPTED
									? "accepted"
									: "exhausted", "accepted"
					), outcomes(report)
			);
			assertEquals(
					List.of("b"),
					fixture.browser.submitted.stream().map(SubmissionGuard.Intent::challengeId).toList()
			);
		} else {
			assertTrue(report.paused());
			assertEquals(List.of("unresolved", "unresolved"), outcomes(report));
			var saved = fixture.savedEntry("a");
			assertEquals(State.SUBMITTING, saved.state());
			assertEquals(INTENT_TIME, saved.submissionIntent());
			assertEquals("observed-id", saved.submissionId());
			assertEquals(SOURCE, saved.sourceCode());
			assertTrue(fixture.browser.submitted.isEmpty());
			assertTrue(fixture.solver.dispatched.isEmpty());
			assertEquals(discovered("b", 0, ""), fixture.savedEntry("b"));
		}
		assertEquals(report.checkpoint(), fixture.saved());
	}

	@Test
	void reconciledFailureUsesRemainingBudgetAndFeedbackRatherThanReclickingOldIntent() throws Exception {
		var entry = active("a", State.SUBMITTING, 1);
		var fixture = new Fixture(checkpoint(2, Approval.automatic, entry));
		fixture.browser.reconciliation =
				intent -> new Verdict(VerdictStatus.FAILED, "old submission failed", "existing-id");
		var report = fixture.resume();
		assertEquals(List.of("accepted"), outcomes(report));
		assertEquals(2, report.summary().getFirst().attempts());
		assertEquals(1, fixture.browser.reconciled.size());
		assertEquals(1, fixture.solver.dispatched.size());
		assertEquals(2, fixture.solver.dispatched.getFirst().attempt());
		assertEquals("old submission failed", fixture.solver.dispatched.getFirst().previousFeedback());
		assertNotEquals(entry.jobId(), fixture.solver.dispatched.getFirst().jobId());
		assertEquals(1, fixture.browser.submitted.size());
		assertNotEquals(INTENT_TIME, fixture.browser.submitted.getFirst().createdAt());
	}

	@ParameterizedTest
	@EnumSource(value = VerdictStatus.class, names = { "PENDING", "UNKNOWN" })
	void uncertainLiveSubmissionPausesAndResumeOnlyReconciles(VerdictStatus status) throws Exception {
		var fixture = new Fixture(fresh(2, Approval.automatic, "a", "b"));
		fixture.browser.submission = intent -> new Verdict(status, "uncertain", "pending-id");
		var paused = fixture.run();
		assertTrue(paused.paused());
		var saved = fixture.savedEntry("a");
		assertEquals(State.SUBMITTING, saved.state());
		assertEquals("pending-id", saved.submissionId());
		assertEquals(fixture.browser.submitted.getFirst().createdAt(), saved.submissionIntent());
		assertEquals(discovered("b", 0, ""), fixture.savedEntry("b"));
		fixture.browser.submission = intent -> accepted();
		var report = fixture.resume();
		assertEquals(List.of("accepted", "accepted"), outcomes(report));
		assertEquals(
				List.of("a", "b"),
				fixture.browser.submitted.stream().map(SubmissionGuard.Intent::challengeId).toList()
		);
		assertEquals(
				new SubmissionGuard.Intent("a", SOURCE, saved.submissionIntent(), "pending-id"),
				fixture.browser.reconciled.getFirst()
		);
		assertEquals(2, fixture.solver.dispatched.size());
	}

	@Test
	void browserInterruptionDuringSubmitRetainsIntentForSafeReconciliation() throws Exception {
		var fixture = new Fixture(fresh(2, Approval.automatic, "a"));
		fixture.browser.submission = intent -> {
			throw new WebDriverException("browser disconnected");
		};
		var paused = fixture.run();
		assertTrue(paused.paused());
		assertTrue(paused.pauseReason().contains("Browser interaction interrupted"));
		var saved = fixture.savedEntry("a");
		assertEquals(State.SUBMITTING, saved.state());
		assertNotNull(saved.submissionIntent());
		assertEquals("", saved.submissionId());
		var report = fixture.resume();
		assertEquals(List.of("accepted"), outcomes(report));
		assertEquals(1, fixture.browser.submitted.size());
		assertEquals(1, fixture.browser.reconciled.size());
		assertEquals(1, fixture.solver.dispatched.size());
	}

	@Test
	void terminalEntriesAreUntouchedOnResume() throws Exception {
		var accepted = new Entry("a", url("a"), State.ACCEPTED, 1, "", "", "accepted before restart", null, "");
		var skipped = new Entry("b", url("b"), State.SKIPPED, 0, "", "", "unsupported before restart", null, "");
		var exhausted = new Entry("c", url("c"), State.EXHAUSTED, 2, "", "", "budget spent", null, "");
		var fixture =
				new Fixture(checkpoint(2, Approval.required, accepted, skipped, exhausted, discovered("d", 0, "")));
		var report = fixture.resume();
		assertFalse(report.paused());
		assertEquals(List.of(accepted, skipped, exhausted), report.checkpoint().queue().subList(0, 3));
		assertEquals(List.of("accepted", "skipped", "exhausted", "accepted"), outcomes(report));
		assertEquals(List.of("d"), fixture.solver.dispatched.stream().map(Request::challengeId).toList());
		assertEquals(List.of("d", "d"), fixture.browser.extracted);
		assertTrue(fixture.browser.reconciled.isEmpty());
	}

	@Test
	void mismatchedResultIsRejectedWithoutTestingOrAdvancingQueue() throws Exception {
		var fixture = new Fixture(fresh(2, Approval.automatic, "a", "b"));
		fixture.solver.result = request -> new Result(1, "wrong-job", ResultStatus.SUCCESS, SOURCE, "");
		var report = fixture.run();
		assertTrue(report.paused());
		assertTrue(report.pauseReason().contains("Mismatched worker result rejected"));
		assertEquals(State.SOLVING, fixture.savedEntry("a").state());
		assertEquals(1, fixture.savedEntry("a").attempt());
		assertEquals(discovered("b", 0, ""), fixture.savedEntry("b"));
		assertTrue(fixture.browser.tested.isEmpty());
		assertTrue(fixture.browser.submitted.isEmpty());
		assertEquals(1, fixture.solver.dispatched.size());
	}

	@ParameterizedTest
	@CsvSource({ "SOLVING,job", "SOLVING,run", "SOLVING,challenge", "SOLVING,attempt",
				 "TESTING,job", "TESTING,run", "TESTING,challenge", "TESTING,attempt",
				 "AWAITING_APPROVAL,job", "AWAITING_APPROVAL,run", "AWAITING_APPROVAL,challenge",
				 "AWAITING_APPROVAL,attempt" })
	void mismatchedRequestIdentityIsRejectedBeforeUsingArtifact(State state, String mismatch) throws Exception {
		var entry = active("a", state, 1);
		var fixture = new Fixture(checkpoint(2, Approval.required, entry, discovered("b", 0, "")));
		var valid = request(fixture.initial, entry);
		var invalid = new Request(
				1,
				mismatch.equals("job")
						? "other-job"
						: valid.jobId(),
				mismatch.equals("run")
						? "other-run"
						: valid.runId(),
				mismatch.equals("challenge")
						? "other-challenge"
						: valid.challengeId(),
				mismatch.equals("attempt")
						? 2
						: valid.attempt(),
				valid.language(),
				valid.statement(),
				valid.starterCode(),
				valid.previousFeedback(),
				valid.deadline()
		);
		fixture.solver.artifacts.put(entry.jobId(), invalid);
		var report = fixture.resume();
		assertTrue(report.paused());
		assertTrue(report.pauseReason().contains("identity mismatch"));
		assertEquals(fixture.initial, report.checkpoint());
		assertEquals(fixture.initial, fixture.saved());
		assertTrue(fixture.solver.dispatched.isEmpty());
		assertTrue(fixture.solver.awaited.isEmpty());
		assertTrue(fixture.browser.extracted.isEmpty());
		assertTrue(fixture.browser.tested.isEmpty());
		assertTrue(fixture.browser.submitted.isEmpty());
	}

	@Test
	void unknownSamplePausesInTestingAndResumeDoesNotRegenerate() throws Exception {
		var fixture = new Fixture(fresh(2, Approval.automatic, "a", "b"));
		fixture.browser.samples = (source, starter) -> new Sample(SampleStatus.UNKNOWN, "sample outcome uncertain");
		var paused = fixture.run();
		assertEquals("sample outcome uncertain", paused.pauseReason());
		assertEquals(State.TESTING, fixture.savedEntry("a").state());
		assertEquals(1, fixture.savedEntry("a").attempt());
		assertEquals(SOURCE, fixture.savedEntry("a").sourceCode());
		assertTrue(fixture.browser.submitted.isEmpty());
		fixture.browser.samples = (source, starter) -> passed();
		var report = fixture.resume();
		assertEquals(List.of("accepted", "accepted"), outcomes(report));
		assertEquals(2, fixture.solver.dispatched.size());
		assertEquals(2, fixture.solver.awaited.size());
		assertEquals(3, fixture.browser.tested.size());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void changedRuntimeOrIncompleteExtractionDuringTestingPausesWithoutSpendingBudget(boolean incomplete)
			throws Exception {
		var entry = active("a", State.TESTING, 1);
		var fixture = new Fixture(checkpoint(2, Approval.automatic, entry));
		fixture.solver.store(request(fixture.initial, entry));
		fixture.browser.extraction = id -> incomplete
				? new Extraction(ExtractionStatus.INCOMPLETE, null, "missing content")
				: new Extraction(ExtractionStatus.READY, new Inputs("Java 21", "changed", STARTER), "");
		var report = fixture.resume();
		assertTrue(report.paused());
		assertEquals(fixture.initial, report.checkpoint());
		assertTrue(fixture.browser.tested.isEmpty());
		assertTrue(fixture.browser.submitted.isEmpty());
		assertTrue(fixture.solver.dispatched.isEmpty());
	}

	private enum FailureStage {
		SOLVER,
		SAMPLE,
		SUBMISSION
	}

	private static String url(String id) {
		return "https://www.hackerrank.com/challenges/" + id + "/problem";
	}

	private static TrackPage.Challenge challenge(String id) {
		return new TrackPage.Challenge(id, url(id));
	}

	private static Entry discovered(String id, int attempt, String feedback) {
		return new Entry(id, url(id), State.DISCOVERED, attempt, "", "", feedback, null, "");
	}

	private static Entry active(String id, State state, int attempt) {
		return new Entry(
				id,
				url(id),
				state,
				attempt,
				"job-" + id,
				state == State.SOLVING
						? ""
						: SOURCE,
				"stored feedback",
				state == State.SUBMITTING
						? INTENT_TIME
						: null,
				state == State.SUBMITTING
						? "existing-id"
						: ""
		);
	}

	private static RunCheckpoint fresh(int maximum, Approval approval, String... ids) {
		return ChallengeRunner.start(
				"track",
				Arrays.stream(ids).map(ChallengeRunnerTest::challenge).toList(),
				maximum,
				approval
		);
	}

	private static RunCheckpoint checkpoint(int maximum, Approval approval, Entry... entries) {
		return new RunCheckpoint(1, "run", "track", maximum, approval, List.of(entries));
	}

	private static Request request(RunCheckpoint checkpoint, Entry entry) {
		return new Request(
				1,
				entry.jobId(),
				checkpoint.runId(),
				entry.challengeId(),
				entry.attempt(),
				"Java 17",
				"Statement for " + entry.challengeId(),
				STARTER,
				entry.feedback(),
				Instant.now().plusSeconds(30)
		);
	}

	private static Extraction ready(String id) {
		return new Extraction(ExtractionStatus.READY, new Inputs("Java 17", "Statement for " + id, STARTER), "");
	}

	private static Sample passed() {
		return new Sample(SampleStatus.PASSED, "samples passed");
	}

	private static Verdict accepted() {
		return new Verdict(VerdictStatus.ACCEPTED, "Accepted", "accepted-id");
	}

	private static Result success(Request request) {
		return new Result(1, request.jobId(), ResultStatus.SUCCESS, SOURCE, "");
	}

	private static Result failure(Request request, String feedback) {
		return new Result(1, request.jobId(), ResultStatus.FAILURE, "", feedback);
	}

	private static List<String> outcomes(ChallengeRunner.Report report) {
		return report.summary().stream().map(ChallengeRunner.Summary::outcome).toList();
	}

	private final class Fixture {
		final RunCheckpoint initial;
		final Path path = directory.resolve("checkpoint.json");
		final List<String> events = new ArrayList<>();
		final FakeBrowser browser = new FakeBrowser();
		final FakeSolver solver = new FakeSolver();
		Predicate<Entry> approval = entry -> true;

		Fixture(RunCheckpoint initial) {
			this.initial = initial;
		}

		ChallengeRunner.Report run() throws Exception {
			return runner(initial).run();
		}

		ChallengeRunner.Report resume() throws Exception {
			if (!java.nio.file.Files.exists(path)) {
				initial.save(path);
			}
			return runner(RunCheckpoint.load(path)).run();
		}

		ChallengeRunner runner(RunCheckpoint checkpoint) {
			return new ChallengeRunner(
					checkpoint, path, browser, solver, entry -> {
				assertEquals(entry, savedEntry(entry.challengeId()));
				assertEquals(State.AWAITING_APPROVAL, entry.state());
				events.add("approve:" + entry.challengeId());
				return approval.test(entry);
			}, Duration.ofSeconds(30), Duration.ZERO
			);
		}

		RunCheckpoint saved() {
			try {
				return RunCheckpoint.load(path);
			} catch (IOException exception) {
				throw new AssertionError("Checkpoint must be durable before port invocation", exception);
			}
		}

		Entry savedEntry(String id) {
			return saved().queue().stream().filter(entry -> entry.challengeId().equals(id)).findFirst().orElseThrow();
		}

		void observe(String event) {
			var checkpoint = saved();
			assertTrue(checkpoint.queue().stream()
							   .filter(entry -> !entry.terminal() && entry.state() != State.DISCOVERED).count() <= 1);
			events.add(event);
		}

		final class FakeBrowser implements ChallengeRunner.Browser {
			final List<String> extracted = new ArrayList<>();
			final List<String> tested = new ArrayList<>();
			final List<SubmissionGuard.Intent> submitted = new ArrayList<>();
			final List<SubmissionGuard.Intent> reconciled = new ArrayList<>();
			Function<String, Extraction> extraction = ChallengeRunnerTest::ready;
			BiFunction<String, String, Sample> samples = (source, starter) -> passed();
			Function<SubmissionGuard.Intent, Verdict> submission = intent -> accepted();
			Function<SubmissionGuard.Intent, Verdict> reconciliation = intent -> accepted();
			String current;

			@Override
			public Extraction extract(String url) {
				current = initial.queue().stream().filter(entry -> entry.url().equals(url)).findFirst().orElseThrow()
						.challengeId();
				observe("extract:" + current);
				extracted.add(current);
				return extraction.apply(current);
			}

			@Override
			public Sample test(String source, String starter) {
				observe("test:" + current);
				var entry = savedEntry(current);
				assertTrue(entry.state() == State.TESTING || entry.state() == State.AWAITING_APPROVAL);
				assertEquals(entry.sourceCode(), source);
				tested.add(source);
				return samples.apply(source, starter);
			}

			@Override
			public Verdict submit(SubmissionGuard.Intent intent) {
				observe("submit:" + intent.challengeId());
				var entry = savedEntry(intent.challengeId());
				assertEquals(State.SUBMITTING, entry.state());
				assertEquals(intent.sourceCode(), entry.sourceCode());
				assertEquals(intent.createdAt(), entry.submissionIntent());
				assertEquals(intent.submissionId(), entry.submissionId());
				submitted.add(intent);
				return submission.apply(intent);
			}

			@Override
			public Verdict reconcile(String url, SubmissionGuard.Intent intent) {
				observe("reconcile:" + intent.challengeId());
				assertEquals(url(intent.challengeId()), url);
				assertEquals(State.SUBMITTING, savedEntry(intent.challengeId()).state());
				reconciled.add(intent);
				return reconciliation.apply(intent);
			}
		}

		final class FakeSolver implements ChallengeRunner.Solver {
			final Map<String, Request> artifacts = new HashMap<>();
			final List<Request> dispatched = new ArrayList<>();
			final List<String> awaited = new ArrayList<>();
			java.util.function.BooleanSupplier available = () -> true;
			Function<Request, Result> result = ChallengeRunnerTest::success;
			int availabilityChecks;

			void store(Request request) {
				artifacts.put(request.jobId(), request);
			}

			@Override
			public boolean available() {
				observe("available");
				availabilityChecks++;
				return available.getAsBoolean();
			}

			@Override
			public void dispatch(Request request) {
				observe("dispatch:" + request.challengeId() + ":" + request.attempt());
				var checkpoint = saved();
				var entry = savedEntry(request.challengeId());
				assertEquals(State.SOLVING, entry.state());
				assertEquals(request.attempt(), entry.attempt());
				assertEquals(request.jobId(), entry.jobId());
				assertEquals(request.runId(), checkpoint.runId());
				assertEquals(request.previousFeedback(), entry.feedback());
				assertEquals("", entry.sourceCode());
				assertNull(entry.submissionIntent());
				int index = checkpoint.queue().indexOf(entry);
				assertTrue(
						checkpoint.queue().subList(0, index).stream().allMatch(Entry::terminal),
						"Earlier challenges must finish before dispatch"
				);
				assertTrue(checkpoint.queue().subList(index + 1, checkpoint.queue().size()).stream()
						.allMatch(later -> later.terminal() || later.state() == State.DISCOVERED));
				assertFalse(artifacts.containsKey(request.jobId()), "A recorded job must never be dispatched again");
				dispatched.add(request);
				store(request);
			}

			@Override
			public Optional<Request> request(String jobId) {
				var entry = saved().queue().stream().filter(candidate -> candidate.jobId().equals(jobId)).findFirst()
						.orElseThrow();
				observe("request:" + entry.challengeId() + ":" + entry.attempt());
				return Optional.ofNullable(artifacts.get(jobId));
			}

			@Override
			public Result await(String jobId) {
				var request = artifacts.get(jobId);
				assertNotNull(request, "Missing artifacts must not be awaited");
				observe("await:" + request.challengeId() + ":" + request.attempt());
				assertEquals(State.SOLVING, savedEntry(request.challengeId()).state());
				awaited.add(jobId);
				return result.apply(request);
			}
		}
	}
}
