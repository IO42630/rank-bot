package com.olexyn.rank.bot.solver;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class LocalAgentRunner {
	@Inject
	ObjectMapper mapper;
	@ConfigProperty(name = "deepseek.api-key")
	Optional<String> apiKey;
	@ConfigProperty(name = "deepseek.url")
	URI url;
	@ConfigProperty(name = "deepseek.model")
	String model;
	@ConfigProperty(name = "deepseek.timeout-seconds")
	long timeoutSeconds;

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public String solve(String text) {
		if (text == null || text.isBlank()) {
			throw new BadRequestException();
		}
		String key = apiKey.filter(value -> !value.isBlank())
				.orElseThrow(() -> new WebApplicationException(503));
		try {
			String body = mapper.writeValueAsString(Map.of(
					"model", model, "stream", false,
					"messages", List.of(Map.of("role", "user", "content", text))
			));
			HttpRequest request = HttpRequest.newBuilder(url)
					.timeout(Duration.ofSeconds(timeoutSeconds))
					.header("Authorization", "Bearer " + key)
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new WebApplicationException(502);
			}
			var content = mapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
			if (!content.isTextual() || content.asText().isBlank()) {
				throw new WebApplicationException(502);
			}
			return content.asText();
		} catch (HttpTimeoutException exception) {
			throw new WebApplicationException(exception, 504);
		} catch (IOException exception) {
			throw new WebApplicationException(exception, 502);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new WebApplicationException(exception, 503);
		}
	}
}