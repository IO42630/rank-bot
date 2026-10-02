package io42630.rankbot.solver;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io42630.rankbot.jobs.JobContracts.Request;
import static io42630.rankbot.jobs.JobContracts.Result;
import static io42630.rankbot.jobs.JobContracts.ResultStatus;
import static io42630.rankbot.jobs.JobContracts.SCHEMA_VERSION;

public final class LocalAgentRunner {
	@FunctionalInterface
	public interface Heartbeat {
		boolean update() throws IOException;
	}

	private final URI endpoint;
	private final Duration heartbeatInterval;
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public LocalAgentRunner(URI endpoint, Duration heartbeatInterval) {
		if (!endpoint.isAbsolute() || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))) {
			throw new IllegalArgumentException("HTTP solver URL required");
		}
		if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
			throw new IllegalArgumentException("Positive heartbeat interval required");
		}
		this.endpoint = endpoint;
		this.heartbeatInterval = heartbeatInterval;
	}

	public Result solve(Request request, Heartbeat heartbeat) throws InterruptedException {
		var remaining = Duration.between(Instant.now(), request.deadline());
		if (remaining.isNegative() || remaining.isZero()) {
			return failure(request, ResultStatus.TIMEOUT, "Solver deadline expired");
		}
		String prompt = "Return complete Java source only, without Markdown fences. Preserve starter signatures.\n"
				+ "Language: " + request.language() + "\nStatement:\n" + request.statement()
				+ "\nStarter code:\n" + request.starterCode() + "\nPrevious feedback:\n" + request.previousFeedback();
		var call = HttpRequest.newBuilder(endpoint).timeout(remaining)
				.header("Content-Type", "text/plain; charset=utf-8")
				.POST(HttpRequest.BodyPublishers.ofString(prompt)).build();
		var response = client.sendAsync(call, HttpResponse.BodyHandlers.ofString());
		try {
			while (true) {
				remaining = Duration.between(Instant.now(), request.deadline());
				if (remaining.isNegative() || remaining.isZero()) {
					return failure(request, ResultStatus.TIMEOUT, "Solver deadline expired");
				}
				if (!heartbeat.update()) {
					return failure(request, ResultStatus.TIMEOUT, "Worker lease lost");
				}
				try {
					var reply = response.get(Math.max(1, Math.min(remaining.toMillis(), heartbeatInterval.toMillis())),
							TimeUnit.MILLISECONDS);
					if (!heartbeat.update() || !Instant.now().isBefore(request.deadline())) {
						return failure(request, ResultStatus.TIMEOUT, "Worker lease or deadline expired");
					}
					if (reply.statusCode() != 200) {
						return failure(request, reply.statusCode() == 504 ? ResultStatus.TIMEOUT : ResultStatus.FAILURE,
								"Solver returned HTTP " + reply.statusCode());
					}
					return new Result(SCHEMA_VERSION, request.jobId(), ResultStatus.SUCCESS, reply.body(), "");
				} catch (TimeoutException ignored) {
				}
			}
		} catch (ExecutionException exception) {
			return failure(request, exception.getCause() instanceof java.net.http.HttpTimeoutException
					? ResultStatus.TIMEOUT : ResultStatus.FAILURE, "Solver request failed");
		} catch (IOException | IllegalArgumentException exception) {
			return failure(request, ResultStatus.FAILURE, "Solver heartbeat or result validation failed");
		} finally {
			response.cancel(true);
		}
	}

	private static Result failure(Request request, ResultStatus status, String message) {
		return new Result(SCHEMA_VERSION, request.jobId(), status, "", message);
	}
}
