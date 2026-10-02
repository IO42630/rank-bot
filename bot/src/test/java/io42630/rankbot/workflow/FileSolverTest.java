package io42630.rankbot.workflow;

import io42630.rankbot.jobs.*;
import io42630.rankbot.browser.BrowserPause;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.time.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static io42630.rankbot.jobs.JobContracts.*;

class FileSolverTest {
	@TempDir
	Path directory;

	@Test
	void workerOutagePausesWithoutReclaimingAndTerminalResultRemainsUsable() throws Exception {
		var store = new JobStore(directory, Duration.ofSeconds(3));
		var solver = new FileSolver(store);
		assertFalse(solver.available());
		var request = new Request(
				1,
				"job",
				"run",
				"challenge",
				1,
				"Java 17",
				"Statement",
				"class Solution {}",
				"",
				Instant.now().plusSeconds(60)
		);
		solver.dispatch(request);
		assertThrows(BrowserPause.class, () -> solver.await("job"));
		assertEquals(request, solver.request("job").orElseThrow());
		try (var channel = FileChannel.open(directory.resolve(".worker.lock"), StandardOpenOption.WRITE);
			 var lock = channel.lock()) {
			assertTrue(solver.available());
			var claim = store.claim().orElseThrow();
			assertTrue(store.complete(claim, new Result(1, "job", ResultStatus.SUCCESS, "class Solution {}", "")));
		}
		assertFalse(solver.available());
		assertEquals(ResultStatus.SUCCESS, solver.await("job").status());
		assertTrue(store.claim().isEmpty());
	}
}
