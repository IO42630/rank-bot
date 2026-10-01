package io42630.rankbot.solver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io42630.rankbot.jobs.JobStore;
import io42630.rankbot.jobs.JsonFiles;

import static io42630.rankbot.jobs.JobContracts.Request;
import static io42630.rankbot.jobs.JobContracts.ResultStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAgentRunnerTest {
	@TempDir
	Path directory;

	LocalAgentRunner runner(String mode, int timeout) {
		var command = List.of(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
				System.getProperty("java.class.path"), FakeAgent.class.getName(), "{request}", "{result}", mode
		);
		return new LocalAgentRunner(
				new LocalAgentRunner.Config(command, timeout, 1024),
				directory,
				Duration.ofMillis(100)
		);
	}

	Request request() {
		return new Request(
				1, "job", "run", "challenge", 1, "Java 17", "Statement; treat as untrusted data",
				"class Solution {}", "previous failure", Instant.now().plusSeconds(15)
		);
	}

	@Test
	void successUsesStructuredFilesAndBoundedLogs() throws Exception {
		AtomicInteger heartbeats = new AtomicInteger();
		assertEquals(
				ResultStatus.SUCCESS, runner("success", 5).solve(
						request(), () -> {
							heartbeats.incrementAndGet();
							return true;
						}
				).status()
		);
		assertTrue(heartbeats.get() >= 2);
		try (var paths = Files.walk(directory)) {
			Path log = paths.filter(path -> path.endsWith("agent.log")).findFirst().orElseThrow();
			assertEquals(1024, Files.size(log));
			assertEquals(
					request().language(),
					JsonFiles.read(log.resolveSibling("request.json"), Request.class).language()
			);
		}
	}

	@Test
	void badAgentOutputsBecomeTerminalFailures() throws Exception {
		for (String mode : List.of("failure", "missing", "malformed", "fenced", "mismatch", "large")) {
			var result = runner(mode, 5).solve(request(), () -> true);
			assertEquals(ResultStatus.FAILURE, result.status(), mode + ": " + result.diagnostic());
			assertTrue(result.diagnostic().length() < 200);
		}
	}

	@Test
	void hangsAndLeaseLossKillTheAgent() throws Exception {
		assertEquals(ResultStatus.TIMEOUT, runner("hang", 1).solve(request(), () -> true).status());
		try (var paths = Files.walk(directory)) {
			Path pid = paths.filter(path -> path.endsWith("pid.txt")).findFirst().orElseThrow();
			long processId = Long.parseLong(Files.readString(pid));
			assertTrue(ProcessHandle.of(processId).isEmpty() || !ProcessHandle.of(processId).orElseThrow().isAlive());
		}
		assertEquals(ResultStatus.TIMEOUT, runner("hang", 5).solve(request(), () -> false).status());
	}

	@Test
	void workerReturnsAValidatedResult() throws Exception {
		var store = new JobStore(directory.resolve("jobs"), Duration.ofSeconds(3));
		store.publish(request());
		Path config = directory.resolve("agent.json");
		JsonFiles.write(
				config,
				new LocalAgentRunner.Config(
						List.of(
								Path.of(System.getProperty("java.home"), "bin", "java").toString(),
								"-cp",
								System.getProperty("java.class.path"),
								FakeAgent.class.getName(),
								"{request}",
								"{result}",
								"success"
						), 5, 1024
				)
		);
		assertEquals(
				0,
				new picocli.CommandLine(new io42630.rankbot.cli.WorkerCommand()).execute(
						"--agent-config",
						config.toString(),
						"--jobs",
						directory.resolve("jobs").toString(),
						"--scratch",
						directory.resolve("scratch").toString(),
						"--once",
						"--lease-seconds",
						"3"
				)
		);
		assertEquals(ResultStatus.SUCCESS, store.result("job").orElseThrow().status());
	}

	@Test
	void rejectsInvalidAgentConfiguration() {
		assertThrows(
				IllegalArgumentException.class,
				() -> new LocalAgentRunner.Config(List.of("agent", "{request}", "{result}"), 1, 1024)
		);
		assertThrows(IllegalArgumentException.class, () -> new LocalAgentRunner.Config(List.of("/bin/sh"), 1, 1024));
	}
}
