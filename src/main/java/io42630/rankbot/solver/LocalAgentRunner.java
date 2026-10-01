package io42630.rankbot.solver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io42630.rankbot.jobs.JsonFiles;

import static io42630.rankbot.jobs.JobContracts.MAX_BYTES;
import static io42630.rankbot.jobs.JobContracts.Request;
import static io42630.rankbot.jobs.JobContracts.Result;
import static io42630.rankbot.jobs.JobContracts.ResultStatus;
import static io42630.rankbot.jobs.JobContracts.SCHEMA_VERSION;

public final class LocalAgentRunner {
	public record Config(List<String> command, long timeoutSeconds, int maxLogBytes) {
		public Config {
			if (command == null || command.isEmpty() || command.stream()
					.anyMatch(value -> value == null || value.isBlank())) {
				throw new IllegalArgumentException("Explicit agent executable and arguments required");
			}
			command = List.copyOf(command);
			Path executable = Path.of(command.getFirst());
			if (!executable.isAbsolute() || !Files.isExecutable(executable) || !Files.isRegularFile(executable)) {
				throw new IllegalArgumentException("Agent executable must be an existing absolute executable path");
			}
			if (timeoutSeconds < 1 || timeoutSeconds > 3600 || maxLogBytes < 1024 || maxLogBytes > MAX_BYTES) {
				throw new IllegalArgumentException("timeoutSeconds must be 1..3600 and maxLogBytes 1024..1048576");
			}
			if (command.stream().noneMatch(value -> value.contains("{request}"))
				|| command.stream().noneMatch(value -> value.contains("{result}"))) {
				throw new IllegalArgumentException("Command must contain {request} and {result} arguments");
			}
		}

		public static Config load(Path path) throws IOException {
			return JsonFiles.read(path, Config.class);
		}
	}

	@FunctionalInterface
	public interface Heartbeat {
		boolean update() throws IOException;
	}

	private final Config config;
	private final Path scratchRoot;
	private final Duration heartbeatInterval;

	public LocalAgentRunner(Config config, Path scratchRoot, Duration heartbeatInterval) {
		if (heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
			throw new IllegalArgumentException("Positive heartbeat interval required");
		}
		this.config = config;
		this.scratchRoot = scratchRoot.toAbsolutePath();
		this.heartbeatInterval = heartbeatInterval;
	}

	public Result solve(Request request, Heartbeat heartbeat) throws InterruptedException {
		Process process = null;
		Thread logThread = null;
		Thread shutdown = null;
		Set<ProcessHandle> children = ConcurrentHashMap.newKeySet();
		try {
			Instant deadline = Instant.now().plusSeconds(config.timeoutSeconds());
			if (request.deadline().isBefore(deadline)) {
				deadline = request.deadline();
			}
			if (!deadline.isAfter(Instant.now())) {
				return failure(request, ResultStatus.TIMEOUT, "Agent deadline expired");
			}
			JsonFiles.privateDirectory(scratchRoot);
			Path scratch = Files.createTempDirectory(scratchRoot, request.jobId() + "-");
			JsonFiles.privateDirectory(scratch);
			Path input = scratch.resolve("request.json"), output = scratch.resolve("result.json");
			JsonFiles.write(input, request);
			List<String> command = config.command().stream().map(argument -> argument
					.replace("{request}", input.toString()).replace("{result}", output.toString())).toList();
			ProcessBuilder builder = new ProcessBuilder(command).directory(scratch.toFile()).redirectErrorStream(true);
			builder.environment().clear();
			builder.environment().put("PATH", "/usr/bin:/bin");
			builder.environment().put("LANG", "C.UTF-8");
			builder.environment().put("TMPDIR", scratch.toString());
			process = builder.start();
			Process running = process;
			shutdown = new Thread(() -> terminate(running, children), "rankbot-agent-shutdown");
			Runtime.getRuntime().addShutdownHook(shutdown);
			process.getOutputStream().close();
			InputStream logs = process.getInputStream();
			logThread =
					Thread.ofVirtual().start(() -> capture(logs, scratch.resolve("agent.log"), config.maxLogBytes()));
			Instant nextHeartbeat = Instant.MIN;
			while (true) {
				process.descendants().forEach(children::add);
				Instant now = Instant.now();
				if (!now.isBefore(deadline)) {
					return failure(request, ResultStatus.TIMEOUT, "Agent exceeded deadline");
				}
				if (!now.isBefore(nextHeartbeat)) {
					if (!heartbeat.update()) {
						return failure(request, ResultStatus.TIMEOUT, "Worker lease lost");
					}
					nextHeartbeat = now.plus(heartbeatInterval);
				}
				if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
					break;
				}
			}
			if (process.exitValue() != 0) {
				return failure(request, ResultStatus.FAILURE, "Agent exited with code " + process.exitValue());
			}
			if (!heartbeat.update()) {
				return failure(request, ResultStatus.TIMEOUT, "Worker lease lost");
			}
			if (!Instant.now().isBefore(deadline)) {
				return failure(request, ResultStatus.TIMEOUT, "Agent exceeded deadline");
			}
			if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
				return failure(request, ResultStatus.FAILURE, "Agent result missing or not a regular file");
			}
			Result result = JsonFiles.read(output, Result.class);
			if (!request.jobId().equals(result.jobId())) {
				return failure(request, ResultStatus.FAILURE, "Agent result identity mismatch");
			}
			return result;
		} catch (IOException | IllegalArgumentException exception) {
			return failure(request, ResultStatus.FAILURE, "Agent launch, heartbeat, or result validation failed");
		} finally {
			if (process != null) {
				terminate(process, children);
				process.waitFor(2, TimeUnit.SECONDS);
				if (logThread != null) {
					logThread.join(1000);
				}
				try {
					process.getInputStream().close();
				} catch (IOException ignored) {
				}
			}
			if (logThread != null) {
				logThread.join(2000);
			}
			if (shutdown != null) {
				try {
					Runtime.getRuntime().removeShutdownHook(shutdown);
				} catch (IllegalStateException ignored) {
				}
			}
		}
	}

	private static void terminate(Process process, Set<ProcessHandle> children) {
		process.descendants().forEach(children::add);
		children.forEach(ProcessHandle::destroy);
		process.destroy();
		for (ProcessHandle child : children) {
			if (child.isAlive()) {
				child.destroyForcibly();
			}
		}
		if (process.isAlive()) {
			process.destroyForcibly();
		}
	}

	private static Result failure(Request request, ResultStatus status, String message) {
		return new Result(SCHEMA_VERSION, request.jobId(), status, "", message);
	}

	private static void capture(InputStream input, Path file, int bound) {
		try (input; OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
			byte[] buffer = new byte[4096];
			int written = 0, count;
			while ((count = input.read(buffer)) != -1) {
				int keep = Math.min(count, bound - written);
				if (keep > 0) {
					output.write(buffer, 0, keep);
					written += keep;
				}
			}
		} catch (IOException ignored) {
		}
	}
}
