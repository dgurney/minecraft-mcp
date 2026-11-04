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
 * A stateless MCP server over Streamable HTTP, answering every request with a single JSON
 * response. A request that carries its protocol version in params._meta speaks revision
 * 2026-07-28; any other request is from an older revision (2025-03-26 to 2025-11-25), which
 * begins with the initialize handshake instead. The server stays stateless for both: it mints
 * no session IDs.
 */
public final class McpHttpServer {
	private static final String PROTOCOL_VERSION = "2026-07-28";
	/** The older Streamable HTTP revisions, newest first. They all use the initialize handshake and the optional version header. */
	private static final List<String> LEGACY_PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");
	private static final String LATEST_LEGACY_PROTOCOL_VERSION = LEGACY_PROTOCOL_VERSIONS.getFirst();
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

	/** The port actually bound, for tests, which pass 0 to {@link #start} for an ephemeral one. */
	static int port() {
		return server.getAddress().getPort();
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
				// 2026-07-28 defines no client notifications over HTTP; the older revisions do, e.g. notifications/initialized. Accept and ignore any.
				return new Response(202, null);
			}
			String method = stringField(request, "method");
			JsonObject params = request.has("params") && request.get("params").isJsonObject() ? request.getAsJsonObject("params") : new JsonObject();
			// 2026-07-28 carries the protocol version in every request body; older revisions carry it, at most, in a header.
			JsonObject meta = params.has("_meta") && params.get("_meta").isJsonObject() ? params.getAsJsonObject("_meta") : new JsonObject();
			boolean modern = stringField(meta, META_PROTOCOL_VERSION) != null;
			if (modern) {
				validateModernHeaders(exchange, method, params);
			} else {
				validateLegacyVersionHeader(exchange);
			}
			JsonObject result = switch (method) {
				case "server/discover" -> discover(modern);
				case "tools/list" -> listTools(modern);
				case "tools/call" -> callTool(params);
				case "initialize" -> initialize(params);
				case "ping" -> new JsonObject();
				default -> throw new RequestError(404, METHOD_NOT_FOUND, "method not found: " + method);
			};
			if (modern) {
				// Caching hints and result typing are 2026-07-28 only; older clients must not be shown them.
				result.addProperty("resultType", "complete");
				result.add("_meta", serverInfoMeta());
			}
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

	/** The 2026-07-28 rules, unchanged: version header required and mirrored in the body, method and name headers matching it. */
	private static void validateModernHeaders(HttpExchange exchange, String method, JsonObject params) {
		String headerVersion = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
		if (headerVersion == null) {
			throw new RequestError(400, HEADER_MISMATCH, "missing MCP-Protocol-Version header; " + supportedVersionsText());
		}
		if (!headerVersion.equals(PROTOCOL_VERSION)) {
			throw new RequestError(400, UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version '" + headerVersion + "'; " + supportedVersionsText(), unsupportedVersionData(headerVersion));
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

	/**
	 * The older revisions send no version header at all (2025-03-26, and every revision's initialize request),
	 * or one of the negotiated legacy versions (2025-06-18 and later). The Mcp-Method and Mcp-Name headers do
	 * not exist in them and are not checked.
	 */
	private static void validateLegacyVersionHeader(HttpExchange exchange) {
		String headerVersion = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
		if (headerVersion == null || LEGACY_PROTOCOL_VERSIONS.contains(headerVersion)) {
			return;
		}
		if (headerVersion.equals(PROTOCOL_VERSION)) {
			// A 2026-07-28 claim must also be carried in the request body; keep that revision's own error for it.
			throw new RequestError(400, HEADER_MISMATCH, "MCP-Protocol-Version header '" + headerVersion + "' does not match params._meta[\"" + META_PROTOCOL_VERSION + "\"] 'null'");
		}
		throw new RequestError(400, UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version '" + headerVersion + "'; " + supportedVersionsText(), unsupportedVersionData(headerVersion));
	}

	private static String supportedVersionsText() {
		return "this server supports " + PROTOCOL_VERSION + " and the older revisions " + String.join(", ", LEGACY_PROTOCOL_VERSIONS);
	}

	private static JsonObject unsupportedVersionData(String requested) {
		JsonObject data = new JsonObject();
		JsonArray supported = new JsonArray();
		supported.add(PROTOCOL_VERSION);
		LEGACY_PROTOCOL_VERSIONS.forEach(supported::add);
		data.add("supported", supported);
		data.addProperty("requested", requested);
		return data;
	}

	private static JsonObject discover(boolean modern) {
		JsonObject result = new JsonObject();
		JsonArray versions = new JsonArray();
		versions.add(PROTOCOL_VERSION);
		result.add("supportedVersions", versions);
		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", new JsonObject());
		result.add("capabilities", capabilities);
		result.addProperty("instructions", INSTRUCTIONS);
		if (modern) {
			result.addProperty("ttlMs", LIST_TTL_MILLIS);
			result.addProperty("cacheScope", "private");
		}
		return result;
	}

	private static JsonObject listTools(boolean modern) {
		JsonObject result = new JsonObject();
		result.add("tools", TOOLS.deepCopy());
		if (modern) {
			result.addProperty("ttlMs", LIST_TTL_MILLIS);
			result.addProperty("cacheScope", "private");
		}
		return result;
	}

	/** The initialize handshake of the older revisions: answer with the client's version when it is one this server speaks, else the newest one it does. */
	private static JsonObject initialize(JsonObject params) {
		String requested = stringField(params, "protocolVersion");
		String negotiated = requested != null && LEGACY_PROTOCOL_VERSIONS.contains(requested) ? requested : LATEST_LEGACY_PROTOCOL_VERSION;
		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", negotiated);
		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", new JsonObject());
		result.add("capabilities", capabilities);
		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "minecraft-mcp");
		serverInfo.addProperty("version", SERVER_VERSION);
		result.add("serverInfo", serverInfo);
		result.addProperty("instructions", INSTRUCTIONS);
		return result;
	}

	/** Modern requests reach this with params.name confirmed by the Mcp-Name check; legacy ones send no such header, so check here. */
	private static JsonObject callTool(JsonObject params) {
		String name = stringField(params, "name");
		if (name == null) {
			throw new RequestError(200, INVALID_PARAMS, "params.name must be a string");
		}
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

	/** The mod version from the loader, with a fallback for when the loader isn't running (unit tests). */
	private static final String SERVER_VERSION = determineServerVersion();

	private static String determineServerVersion() {
		try {
			return MinecraftMcp.version();
		} catch (RuntimeException | LinkageError e) {
			return "dev";
		}
	}

	private static JsonObject serverInfoMeta() {
		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "minecraft-mcp");
		serverInfo.addProperty("version", SERVER_VERSION);
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
			return host != null && List.of("localhost", "127.0.0.1", "[::1]").contains(host);
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
