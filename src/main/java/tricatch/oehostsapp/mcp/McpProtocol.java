package tricatch.oehostsapp.mcp;

import tricatch.oehostsapp.OeHostsApp;
import tricatch.oehostsapp.store.Store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The JSON-RPC side of MCP for a tools-only server: {@code initialize}, {@code ping},
 * {@code tools/list} and {@code tools/call}. One message in, at most one message out; no sessions,
 * no server-to-client requests, no streaming. Kept apart from the HTTP transport
 * ({@link McpServer}) so it can be tested without a socket.
 */
final class McpProtocol {

    private static final Logger log = LoggerFactory.getLogger(McpProtocol.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Protocol revisions this server speaks, newest first; a client asking for another gets the newest. */
    static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;
    static final int INTERNAL_ERROR = -32603;

    private static final String INSTRUCTIONS = """
            oeHostsApp is a desktop app that maps host names to IP addresses for development (oeHosts profiles, \
            applied through its proxy service, its Chrome launcher and oeHosts-Agent, never the system hosts file) \
            and runs an HTTPS reverse proxy with YAML routes (oeProxy virtual hosts). These tools only read: \
            use list_hosts_profiles / list_vhosts for ids, get_active_hosts to see where a host name goes, \
            and get_proxy_status when a request through the proxies fails.""";

    private final McpTools tools;

    McpProtocol(Store store) {
        this.tools = new McpTools(store);
    }

    /** The reply to {@code msg}, or null when none is due (a notification or a client's response). */
    JsonNode handle(JsonNode msg) {
        if (msg == null || !msg.isObject()) return error(null, INVALID_REQUEST, "Expected one JSON-RPC message object");
        JsonNode id = msg.get("id");
        JsonNode method = msg.get("method");
        if (method == null) {
            // A response to a request of ours; this server sends none, so there is nothing to match.
            if (msg.has("result") || msg.has("error")) return null;
            return error(id, INVALID_REQUEST, "Missing method");
        }
        if (!"2.0".equals(msg.path("jsonrpc").asText(null)) || !method.isTextual()) {
            return id == null ? null : error(id, INVALID_REQUEST, "Not a JSON-RPC 2.0 request");
        }
        // Notifications (notifications/initialized, notifications/cancelled...) need no answer.
        if (id == null || id.isNull()) return null;
        JsonNode params = msg.get("params");
        try {
            return switch (method.asText()) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, JSON.objectNode());
                case "tools/list" -> result(id, toolsList());
                case "tools/call" -> call(id, params);
                default -> error(id, METHOD_NOT_FOUND, "Method not found: " + method.asText());
            };
        } catch (RuntimeException e) {
            log.warn("MCP {} failed", method.asText(), e);
            return error(id, INTERNAL_ERROR, "Internal error");
        }
    }

    private static ObjectNode initialize(JsonNode params) {
        String asked = params == null ? null : params.path("protocolVersion").asText(null);
        ObjectNode r = JSON.objectNode();
        r.put("protocolVersion", VERSIONS.contains(asked) ? asked : VERSIONS.get(0));
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", "oehostsapp");
        info.put("title", "oeHostsApp");
        info.put("version", OeHostsApp.version());
        r.put("instructions", INSTRUCTIONS);
        return r;
    }

    private ObjectNode toolsList() {
        ObjectNode r = JSON.objectNode();
        ArrayNode list = r.putArray("tools");
        for (McpTools.Tool t : tools.all()) {
            ObjectNode n = list.addObject();
            n.put("name", t.name());
            n.put("title", t.title());
            n.put("description", t.description());
            n.set("inputSchema", t.inputSchema());
            ObjectNode a = n.putObject("annotations");
            a.put("title", t.title());
            a.put("readOnlyHint", true);
            a.put("destructiveHint", false);
            a.put("idempotentHint", true);
            a.put("openWorldHint", false);
        }
        return r;
    }

    private ObjectNode call(JsonNode id, JsonNode params) {
        String name = params == null ? null : params.path("name").asText(null);
        McpTools.Tool t = tools.find(name);
        if (t == null) return error(id, INVALID_PARAMS, "Unknown tool: " + name);
        JsonNode args = params.get("arguments");
        if (args != null && !args.isNull() && !args.isObject()) return result(id, toolError("arguments must be an object"));
        try {
            JsonNode out = t.run().apply(args);
            ObjectNode r = JSON.objectNode();
            r.putArray("content").addObject().put("type", "text").put("text", PRETTY.writeValueAsString(out));
            r.set("structuredContent", out);
            r.put("isError", false);
            return result(id, r);
        } catch (McpTools.ToolError e) {
            return result(id, toolError(e.getMessage()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode toolError(String text) {
        ObjectNode r = JSON.objectNode();
        r.putArray("content").addObject().put("type", "text").put("text", text);
        r.put("isError", true);
        return r;
    }

    private static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode r = JSON.objectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r;
    }

    static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = JSON.objectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? JSON.nullNode() : id);
        ObjectNode e = r.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return r;
    }
}
