package dev.gurney.minecraftmcp.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the real HTTP server on an ephemeral port and speaks raw Streamable HTTP to it, once
 * per protocol era. Requests never call tools that need a running game client.
 */
class McpHttpServerTest {
	private static final HttpClient CLIENT = HttpClient.newHttpClient();

	private static int port;

	@BeforeAll
	static void startServer() throws IOException {
		McpHttpServer.start(0);
		port = McpHttpServer.port();
	}

	@AfterAll
	static void stopServer() {
		McpHttpServer.stop();
	}

	private static HttpResponse<String> post(Map<String, String> headers, String body) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		headers.forEach(request::header);
		return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static JsonObject json(HttpResponse<String> response) {
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private static JsonObject result(HttpResponse<String> response) {
		return json(response).getAsJsonObject("result");
	}

	private static JsonObject error(HttpResponse<String> response) {
		return json(response).getAsJsonObject("error");
	}

	private static String stringOf(JsonObject object, String field) {
		JsonElement value = object.get(field);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	@Test
	void modernRequestWithEnvelopeAndAllHeadersPasses() throws Exception {
		var response = post(
				Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/list"),
				"""
				{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}
				""");
		assertEquals(200, response.statusCode());
		JsonObject result = result(response);
		assertEquals("complete", stringOf(result, "resultType"));
		assertEquals(3_600_000L, result.get("ttlMs").getAsLong());
		assertEquals("private", stringOf(result, "cacheScope"));
		assertFalse(result.getAsJsonArray("tools").isEmpty());
		assertEquals("minecraft-mcp", stringOf(result.getAsJsonObject("_meta").getAsJsonObject("io.modelcontextprotocol/serverInfo"), "name"));
	}

	@Test
	void modernRequestWithoutVersionHeaderFailsAsBefore() throws Exception {
		var response = post(
				Map.of("Mcp-Method", "tools/list"),
				"""
				{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}
				""");
		assertEquals(400, response.statusCode());
		assertEquals(-32020, error(response).get("code").getAsInt());
		assertEquals(
				"missing MCP-Protocol-Version header; this server supports 2026-07-28 and the older revisions 2025-11-25, 2025-06-18, 2025-03-26",
				stringOf(error(response), "message"));
	}

	@Test
	void modernRequestWithWrongVersionHeaderFailsAsBefore() throws Exception {
		var response = post(
				Map.of("MCP-Protocol-Version", "2025-06-18", "Mcp-Method", "tools/list"),
				"""
				{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}
				""");
		assertEquals(400, response.statusCode());
		assertEquals(-32022, error(response).get("code").getAsInt());
		assertEquals("Unsupported protocol version '2025-06-18'; this server supports 2026-07-28 and the older revisions 2025-11-25, 2025-06-18, 2025-03-26", stringOf(error(response), "message"));
	}

	@Test
	void modernRequestWithWrongMethodHeaderFailsAsBefore() throws Exception {
		var response = post(
				Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call"),
				"""
				{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}
				""");
		assertEquals(400, response.statusCode());
		assertEquals(-32020, error(response).get("code").getAsInt());
		assertEquals("Mcp-Method header 'tools/call' does not match body method 'tools/list'", stringOf(error(response), "message"));
	}

	@Test
	void legacyInitializeReturnsAValidResult() throws Exception {
		var response = post(
				Map.of(),
				"""
				{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
				""");
		assertEquals(200, response.statusCode());
		JsonObject result = result(response);
		assertEquals("2025-06-18", stringOf(result, "protocolVersion"));
		assertNotNull(result.getAsJsonObject("capabilities").getAsJsonObject("tools"));
		assertEquals("minecraft-mcp", stringOf(result.getAsJsonObject("serverInfo"), "name"));
		assertNotNull(stringOf(result.getAsJsonObject("serverInfo"), "version"));
		assertFalse(stringOf(result, "instructions") == null || stringOf(result, "instructions").isEmpty());
		// The 2026-only result fields must not leak to older clients, and a stateless server mints no session.
		assertNull(result.get("ttlMs"));
		assertNull(result.get("cacheScope"));
		assertNull(result.get("resultType"));
		assertNull(result.get("_meta"));
		assertTrue(response.headers().firstValue("Mcp-Session-Id").isEmpty());
	}

	@Test
	void legacyInitializeWithUnsupportedVersionAnswersWithANewerOneItSupports() throws Exception {
		var response = post(
				Map.of(),
				"""
				{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1.0.0","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
				""");
		assertEquals(200, response.statusCode());
		assertEquals("2025-11-25", stringOf(result(response), "protocolVersion"));
	}

	@Test
	void legacyInitializeWithoutAVersionAnswersWithTheNewestLegacyOne() throws Exception {
		var response = post(
				Map.of(),
				"""
				{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
				""");
		assertEquals(200, response.statusCode());
		assertEquals("2025-11-25", stringOf(result(response), "protocolVersion"));
	}

	@Test
	void legacyInitializedNotificationReturns202WithNoBody() throws Exception {
		var response = post(Map.of(), """
		{"jsonrpc":"2.0","method":"notifications/initialized"}
		""");
		assertEquals(202, response.statusCode());
		assertEquals("", response.body());
	}

	@Test
	void legacyToolsListWithoutAVersionHeaderPasses() throws Exception {
		var response = post(Map.of(), """
		{"jsonrpc":"2.0","id":2,"method":"tools/list"}
		""");
		assertEquals(200, response.statusCode());
		JsonObject result = result(response);
		assertFalse(result.getAsJsonArray("tools").isEmpty());
		assertNull(result.get("ttlMs"));
		assertNull(result.get("cacheScope"));
		assertNull(result.get("resultType"));
		assertNull(result.get("_meta"));
	}

	@Test
	void legacyToolsListWithANegotiatedVersionHeaderPasses() throws Exception {
		var response = post(
				Map.of("MCP-Protocol-Version", "2025-06-18"),
				"""
				{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
				""");
		assertEquals(200, response.statusCode());
		assertFalse(result(response).getAsJsonArray("tools").isEmpty());
	}

	@Test
	void legacyPingAnswersWithAnEmptyResult() throws Exception {
		var response = post(
				Map.of("MCP-Protocol-Version", "2025-11-25"),
				"""
				{"jsonrpc":"2.0","id":3,"method":"ping"}
				""");
		assertEquals(200, response.statusCode());
		assertTrue(result(response).isEmpty());
	}

	@Test
	void unknownVersionHeaderFailsWithAClearError() throws Exception {
		var response = post(
				Map.of("MCP-Protocol-Version", "1999-01-01"),
				"""
				{"jsonrpc":"2.0","id":1,"method":"tools/list"}
				""");
		assertEquals(400, response.statusCode());
		JsonObject error = error(response);
		assertEquals(-32022, error.get("code").getAsInt());
		assertEquals(
				"Unsupported protocol version '1999-01-01'; this server supports 2026-07-28 and the older revisions 2025-11-25, 2025-06-18, 2025-03-26",
				stringOf(error, "message"));
		assertEquals("1999-01-01", stringOf(error.getAsJsonObject("data"), "requested"));
		assertTrue(error.getAsJsonObject("data").getAsJsonArray("supported").toString().contains("2026-07-28"));
	}

	@Test
	void versionHeaderClaiming2026WithoutTheBodyFieldFailsWithTheModernMismatch() throws Exception {
		var response = post(
				Map.of("MCP-Protocol-Version", "2026-07-28"),
				"""
				{"jsonrpc":"2.0","id":1,"method":"tools/list"}
				""");
		assertEquals(400, response.statusCode());
		assertEquals(-32020, error(response).get("code").getAsInt());
		assertEquals(
				"MCP-Protocol-Version header '2026-07-28' does not match params._meta[\"io.modelcontextprotocol/protocolVersion\"] 'null'",
				stringOf(error(response), "message"));
	}
}
