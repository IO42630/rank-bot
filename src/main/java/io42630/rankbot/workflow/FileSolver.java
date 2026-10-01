package io42630.rankbot.workflow;

import java.io.IOException;
import java.util.Optional;

import io42630.rankbot.browser.BrowserPause;
import io42630.rankbot.jobs.JobStore;

import static io42630.rankbot.jobs.JobContracts.Request;
import static io42630.rankbot.jobs.JobContracts.Result;

public final class FileSolver implements ChallengeRunner.Solver {
	private final JobStore store;

	public FileSolver(JobStore store) {
		this.store = store;
	}

	@Override
	public boolean available() throws IOException {
		return store.workerAvailable();
	}

	@Override
	public void dispatch(Request request) throws IOException {
		store.publish(request);
	}

	@Override
	public Optional<Request> request(String jobId) throws IOException {
		return store.request(jobId);
	}

	@Override
	public Result await(String jobId) throws IOException, InterruptedException {
		while (true) {
			var result = store.result(jobId);
			if (result.isPresent()) {
				return result.get();
			}
			if (!available()) {
				throw new BrowserPause("Worker interrupted; recorded attempt retained for resume");
			}
			Thread.sleep(500);
		}
	}
}
