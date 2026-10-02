package io42630.rankbot.jobs;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public final class JobContracts {
	public static final int SCHEMA_VERSION = 1;
	public static final int MAX_BYTES = 1_048_576;
	private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

	private JobContracts() {
	}

	public enum ResultStatus {
		SUCCESS,
		FAILURE,
		TIMEOUT
	}

	public record Request(
			int schemaVersion, String jobId, String runId, String challengeId,
			int attempt, String language, String statement, String starterCode,
			String previousFeedback, Instant deadline
	) {
		public Request {
			schema(schemaVersion);
			identity(jobId);
			identity(runId);
			identity(challengeId);
			if (attempt < 1 || attempt > 3) {
				throw new IllegalArgumentException("attempt must be 1..3");
			}
			text(language, "language", 100);
			if (!language.matches("(?i)java(?:\\s+.*)?")) {
				throw new IllegalArgumentException("Java language required");
			}
			text(statement, "statement", 500_000);
			source(starterCode);
			bounded(previousFeedback, "previousFeedback", 100_000);
			Objects.requireNonNull(deadline, "deadline");
		}
	}

	public record Result(
			int schemaVersion, String jobId, ResultStatus status, String sourceCode,
			String diagnostic
	) {
		public Result {
			schema(schemaVersion);
			identity(jobId);
			Objects.requireNonNull(status, "status");
			bounded(diagnostic, "diagnostic", 100_000);
			if (status == ResultStatus.SUCCESS) {
				source(sourceCode);
			} else {
				bounded(sourceCode, "sourceCode", 500_000);
			}
		}
	}

	public static void identity(String id) {
		if (id == null || !ID.matcher(id).matches()) {
			throw new IllegalArgumentException("Invalid identity");
		}
	}

	public static void source(String source) {
		text(source, "Java source", 500_000);
		if (source.contains("```")) {
			throw new IllegalArgumentException("Java source must not contain Markdown fences");
		}
		if (!Pattern.compile("\\b(class|interface|enum|record)\\s+\\w+").matcher(source).find()) {
			throw new IllegalArgumentException("Java source requires a type declaration");
		}
	}

	private static void schema(int version) {
		if (version != SCHEMA_VERSION) {
			throw new IllegalArgumentException("Unsupported schema version");
		}
	}

	private static void text(String value, String name, int max) {
		bounded(value, name, max);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be empty");
		}
	}

	private static void bounded(String value, String name, int max) {
		if (value == null || value.length() > max) {
			throw new IllegalArgumentException(name + " missing or too large");
		}
	}
}
