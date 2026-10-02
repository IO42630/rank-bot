package io42630.rankbot.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;

import io42630.rankbot.browser.ChallengePage;

public final class SubmissionGuard {
	public record Intent(String challengeId, String sourceCode, Instant createdAt, String submissionId) {
		public String sourceHash() {
			try {
				return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
						.digest(sourceCode.getBytes(StandardCharsets.UTF_8)));
			} catch (NoSuchAlgorithmException exception) {
				throw new IllegalStateException(exception);
			}
		}
	}

	public enum Outcome {
		ACCEPTED,
		FAILED,
		REJECTED,
		PENDING,
		UNKNOWN
	}

	public record Decision(Outcome outcome, String feedback, String submissionId) {
	}

	@FunctionalInterface
	public interface PersistIntent {
		void save(Intent intent) throws IOException;
	}

	@FunctionalInterface
	public interface Submit {
		ChallengePage.Verdict send(Intent intent);
	}

	public Decision execute(
			String challengeId, String source, ChallengePage.Sample sample,
			RunCheckpoint.Approval approval, BooleanSupplier confirm,
			PersistIntent persist, Submit submit
	) throws IOException {
		if (sample.status() != ChallengePage.SampleStatus.PASSED) {
			return new Decision(Outcome.FAILED, "Samples did not pass", "");
		}
		if (approval == RunCheckpoint.Approval.required && !confirm.getAsBoolean()) {
			return new Decision(Outcome.REJECTED, "Submission not approved", "");
		}
		Intent intent = new Intent(challengeId, source, Instant.now(), "");
		persist.save(intent);
		return decision(submit.send(intent));
	}

	public static Decision decision(ChallengePage.Verdict verdict) {
		return new Decision(
				switch (verdict.status()) {
					case ACCEPTED -> Outcome.ACCEPTED;
					case FAILED -> Outcome.FAILED;
					case PENDING -> Outcome.PENDING;
					case UNKNOWN -> Outcome.UNKNOWN;
				}, verdict.feedback(), verdict.submissionId()
		);
	}
}
