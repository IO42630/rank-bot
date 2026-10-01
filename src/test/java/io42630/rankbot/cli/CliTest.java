package io42630.rankbot.cli;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io42630.rankbot.workflow.RunCheckpoint;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliTest {
	@TempDir
	Path directory;

	@Test
	void defaultsAndInvalidConfiguration() {
		var browser = new BrowserCommand();
		var command = new CommandLine(browser);
		command.parseArgs("--track", "https://www.hackerrank.com/domains/java");
		assertEquals(3, browser.maxAttempts);
		assertEquals("required", browser.approval.name());
		browser.maxAttempts = 4;
		assertThrows(IllegalArgumentException.class, browser::call);
		assertThrows(
				CommandLine.ParameterException.class,
				() -> command.parseArgs("--track", "x", "--approval", "never")
		);
		assertThrows(CommandLine.ParameterException.class, () -> new CommandLine(new BrowserCommand()).parseArgs());
	}

	@Test
	void resumeCannotChangeApprovalOrAttemptBudget() throws Exception {
		Path path = directory.resolve("checkpoint.json");
		new RunCheckpoint(
				1,
				"run",
				"https://www.hackerrank.com/domains/java",
				2,
				RunCheckpoint.Approval.required,
				List.of()
		).save(path);
		var browser = new BrowserCommand();
		new CommandLine(browser).parseArgs(
				"--track",
				"https://www.hackerrank.com/domains/java",
				"--checkpoint",
				path.toString(),
				"--resume",
				"--approval",
				"automatic",
				"--max-attempts",
				"2"
		);
		assertThrows(IllegalArgumentException.class, browser::call);
		browser.approval = RunCheckpoint.Approval.required;
		browser.maxAttempts = 3;
		assertThrows(IllegalArgumentException.class, browser::call);
		browser.resume = false;
		assertThrows(IllegalArgumentException.class, browser::call);
	}
}
