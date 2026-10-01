package io42630.rankbot.workflow;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io42630.rankbot.browser.ChallengePage.Sample;
import io42630.rankbot.browser.ChallengePage.SampleStatus;
import io42630.rankbot.browser.ChallengePage.Verdict;
import io42630.rankbot.browser.ChallengePage.VerdictStatus;

import static io42630.rankbot.workflow.SubmissionGuard.Outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class SubmissionGuardTest {
	final Sample passed = new Sample(SampleStatus.PASSED, "pass");

	@Test
	void rejectionOrFailedSamplesNeverSubmit() throws Exception {
		var guard = new SubmissionGuard();
		assertEquals(
				Outcome.REJECTED, guard.execute(
						"challenge", "class Solution {}", passed, RunCheckpoint.Approval.required,
						() -> false, intent -> fail("must not persist intent"), intent -> {
							fail("must not submit");
							return null;
						}
				).outcome()
		);
		assertEquals(
				Outcome.FAILED, guard.execute(
						"challenge",
						"class Solution {}",
						new Sample(SampleStatus.WRONG_ANSWER, "wrong"),
						RunCheckpoint.Approval.automatic,
						() -> {
							fail("no prompt");
							return false;
						},
						intent -> fail("must not persist"),
						intent -> {
							fail("must not submit");
							return null;
						}
				).outcome()
		);
	}

	@Test
	void approvalAndAutomaticModePersistBeforeSubmitting() throws Exception {
		for (var policy : RunCheckpoint.Approval.values()) {
			List<String> events = new ArrayList<>();
			var decision = new SubmissionGuard().execute(
					"challenge", "class Solution {}", passed, policy,
					() -> {
						events.add("approve");
						return true;
					}, intent -> {
						assertNotNull(intent.createdAt());
						events.add("persist");
					},
					intent -> {
						events.add("submit");
						return new Verdict(VerdictStatus.ACCEPTED, "Accepted", "123");
					}
			);
			assertEquals(
					policy == RunCheckpoint.Approval.required
							? List.of("approve", "persist", "submit")
							: List.of("persist", "submit"), events
			);
			assertEquals(Outcome.ACCEPTED, decision.outcome());
		}
	}

	@Test
	void persistenceFailurePreventsClickAndUnknownStaysUnknown() {
		assertThrows(
				java.io.IOException.class, () -> new SubmissionGuard().execute(
						"challenge", "class Solution {}", passed, RunCheckpoint.Approval.automatic,
						() -> true, intent -> {
							throw new java.io.IOException("disk failed");
						}, intent -> {
							fail("must not click");
							return null;
						}
				)
		);
		assertEquals(
				Outcome.UNKNOWN,
				SubmissionGuard.decision(new Verdict(VerdictStatus.UNKNOWN, "unknown", "")).outcome()
		);
		assertEquals(
				Outcome.PENDING,
				SubmissionGuard.decision(new Verdict(VerdictStatus.PENDING, "pending", "123")).outcome()
		);
	}
}
