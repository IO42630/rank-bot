package io42630.rankbot.jobs;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

public final class JsonFiles {
	public static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

	private JsonFiles() {
	}

	public static <T> T read(Path path, Class<T> type) throws IOException {
		if (Files.size(path) > JobContracts.MAX_BYTES) {
			throw new IOException("JSON payload too large");
		}
		try (var input = Files.newInputStream(path)) {
			byte[] bytes = input.readNBytes(JobContracts.MAX_BYTES + 1);
			if (bytes.length > JobContracts.MAX_BYTES) {
				throw new IOException("JSON payload too large");
			}
			return MAPPER.readValue(bytes, type);
		}
	}

	public static void privateDirectory(Path directory) throws IOException {
		Files.createDirectories(directory);
		try {
			Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
		} catch (UnsupportedOperationException ignored) {
		}
	}

	public static void write(Path path, Object value) throws IOException {
		byte[] bytes = MAPPER.writeValueAsBytes(value);
		if (bytes.length > JobContracts.MAX_BYTES) {
			throw new IOException("JSON payload too large");
		}
		privateDirectory(path.toAbsolutePath().getParent());
		Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), ".publish-", ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
				channel.force(true);
			}
			Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}
