package io42630.rankbot.solver;

import java.nio.file.Files;
import java.nio.file.Path;

import io42630.rankbot.jobs.JsonFiles;

import static io42630.rankbot.jobs.JobContracts.MAX_BYTES;
import static io42630.rankbot.jobs.JobContracts.Request;
import static io42630.rankbot.jobs.JobContracts.Result;
import static io42630.rankbot.jobs.JobContracts.ResultStatus;

public final class FakeAgent {
	static void main(String[] args) throws Exception {
		Request request = JsonFiles.read(Path.of(args[0]), Request.class);
		if (!request.statement().contains("Statement") || !request.language().equals("Java 17")
			|| !request.starterCode().contains("Solution") || !request.previousFeedback().equals("previous failure")
			|| request.deadline() == null || System.getenv("RANKBOT_TEST_SECRET") != null
			|| System.getenv("HOME") != null || System.getenv("JAVA_HOME") != null) {
			System.exit(8);
		}
		Path output = Path.of(args[1]);
		switch (args[2]) {
			case "failure" -> System.exit(3);
			case "missing" -> {
			}
			case "malformed" -> Files.writeString(output, "not JSON");
			case "fenced" -> Files.writeString(
					output,
					"{\"schemaVersion\":1,\"jobId\":\"job\",\"status\":\"SUCCESS\",\"sourceCode\":\"```java class "
					+ "Solution {} ```\",\"diagnostic\":\"\"}"
			);
			case "mismatch" ->
					JsonFiles.write(output, new Result(1, "other", ResultStatus.SUCCESS, "class Solution {}", ""));
			case "hang" -> {
				Files.writeString(output.getParent().resolve("pid.txt"), Long.toString(ProcessHandle.current().pid()));
				Thread.sleep(60_000);
			}
			case "large" -> Files.writeString(output, "x".repeat(MAX_BYTES + 1));
			default -> {
				System.out.print("x".repeat(200_000));
				JsonFiles.write(
						output,
						new Result(1, request.jobId(), ResultStatus.SUCCESS, "public class Solution {}", "")
				);
			}
		}
	}
}
