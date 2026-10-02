package com.olexyn.rank.bot.solver;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class DeepSeekFixture implements QuarkusTestResourceLifecycleManager {
	static volatile int status = 200;
	static volatile String response;
	static volatile long delay;
	static AtomicReference<String> request = new AtomicReference<>();
	static AtomicReference<String> authorization = new AtomicReference<>();
	static AtomicInteger calls = new AtomicInteger();
	private HttpServer server;

	@Override
	public Map<String, String> start() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/chat/completions", exchange -> {
				calls.incrementAndGet();
				request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
				int code = status;
				byte[] body = response.getBytes(StandardCharsets.UTF_8);
				try {
					Thread.sleep(delay);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				try (exchange) {
					exchange.sendResponseHeaders(code, body.length);
					exchange.getResponseBody().write(body);
				}
			});
			server.start();
			return Map.of("deepseek.url", "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions",
					"deepseek.api-key", "test-key", "deepseek.timeout-seconds", "1");
		} catch (java.io.IOException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Override
	public void stop() {
		server.stop(0);
	}
}