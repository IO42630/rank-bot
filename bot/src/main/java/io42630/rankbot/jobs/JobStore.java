package io42630.rankbot.jobs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import static io42630.rankbot.jobs.JobContracts.Request;
import static io42630.rankbot.jobs.JobContracts.Result;
import static io42630.rankbot.jobs.JobContracts.ResultStatus;
import static io42630.rankbot.jobs.JobContracts.SCHEMA_VERSION;
import static io42630.rankbot.jobs.JobContracts.identity;

public final class JobStore {
	private static final Object JVM_LOCK = new Object();

	public enum Phase {
		PENDING,
		CLAIMED,
		TERMINAL
	}

	public record Envelope(Request request, Phase phase, String token, Instant leaseUntil, Result result) {
	}

	public record Claim(Request request, String token) {
	}

	private final Path root;
	private final Clock clock;
	private final Duration lease;

	public JobStore(Path root, Duration lease) throws IOException {
		this(root, lease, Clock.systemUTC());
	}

	public JobStore(Path root, Duration lease, Clock clock) throws IOException {
		if (lease.isNegative() || lease.isZero()) {
			throw new IllegalArgumentException("Positive lease required");
		}
		this.root = root.toAbsolutePath();
		this.clock = clock;
		this.lease = lease;
		JsonFiles.privateDirectory(this.root);
	}

	public void publish(Request request) throws IOException {
		locked(() -> {
			Path file = file(request.jobId());
			if (Files.exists(file)) {
				throw new IOException("Job identity already exists");
			}
			if (!request.deadline().isAfter(clock.instant())) {
				throw new IOException("Job deadline expired");
			}
			JsonFiles.write(file, new Envelope(request, Phase.PENDING, "", null, null));
			return null;
		});
	}

	public Optional<Claim> claim() throws IOException {
		return locked(() -> {
			ArrayList<Path> files = new ArrayList<>();
			try (var entries = Files.newDirectoryStream(root, "*.json")) {
				entries.forEach(files::add);
			}
			files.sort(Comparator.comparing(Path::toString));
			for (Path file : files) {
				Envelope envelope = expire(file, read(file));
				if (envelope.phase() != Phase.PENDING) {
					continue;
				}
				String token = UUID.randomUUID().toString();
				JsonFiles.write(
						file, new Envelope(
								envelope.request(), Phase.CLAIMED, token,
								leaseEnd(envelope.request()), null
						)
				);
				return Optional.of(new Claim(envelope.request(), token));
			}
			return Optional.empty();
		});
	}

	public boolean heartbeat(Claim claim) throws IOException {
		return locked(() -> {
			Path file = file(claim.request().jobId());
			Envelope envelope = expire(file, read(file));
			if (!owns(envelope, claim)) {
				return false;
			}
			JsonFiles.write(
					file, new Envelope(
							envelope.request(), Phase.CLAIMED, claim.token(),
							leaseEnd(envelope.request()), null
					)
			);
			return true;
		});
	}

	public boolean complete(Claim claim, Result result) throws IOException {
		if (!claim.request().jobId().equals(result.jobId())) {
			throw new IllegalArgumentException("Result identity mismatch");
		}
		return locked(() -> {
			Path file = file(result.jobId());
			Envelope envelope = expire(file, read(file));
			if (!owns(envelope, claim)) {
				return false;
			}
			JsonFiles.write(file, new Envelope(envelope.request(), Phase.TERMINAL, "", null, result));
			return true;
		});
	}

	public Optional<Result> result(String jobId) throws IOException {
		return locked(() -> {
			Path file = file(jobId);
			if (!Files.exists(file)) {
				return Optional.empty();
			}
			return Optional.ofNullable(expire(file, read(file)).result());
		});
	}

	public boolean exists(String jobId) {
		return Files.exists(file(jobId));
	}

	public Optional<Request> request(String jobId) throws IOException {
		return locked(() -> {
			Path file = file(jobId);
			return Files.exists(file)
					? Optional.of(expire(file, read(file)).request())
					: Optional.empty();
		});
	}

	public boolean workerAvailable() throws IOException {
		try (var channel = FileChannel.open(
				root.resolve(".worker.lock"),
				StandardOpenOption.CREATE,
				StandardOpenOption.WRITE
		)) {
			try (var lock = channel.tryLock()) {
				return lock == null;
			} catch (java.nio.channels.OverlappingFileLockException exception) {
				return true;
			}
		}
	}

	private Envelope read(Path file) throws IOException {
		Envelope envelope = JsonFiles.read(file, Envelope.class);
		if (!file.getFileName().toString().equals(envelope.request().jobId() + ".json")) {
			throw new IOException("Job filename identity mismatch");
		}
		if (envelope.phase() == null || (envelope.phase() == Phase.CLAIMED
										 && (envelope.token() == null
											 || envelope.token().isBlank()
											 || envelope.leaseUntil() == null))
			|| (envelope.phase() == Phase.TERMINAL && envelope.result() == null)
			|| (envelope.phase() != Phase.TERMINAL && envelope.result() != null)
			|| (envelope.result() != null && !envelope.request().jobId().equals(envelope.result().jobId()))) {
			throw new IOException("Invalid job lifecycle");
		}
		return envelope;
	}

	private Envelope expire(Path file, Envelope envelope) throws IOException {
		if (envelope.phase() != Phase.TERMINAL && (!envelope.request().deadline().isAfter(clock.instant())
												   || (envelope.phase() == Phase.CLAIMED && !envelope.leaseUntil()
				.isAfter(clock.instant())))) {
			envelope = new Envelope(
					envelope.request(), Phase.TERMINAL, "", null,
					new Result(
							SCHEMA_VERSION,
							envelope.request().jobId(),
							ResultStatus.TIMEOUT,
							"",
							"Job deadline or worker lease expired"
					)
			);
			JsonFiles.write(file, envelope);
		}
		return envelope;
	}

	private boolean owns(Envelope envelope, Claim claim) {
		return envelope.phase() == Phase.CLAIMED && envelope.token().equals(claim.token())
			   && envelope.request().equals(claim.request());
	}

	private Instant leaseEnd(Request request) {
		Instant end = clock.instant().plus(lease);
		return end.isBefore(request.deadline())
				? end
				: request.deadline();
	}

	private Path file(String jobId) {
		identity(jobId);
		return root.resolve(jobId + ".json");
	}

	private <T> T locked(IOCall<T> action) throws IOException {
		synchronized (JVM_LOCK) {
			try (FileChannel channel = FileChannel.open(
					root.resolve(".lock"),
					StandardOpenOption.CREATE,
					StandardOpenOption.WRITE
			);
				 var lock = channel.lock()) {
				return action.run();
			}
		}
	}

	private interface IOCall<T> {
		T run() throws IOException;
	}
}
