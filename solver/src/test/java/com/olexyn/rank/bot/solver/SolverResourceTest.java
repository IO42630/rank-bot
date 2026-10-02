package com.olexyn.rank.bot.solver;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(DeepSeekFixture.class)
class SolverResourceTest {
	@BeforeEach
	void reset() {
		DeepSeekFixture.status = 200;
		DeepSeekFixture.response = "{\"choices\":[{\"message\":{\"content\":\"The reply\\n第二行\"}}]}";
		DeepSeekFixture.delay = 0;
		DeepSeekFixture.calls.set(0);
	}

	@Test
	void forwardsTextAndReturnsOnlyReply() throws Exception {
		String text = "Some \"quoted\" text\n第二行";
		given().contentType("text/plain; charset=utf-8").body(text).post("/solve")
				.then().statusCode(200).body(equalTo("The reply\n第二行"));
		var json = new ObjectMapper().readTree(DeepSeekFixture.request.get());
		assertEquals("deepseek-flash", json.path("model").asText());
		assertFalse(json.path("stream").asBoolean());
		assertEquals("user", json.path("messages").path(0).path("role").asText());
		assertEquals(text, json.path("messages").path(0).path("content").asText());
		assertEquals("Bearer test-key", DeepSeekFixture.authorization.get());
		assertEquals(1, DeepSeekFixture.calls.get());
	}

	@Test
	void rejectsBlankInputWithoutCallingDeepSeek() {
		given().contentType("text/plain").body(" \n").post("/solve").then().statusCode(400);
		assertEquals(0, DeepSeekFixture.calls.get());
	}

	@Test
	void missingKeyHasClearFailure() {
		var runner = new LocalAgentRunner();
		runner.apiKey = Optional.empty();
		assertEquals(503, assertThrows(WebApplicationException.class, () -> runner.solve("text"))
				.getResponse().getStatus());
	}

	@Test
	void upstreamErrorsAndInvalidResponsesBecomeBadGateway() {
		DeepSeekFixture.status = 401;
		given().contentType("text/plain").body("text").post("/solve").then().statusCode(502);
		DeepSeekFixture.status = 200;
		for (String response : new String[]{"not JSON", "{}", "{\"choices\":[]}",
				"{\"choices\":[{\"message\":{\"content\":null}}]}"}) {
			DeepSeekFixture.response = response;
			given().contentType("text/plain").body("text").post("/solve").then().statusCode(502);
		}
	}

	@Test
	void upstreamTimeoutBecomesGatewayTimeout() {
		DeepSeekFixture.delay = 1500;
		given().contentType("text/plain").body("text").post("/solve").then().statusCode(504);
	}
}