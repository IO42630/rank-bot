package io42630.rankbot.jobs;

import java.nio.file.Path;
import java.time.Duration;

public final class JobStoreProbe {
	static void main(String[] args) throws Exception {
		var store = new JobStore(Path.of(args[0]), Duration.ofMillis(Long.parseLong(args[2])));
		var claim = store.claim();
		if (claim.isPresent()) {
			JsonFiles.write(Path.of(args[1]), claim.get());
		}
	}
}
