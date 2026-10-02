package io42630.rankbot.solver;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io42630.rankbot.jobs.JobStore;

import static io42630.rankbot.jobs.JobContracts.Request;
import static io42630.rankbot.jobs.JobContracts.ResultStatus;
import static org.junit.jupiter.api.Assertions.*;

class LocalAgentRunnerTest {
	@TempDir
	Path directory;
	HttpServer server;
	URI endpoint;
	int status = 200;
	String reply = "public class Solution {}";
	long delay;
	AtomicReference<String> prompt = new AtomicReference<>();

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/solve", exchange -> {
			prompt.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			try {
				Thread.sleep(delay);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			byte[] body = reply.getBytes(StandardCharsets.UTF_8);
			try (exchange) {
				exchange.sendResponseHeaders(status, body.length);
				exchange.getResponseBody().write(body);
			}
		});
		server.start();
		endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/solve");
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	LocalAgentRunner runner() {
		return new LocalAgentRunner(endpoint, Duration.ofMillis(50));
	}

	Request request(Instant deadline) {
		return new Request(1, "job", "run", "challenge", 1, "Java 17", "Statement",
				"class Solution {}", "previous failure", deadline);
	}

	@Test
	void successForwardsInputsAndKeepsLeaseAlive() throws Exception {
		delay = 200;
		AtomicInteger heartbeats = new AtomicInteger();
		var result = runner().solve(request(Instant.now().plusSeconds(5)), () -> {
			heartbeats.incrementAndGet();
			return true;
		});
		assertEquals(ResultStatus.SUCCESS, result.status());
		assertEquals(reply, result.sourceCode());
		assertEquals("job", result.jobId());
		assertTrue(heartbeats.get() >= 3);
		for (String value : List.of("Java 17", "Statement", "class Solution {}", "previous failure")) {
			assertTrue(prompt.get().contains(value));
		}
	}

	@Test
	void badRepliesBecomeTerminalFailures() throws Exception {
		for (String value : List.of("", "not Java", "```java\nclass Solution {}\n```", "x".repeat(500_001))) {
			reply = value;
			assertEquals(ResultStatus.FAILURE,
					runner().solve(request(Instant.now().plusSeconds(5)), () -> true).status());
		}
		status = 502;
		assertEquals(ResultStatus.FAILURE,
				runner().solve(request(Instant.now().plusSeconds(5)), () -> true).status());
		status = 504;
		assertEquals(ResultStatus.TIMEOUT,
				runner().solve(request(Instant.now().plusSeconds(5)), () -> true).status());
	}

	@Test
	void deadlinesAndLeaseLossStopWaiting() throws Exception {
		assertEquals(ResultStatus.TIMEOUT,
				runner().solve(request(Instant.now().minusSeconds(1)), () -> true).status());
		delay = 500;
		assertEquals(ResultStatus.TIMEOUT,
				runner().solve(request(Instant.now().plusMillis(100)), () -> true).status());
		assertEquals(ResultStatus.TIMEOUT,
				runner().solve(request(Instant.now().plusSeconds(5)), () -> false).status());
	}

	@Test
	void workerReturnsAValidatedResult() throws Exception {
		var store = new JobStore(directory.resolve("jobs"), Duration.ofSeconds(3));
		store.publish(request(Instant.now().plusSeconds(5)));
		assertEquals(0, new picocli.CommandLine(new io42630.rankbot.cli.WorkerCommand()).execute(
				"--solver-url", endpoint.toString(), "--jobs", directory.resolve("jobs").toString(),
				"--once", "--lease-seconds", "3"));
		assertEquals(ResultStatus.SUCCESS, store.result("job").orElseThrow().status());
	}

	@Test
	void rejectsInvalidConfiguration() {
		assertThrows(IllegalArgumentException.class,
				() -> new LocalAgentRunner(URI.create("file:/tmp/solver"), Duration.ofSeconds(1)));
		assertThrows(IllegalArgumentException.class, () -> new LocalAgentRunner(endpoint, Duration.ZERO));
	}
}