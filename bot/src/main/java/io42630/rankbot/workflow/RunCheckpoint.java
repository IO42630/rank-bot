package io42630.rankbot.workflow;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import io42630.rankbot.jobs.JobContracts;
import io42630.rankbot.jobs.JsonFiles;

public record RunCheckpoint(
		int schemaVersion, String runId, String trackUrl, int maxAttempts,
		Approval approval, List<Entry> queue
) {
	public enum Approval {
		required,
		automatic
	}

	public enum State {
		DISCOVERED,
		SOLVING,
		TESTING,
		AWAITING_APPROVAL,
		SUBMITTING,
		ACCEPTED,
		SKIPPED,
		EXHAUSTED
	}

	public record Entry(
			String challengeId, String url, State state, int attempt, String jobId,
			String sourceCode, String feedback, Instant submissionIntent, String submissionId
	) {
		public Entry {
			JobContracts.identity(challengeId);
			if (url == null || state == null || attempt < 0 || attempt > 3) {
				throw new IllegalArgumentException("Invalid checkpoint entry");
			}
			if (jobId == null
				|| sourceCode == null
				|| feedback == null
				|| submissionId == null
				|| feedback.length() > 100_000) {
				throw new IllegalArgumentException("Incomplete checkpoint entry");
			}
			if (state == State.SOLVING
				|| state == State.TESTING
				|| state == State.AWAITING_APPROVAL
				|| state == State.SUBMITTING) {
				JobContracts.identity(jobId);
				if (attempt == 0) {
					throw new IllegalArgumentException("Active state requires a recorded attempt");
				}
			}
			if (state == State.TESTING || state == State.AWAITING_APPROVAL || state == State.SUBMITTING) {
				JobContracts.source(sourceCode);
			}
			if (state == State.SUBMITTING && submissionIntent == null) {
				throw new IllegalArgumentException("Submission state requires durable intent");
			}
		}

		public boolean terminal() {
			return state == State.ACCEPTED || state == State.SKIPPED || state == State.EXHAUSTED;
		}
	}

	public RunCheckpoint {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("Unsupported checkpoint schema");
		}
		JobContracts.identity(runId);
		if (maxAttempts != 2 && maxAttempts != 3) {
			throw new IllegalArgumentException("max-attempts must be 2 or 3");
		}
		if (approval == null || trackUrl == null || queue == null) {
			throw new IllegalArgumentException("Incomplete checkpoint");
		}
		queue = List.copyOf(queue);
		if (queue.stream().map(Entry::challengeId).distinct().count() != queue.size()) {
			throw new IllegalArgumentException("Duplicate queue identities");
		}
		if (queue.stream().anyMatch(entry -> entry.attempt() > maxAttempts)) {
			throw new IllegalArgumentException("Attempt exceeds budget");
		}
		if (queue.stream().filter(entry -> !entry.terminal() && entry.state() != State.DISCOVERED).count() > 1) {
			throw new IllegalArgumentException("Only one active challenge allowed");
		}
		boolean unfinished = false;
		for (Entry entry : queue) {
			if (unfinished && !entry.terminal() && entry.state() != State.DISCOVERED) {
				throw new IllegalArgumentException("Active challenge must be first unfinished entry");
			}
			if (!entry.terminal()) {
				unfinished = true;
			}
		}
	}

	public void save(Path path) throws IOException {
		JsonFiles.write(path, this);
	}

	public static RunCheckpoint load(Path path) throws IOException {
		return JsonFiles.read(path, RunCheckpoint.class);
	}
}
