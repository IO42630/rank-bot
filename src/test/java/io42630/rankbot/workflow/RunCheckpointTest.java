package io42630.rankbot.workflow;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io42630.rankbot.workflow.RunCheckpoint.Approval;
import static io42630.rankbot.workflow.RunCheckpoint.Entry;
import static io42630.rankbot.workflow.RunCheckpoint.State;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCheckpointTest {
	@TempDir
	Path directory;

	@Test
	void roundTripKeepsOrderAndBudget() throws Exception {
		var a = new Entry(
				"a",
				"https://www.hackerrank.com/challenges/a/problem",
				State.ACCEPTED,
				2,
				"job",
				"class Solution {}",
				"",
				null,
				""
		);
		var b = new Entry(
				"b",
				"https://www.hackerrank.com/challenges/b/problem",
				State.DISCOVERED,
				0,
				"",
				"",
				"",
				null,
				""
		);
		var checkpoint = new RunCheckpoint(1, "run", "track", 2, Approval.required, List.of(a, b));
		checkpoint.save(directory.resolve("checkpoint.json"));
		assertEquals(checkpoint, RunCheckpoint.load(directory.resolve("checkpoint.json")));
		assertTrue(a.terminal());
		assertFalse(b.terminal());
		assertThrows(
				IllegalArgumentException.class,
				() -> new RunCheckpoint(1, "run", "track", 1, Approval.required, List.of(a))
		);
	}
}
