package io42630.rankbot.cli;

import java.nio.file.Path;
import java.time.Duration;

import io42630.rankbot.jobs.JobStore;
import picocli.CommandLine.Option;

public final class CommonOptions {
	@Option(names = "--jobs", defaultValue = "runtime/jobs")
	public Path jobs;
	@Option(names = "--lease-seconds", defaultValue = "30")
	public long leaseSeconds;

	public JobStore store() throws java.io.IOException {
		if (leaseSeconds < 3 || leaseSeconds > 3600) {
			throw new IllegalArgumentException("lease-seconds must be 3..3600");
		}
		return new JobStore(jobs, Duration.ofSeconds(leaseSeconds));
	}
}
