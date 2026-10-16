package dev.gurney.minecraftmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.gurney.minecraftmcp.MinecraftMcp;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;

/**
 * A stateless MCP server (protocol revision 2026-07-28) over Streamable HTTP, answering every
 * request with a single JSON response.
 */
public final class McpHttpServer {
	private static final String PROTOCOL_VERSION = "2026-07-28";
	private static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
	private static final int MAX_BODY_BYTES = 1 << 20;
	private static final long LIST_TTL_MILLIS = 60 * 60 * 1000;

	private static final int PARSE_ERROR = -32700;
	private static final int INVALID_REQUEST = -32600;
	private static final int METHOD_NOT_FOUND = -32601;
	private static final int INVALID_PARAMS = -32602;
	private static final int INTERNAL_ERROR = -32603;
	private static final int HEADER_MISMATCH = -32020;
	private static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

	private static final JsonArray TOOLS = loadTools();
	private static final String INSTRUCTIONS = loadResource("instructions.md");

	private static HttpServer server;

	private McpHttpServer() {
	}

	public static void start(int port) throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		server.createContext("/mcp", McpHttpServer::handle);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();
	}

	public static void stop() {
		server.stop(0);
	}

	private static void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			Response response = respond(exchange);
			byte[] body = response.body == null ? new byte[0] : response.body.toString().getBytes(StandardCharsets.UTF_8);
			if (response.body != null) {
				exchange.getResponseHeaders().set("Content-Type", "application/json");
			}
			if (response.status == 405) {
				exchange.getResponseHeaders().set("Allow", "POST");
			}
			exchange.sendResponseHeaders(response.status, body.length == 0 ? -1 : body.length);
			if (body.length > 0) {
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			}
		}
	}

	private record Response(int status, JsonObject body) {
	}

	private static final class RequestError extends RuntimeException {
		final int status;
		final int code;
		final JsonObject data;

		RequestError(int status, int code, String message, JsonObject data) {
			super(message);
			this.status = status;
			this.code = code;
			this.data = data;
		}

		RequestError(int status, int code, String message) {
			this(status, code, message, null);
		}
	}

	private static Response respond(HttpExchange exchange) throws IOException {
		if (!isAllowedOrigin(exchange.getRequestHeaders().getFirst("Origin"))) {
			return new Response(403, error(null, INVALID_REQUEST, "requests from web pages are not allowed", null));
		}
		if (!exchange.getRequestMethod().equals("POST")) {
			return new Response(405, null);
		}

		byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
		if (bytes.length > MAX_BODY_BYTES) {
			return new Response(413, error(null, INVALID_REQUEST, "request body is larger than " + MAX_BODY_BYTES + " bytes", null));
		}
		JsonObject request;
		try {
			JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) {
				return new Response(400, error(null, INVALID_REQUEST, "the body must be a single JSON-RPC request", null));
			}
			request = parsed.getAsJsonObject();
		} catch (JsonParseException e) {
			return new Response(400, error(null, PARSE_ERROR, "invalid JSON: " + e.getMessage(), null));
		}

		JsonElement id = request.get("id");
		try {
			if (!"2.0".equals(stringField(request, "jsonrpc")) || stringField(request, "method") == null) {
				throw new RequestError(400, INVALID_REQUEST, "not a JSON-RPC 2.0 request");
			}
			if (id == null) {
				// This revision defines no client notifications over HTTP; accept and ignore any.
				return new Response(202, null);
			}
			String method = stringField(request, "method");
			JsonObject params = request.has("params") && request.get("params").isJsonObject() ? request.getAsJsonObject("params") : new JsonObject();
			validateHeaders(exchange, method, params);
			JsonObject result = switch (method) {
				case "server/discover" -> discover();
				case "tools/list" -> listTools();
				case "tools/call" -> callTool(params);
				default -> throw new RequestError(404, METHOD_NOT_FOUND, "method not found: " + method);
			};
			result.addProperty("resultType", "complete");
			result.add("_meta", serverInfoMeta());
			JsonObject response = new JsonObject();
			response.addProperty("jsonrpc", "2.0");
			response.add("id", id);
			response.add("result", result);
			return new Response(200, response);
		} catch (RequestError e) {
			return new Response(e.status, error(id, e.code, e.getMessage(), e.data));
		} catch (RuntimeException e) {
			MinecraftMcp.LOGGER.error("MCP request failed", e);
			return new Response(500, error(id, INTERNAL_ERROR, "internal error: " + e, null));
		}
	}

	private static void validateHeaders(HttpExchange exchange, String method, JsonObject params) {
		String headerVersion = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
		if (headerVersion == null) {
			throw new RequestError(400, HEADER_MISMATCH, "missing MCP-Protocol-Version header; this server only supports MCP protocol version " + PROTOCOL_VERSION);
		}
		// Checked before the body so that older clients, whose requests lack the body field, are told which version to use.
		if (!headerVersion.equals(PROTOCOL_VERSION)) {
			JsonObject data = new JsonObject();
			JsonArray supported = new JsonArray();
			supported.add(PROTOCOL_VERSION);
			data.add("supported", supported);
			data.addProperty("requested", headerVersion);
			throw new RequestError(400, UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version; this server only supports " + PROTOCOL_VERSION, data);
		}
		JsonObject meta = params.has("_meta") && params.get("_meta").isJsonObject() ? params.getAsJsonObject("_meta") : new JsonObject();
		String bodyVersion = stringField(meta, META_PROTOCOL_VERSION);
		if (!headerVersion.equals(bodyVersion)) {
			throw new RequestError(400, HEADER_MISMATCH, "MCP-Protocol-Version header '" + headerVersion + "' does not match params._meta[\"" + META_PROTOCOL_VERSION + "\"] '" + bodyVersion + "'");
		}
		String headerMethod = exchange.getRequestHeaders().getFirst("Mcp-Method");
		if (!method.equals(headerMethod)) {
			throw new RequestError(400, HEADER_MISMATCH, "Mcp-Method header '" + headerMethod + "' does not match body method '" + method + "'");
		}
		if (method.equals("tools/call")) {
			String headerName = decodeHeaderValue(exchange.getRequestHeaders().getFirst("Mcp-Name"));
			String bodyName = stringField(params, "name");
			if (headerName == null || !headerName.equals(bodyName)) {
				throw new RequestError(400, HEADER_MISMATCH, "Mcp-Name header '" + headerName + "' does not match body name '" + bodyName + "'");
			}
		}
	}

	private static JsonObject discover() {
		JsonObject result = new JsonObject();
		JsonArray versions = new JsonArray();
		versions.add(PROTOCOL_VERSION);
		result.add("supportedVersions", versions);
		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", new JsonObject());
		result.add("capabilities", capabilities);
		result.addProperty("instructions", INSTRUCTIONS);
		result.addProperty("ttlMs", LIST_TTL_MILLIS);
		result.addProperty("cacheScope", "private");
		return result;
	}

	private static JsonObject listTools() {
		JsonObject result = new JsonObject();
		result.add("tools", TOOLS.deepCopy());
		result.addProperty("ttlMs", LIST_TTL_MILLIS);
		result.addProperty("cacheScope", "private");
		return result;
	}

	/** Only called once header validation has confirmed params.name is a string. */
	private static JsonObject callTool(JsonObject params) {
		String name = stringField(params, "name");
		JsonElement arguments = params.get("arguments");
		if (arguments != null && !arguments.isJsonObject()) {
			throw new RequestError(200, INVALID_PARAMS, "params.arguments must be an object");
		}
		try {
			return ToolCalls.call(name, arguments == null ? new JsonObject() : arguments.getAsJsonObject());
		} catch (ToolCalls.UnknownToolException e) {
			throw new RequestError(200, INVALID_PARAMS, e.getMessage());
		}
	}

	private static JsonObject serverInfoMeta() {
		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "minecraft-mcp");
		serverInfo.addProperty("version", MinecraftMcp.version());
		JsonObject meta = new JsonObject();
		meta.add("io.modelcontextprotocol/serverInfo", serverInfo);
		return meta;
	}

	private static JsonObject error(JsonElement id, int code, String message, JsonObject data) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		if (data != null) {
			error.add("data", data);
		}
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		if (id != null) {
			response.add("id", id);
		}
		response.add("error", error);
		return response;
	}

	/** Browsers always send Origin, so only pages served from this machine may call the server. */
	private static boolean isAllowedOrigin(String origin) {
		if (origin == null) {
			return true;
		}
		try {
			String host = URI.create(origin).getHost();
			return List.of("localhost", "127.0.0.1", "[::1]").contains(host);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** Decodes the "=?base64?...?=" form clients use for header values that aren't plain ASCII. */
	private static String decodeHeaderValue(String value) {
		if (value != null && value.startsWith("=?base64?") && value.endsWith("?=")) {
			try {
				return new String(Base64.getDecoder().decode(value.substring(9, value.length() - 2)), StandardCharsets.UTF_8);
			} catch (IllegalArgumentException e) {
				throw new RequestError(400, HEADER_MISMATCH, "invalid base64 in header value '" + value + "'");
			}
		}
		return value;
	}

	private static String stringField(JsonObject object, String field) {
		JsonElement value = object.get(field);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	private static JsonArray loadTools() {
		return JsonParser.parseString(loadResource("tools.json")).getAsJsonArray();
	}

	private static String loadResource(String name) {
		String path = "/assets/minecraft-mcp/mcp/" + name;
		try (InputStream in = Objects.requireNonNull(McpHttpServer.class.getResourceAsStream(path), path)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("reading bundled " + path + " failed", e);
		}
	}
}
