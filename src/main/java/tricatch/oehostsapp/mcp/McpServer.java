package tricatch.oehostsapp.mcp;

import tricatch.oehostsapp.Listeners;
import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.store.Store;
import tricatch.oehostsapp.web.ShareServer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The MCP server for AI coding tools (Claude Code): MCP's Streamable HTTP transport at
 * {@code http://127.0.0.1:<settings.mcp.port>/mcp}, answering each POSTed JSON-RPC message with one
 * JSON reply ({@link McpProtocol}); no event stream, no sessions. The tools are in {@link McpTools}.
 * It also serves the CLI's Claude Code skill at {@link CliSkill#PATH}, the only path without the token.
 *
 * <p>Runs while {@code settings.mcp.enabled} (on by default). It listens on 127.0.0.1 only, never on a network card,
 * and every request must carry {@code Authorization: Bearer <Store.mcpToken()>}. A Host header that
 * is not a loopback name, or an Origin that is not a loopback page, is refused before the token is
 * looked at: a web page in a browser on this PC must not reach it (DNS rebinding).
 */
public final class McpServer {

    public enum State { OFF, RUNNING, FAILED }

    public static final String PATH = "/mcp";
    /** Bigger requests are refused; a tools call is a few hundred bytes. */
    static final int MAX_BODY = 1 << 20;

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Listeners listeners = new Listeners();

    private static HttpServer server;
    private static ExecutorService executor;
    private static volatile Store store;
    private static volatile State state = State.OFF;
    private static volatile String error;
    private static volatile int port;

    private McpServer() {}

    public static State state() {
        return state;
    }

    /** Why the last start failed, or null. */
    public static String error() {
        return error;
    }

    /** The port of the last start attempt (0 before one). */
    public static int port() {
        return port;
    }

    /** The URL clients are registered with, for {@code port}. */
    public static String url(int port) {
        return "http://" + Network.LOOPBACK + ":" + port + PATH;
    }

    /** Runs {@code r} (on the thread of the change) whenever the state changes. */
    public static Listeners.Subscription onChange(Runnable r) {
        return listeners.add(r);
    }

    /** Starts on the stored port when the server is switched on; does nothing otherwise. */
    public static synchronized void start(Store s) {
        store = s;
        boolean on;
        int p;
        synchronized (s) {
            on = s.data.settings.mcp.enabled;
            p = s.data.settings.mcp.port;
        }
        port = p;
        if (!on) {
            set(State.OFF, null);
            return;
        }
        McpProtocol protocol = new McpProtocol(s);
        try {
            // Made (and saved) here rather than by the first request.
            synchronized (s) {
                s.mcpToken();
            }
            HttpServer h = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), p), 0);
            h.createContext("/", ex -> handle(ex, s, protocol));
            executor = Executors.newVirtualThreadPerTaskExecutor();
            h.setExecutor(executor);
            h.start();
            server = h;
            log.info("MCP server listening on {}", url(p));
            set(State.RUNNING, null);
        } catch (IOException | RuntimeException e) {
            stopExecutor();
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("MCP server failed to start on port {}: {}", p, msg);
            set(State.FAILED, msg);
        }
    }

    /** Stops, then starts again with the stored switch and port (after they were saved). */
    public static synchronized void restart() {
        stop();
        if (store != null) start(store);
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        stopExecutor();
        set(State.OFF, null);
    }

    private static void stopExecutor() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static void set(State s, String err) {
        state = s;
        error = err;
        listeners.fire();
    }

    // ── Requests ──────────────────────────────────────────────

    private static void handle(HttpExchange ex, Store s, McpProtocol protocol) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            if (!PATH.equals(path) && !CliSkill.PATH.equals(path)) {
                text(ex, 404, "Not found");
                return;
            }
            if (!loopbackHost(ex.getRequestHeaders().getFirst("Host"))
                    || !allowedOrigin(ex.getRequestHeaders().getFirst("Origin"))) {
                text(ex, 403, "Forbidden");
                return;
            }
            if (CliSkill.PATH.equals(path)) {
                skill(ex, s);
                return;
            }
            String token;
            synchronized (s) {
                token = s.mcpToken();
            }
            if (!authorized(ex.getRequestHeaders().getFirst("Authorization"), token)) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"oeHostsApp\"");
                text(ex, 401, "Unauthorized");
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                // No server-to-client event stream (GET) and no sessions to end (DELETE).
                ex.getResponseHeaders().set("Allow", "POST");
                text(ex, 405, "Method not allowed");
                return;
            }
            String version = ex.getRequestHeaders().getFirst("MCP-Protocol-Version");
            if (version != null && !McpProtocol.VERSIONS.contains(version)) {
                text(ex, 400, "Unsupported MCP-Protocol-Version: " + version);
                return;
            }
            String type = ex.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                text(ex, 415, "Expected application/json");
                return;
            }
            byte[] body = read(ex.getRequestBody());
            if (body == null) {
                text(ex, 413, "Request too large");
                return;
            }
            JsonNode msg;
            try {
                msg = JSON.readTree(body);
            } catch (IOException e) {
                json(ex, 400, McpProtocol.error(null, McpProtocol.PARSE_ERROR, "Parse error"));
                return;
            }
            JsonNode reply = msg != null && msg.isArray()
                    ? McpProtocol.error(null, McpProtocol.INVALID_REQUEST, "Batches are not supported")
                    : protocol.handle(msg);
            if (reply == null) {
                ex.sendResponseHeaders(202, -1);
                return;
            }
            json(ex, 200, reply);
        } catch (IOException e) {
            log.debug("MCP request: {}", e.getMessage());
        }
    }

    /**
     * The CLI skill ({@link CliSkill}): the one answer without the token, since it holds no secret
     * (paths of this PC only); the Host and Origin checks above still keep web pages out.
     */
    private static void skill(HttpExchange ex, Store s) throws IOException {
        String method = ex.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            ex.getResponseHeaders().set("Allow", "GET, HEAD");
            text(ex, 405, "Method not allowed");
            return;
        }
        Path dataDir = s.path().toAbsolutePath().getParent();
        String md = CliSkill.markdown(dataDir, port);
        if (md == null) {
            text(ex, 404, "No CLI skill: oeHostsApp is not running from its jar");
            return;
        }
        byte[] body = md.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/markdown; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "inline; filename=\"SKILL.md\"");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equals(method)) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
    }

    /** The body, or null when it is larger than {@link #MAX_BODY}. */
    private static byte[] read(InputStream in) throws IOException {
        byte[] b = in.readNBytes(MAX_BODY + 1);
        return b.length > MAX_BODY ? null : b;
    }

    /** A Host header naming this PC by a loopback name (any port); not a LAN address, not a domain. */
    static boolean loopbackHost(String host) {
        String h = ShareServer.hostName(host);
        return h != null && isLoopbackName(h);
    }

    /** No Origin (a command-line client), or a page served from a loopback name. */
    static boolean allowedOrigin(String origin) {
        if (origin == null) return true;
        try {
            String h = URI.create(origin.strip()).getHost();
            if (h == null) return false;
            h = h.toLowerCase(Locale.ROOT);
            if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
            return isLoopbackName(h);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isLoopbackName(String h) {
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1");
    }

    static boolean authorized(String header, String token) {
        if (header == null || token == null) return false;
        String h = header.strip();
        if (h.length() < 7 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        byte[] given = h.substring(7).strip().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(given, token.getBytes(StandardCharsets.US_ASCII));
    }

    private static void json(HttpExchange ex, int status, JsonNode body) throws IOException {
        send(ex, status, "application/json", JSON.writeValueAsBytes(body));
    }

    private static void text(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) ex.getResponseBody().write(body);
    }
}
