package io42630.rankbot.jobs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io42630.rankbot.jobs.JobContracts.MAX_BYTES;
import static io42630.rankbot.jobs.JobContracts.Request;
import static io42630.rankbot.jobs.JobContracts.Result;
import static io42630.rankbot.jobs.JobContracts.ResultStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobStoreTest {
	@TempDir
	Path directory;

	static Request request(String id, Instant deadline) {
		return new Request(
				1,
				id,
				"run",
				"hello-world",
				1,
				"Java 17",
				"Statement",
				"public class Solution {}",
				"",
				deadline
		);
	}

	@Test
	void roundTripAndSingleClaim() throws Exception {
		JobStore store = new JobStore(directory, Duration.ofSeconds(30));
		Request request = request("job", Instant.now().plusSeconds(60));
		store.publish(request);
		assertThrows(java.io.IOException.class, () -> store.publish(request));
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(store::claim);
			var second = executor.submit(() -> new JobStore(directory, Duration.ofSeconds(30)).claim());
			var a = first.get();
			var b = second.get();
			assertNotEquals(a.isPresent(), b.isPresent());
			var claim = a.orElseGet(b::orElseThrow);
			assertEquals(request, claim.request());
			assertThrows(
					IllegalArgumentException.class,
					() -> store.complete(claim, new Result(1, "wrong", ResultStatus.SUCCESS, "class Solution {}", ""))
			);
			assertTrue(store.complete(claim, new Result(1, "job", ResultStatus.SUCCESS, "class Solution {}", "")));
			assertEquals(ResultStatus.SUCCESS, store.result("job").orElseThrow().status());
			assertFalse(store.heartbeat(claim));
			assertFalse(store.complete(claim, new Result(1, "job", ResultStatus.FAILURE, "", "late")));
		}
	}

	@Test
	void expiredLeaseIsTerminalAndRejectsLateResults() throws Exception {
		MutableClock clock = new MutableClock();
		JobStore store = new JobStore(directory, Duration.ofSeconds(5), clock);
		store.publish(request("job", clock.instant().plusSeconds(60)));
		var claim = store.claim().orElseThrow();
		clock.now = clock.now.plusSeconds(4);
		assertTrue(store.heartbeat(claim));
		clock.now = clock.now.plusSeconds(5);
		assertEquals(ResultStatus.TIMEOUT, store.result("job").orElseThrow().status());
		assertFalse(store.complete(claim, new Result(1, "job", ResultStatus.SUCCESS, "class Solution {}", "")));
		assertTrue(store.claim().isEmpty());
	}

	@Test
	void pendingDeadlineAndInvalidFiles() throws Exception {
		MutableClock clock = new MutableClock();
		JobStore store = new JobStore(directory, Duration.ofSeconds(5), clock);
		store.publish(request("job", clock.instant().plusSeconds(1)));
		clock.now = clock.now.plusSeconds(2);
		assertTrue(store.claim().isEmpty());
		assertEquals(ResultStatus.TIMEOUT, store.result("job").orElseThrow().status());
		Files.writeString(directory.resolve("bad.json"), "{bad}");
		assertThrows(java.io.IOException.class, store::claim);
		Files.writeString(directory.resolve("large.json"), "x".repeat(MAX_BYTES + 1));
		assertThrows(java.io.IOException.class, () -> JsonFiles.read(directory.resolve("large.json"), Result.class));
	}

	@Test
	void validatesContractsAndDuplicateJsonFields() throws Exception {
		assertThrows(
				IllegalArgumentException.class, () -> new Result(
						2, "id", ResultStatus.SUCCESS, "class A {}",
						""
				)
		);
		assertThrows(IllegalArgumentException.class, () -> new Result(1, "../id", ResultStatus.FAILURE, "", ""));
		assertThrows(IllegalArgumentException.class, () -> new Result(1, "id", ResultStatus.SUCCESS, "", ""));
		assertThrows(
				IllegalArgumentException.class,
				() -> new Result(1, "id", ResultStatus.SUCCESS, "```java\nclass A {}\n```", "")
		);
		assertThrows(
				IllegalArgumentException.class,
				() -> new Result(1, "id", ResultStatus.SUCCESS, "x".repeat(500_001), "")
		);
		Files.writeString(directory.resolve("duplicate.json"), "{\"jobId\":\"a\",\"jobId\":\"b\"}");
		assertThrows(
				java.io.IOException.class,
				() -> JsonFiles.read(directory.resolve("duplicate.json"), Result.class)
		);
	}

	Process probe(Path store, Path output, long leaseMillis) throws Exception {
		return new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp",
				System.getProperty("java.class.path"),
				JobStoreProbe.class.getName(),
				store.toString(),
				output.toString(),
				Long.toString(leaseMillis)
		).redirectErrorStream(true).start();
	}

	@Test
	void independentProcessesCannotBothClaim() throws Exception {
		Path jobs = directory.resolve("jobs");
		var store = new JobStore(jobs, Duration.ofSeconds(30));
		store.publish(request("job", Instant.now().plusSeconds(60)));
		Path first = directory.resolve("first.claim"), second = directory.resolve("second.claim");
		Process a = probe(jobs, first, 30_000), b = probe(jobs, second, 30_000);
		assertTrue(a.waitFor(10, TimeUnit.SECONDS));
		assertTrue(b.waitFor(10, TimeUnit.SECONDS));
		assertEquals(0, a.exitValue());
		assertEquals(0, b.exitValue());
		assertNotEquals(Files.exists(first), Files.exists(second));
	}

	@Test
	void exitedWorkerLeavesOneConsumedExpiringAttempt() throws Exception {
		Path jobs = directory.resolve("jobs");
		var store = new JobStore(jobs, Duration.ofMillis(500));
		store.publish(request("job", Instant.now().plusSeconds(60)));
		Path marker = directory.resolve("orphan.claim");
		Process worker = probe(jobs, marker, 500);
		assertTrue(worker.waitFor(10, TimeUnit.SECONDS));
		assertEquals(0, worker.exitValue());
		var claim = JsonFiles.read(marker, JobStore.Claim.class);
		Thread.sleep(600);
		assertEquals(ResultStatus.TIMEOUT, store.result("job").orElseThrow().status());
		assertTrue(store.claim().isEmpty());
		assertFalse(store.complete(
				claim,
				new Result(1, "job", ResultStatus.SUCCESS, "class Solution {}", "late after process exit")
		));
	}

	static class MutableClock extends Clock {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");

		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		public Clock withZone(ZoneId zone) {
			return this;
		}

		public Instant instant() {
			return now;
		}
	}
}
