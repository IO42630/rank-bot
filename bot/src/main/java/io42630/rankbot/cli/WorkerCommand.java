package io42630.rankbot.cli;

import java.util.concurrent.Callable;
import java.net.URI;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.time.Duration;

import io42630.rankbot.solver.LocalAgentRunner;
import picocli.CommandLine.*;

@Command(name = "worker", mixinStandardHelpOptions = true, description = "Separate local solver worker")
public final class WorkerCommand implements Callable<Integer> {
	@Mixin
	public CommonOptions common;
	@Option(names = "--solver-url", defaultValue = "http://127.0.0.1:8080/solve")
	public URI solverUrl;
	@Option(names = "--once", description = "Process at most one available job, then exit")
	public boolean once;

	@Override
	public Integer call() throws Exception {
		var store = common.store();
		var runner = new LocalAgentRunner(solverUrl, Duration.ofMillis(common.leaseSeconds * 1000 / 3));
		try (var channel = FileChannel.open(
				common.jobs.resolve(".worker.lock"),
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE
		);
			 var lock = channel.tryLock()) {
			if (lock == null) {
				throw new IllegalStateException("Only one worker may use this job directory");
			}
			do {
				var claim = store.claim();
				if (claim.isPresent()) {
					var result = runner.solve(claim.get().request(), () -> store.heartbeat(claim.get()));
					store.complete(claim.get(), result);
					System.out.println("Job " + result.jobId() + ": " + result.status());
				} else if (!once) {
					Thread.sleep(1000);
				}
			} while (!once && !Thread.currentThread().isInterrupted());
		}
		return 0;
	}
}
