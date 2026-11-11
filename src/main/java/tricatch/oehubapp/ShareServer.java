package tricatch.oehubapp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Serves the shared oeHosts profiles as plain hosts text, for hosts managers that subscribe to a
 * URL (SwitchHosts' "Remote" entries): {@code GET http://127.0.0.1:<settings.shareServerPort>/hosts/<id>-<tag>/<name>},
 * the tag being an HMAC of the id under a key only this installation has ({@link Store#shareToken}).
 * The trailing name ({@link Store#shareSlug}) is only there for people and is ignored, so a
 * renamed profile keeps working under its old URL.
 * Only profiles whose {@link Store.Item#shared} is on are served; a wrong tag, an unknown id and a
 * profile whose sharing was turned off all answer the same 404, so ids cannot be probed. {@code ${PROXY_SVR}} / {@code ${LOCAL_SVR}} are filled in
 * ({@link HostsRules#resolvePlaceholders}), the same as everywhere else in the app.
 *
 * <p>Bound to the loopback address only: its clients run on this PC. A request whose Host header is
 * not a loopback name is refused, so a web page cannot read the profiles through DNS rebinding.
 * Uses the JDK's built-in {@code com.sun.net.httpserver} (module jdk.httpserver), so no dependency.
 */
public final class ShareServer {

    public enum State { STOPPED, RUNNING, FAILED }

    public static final String PATH = "/hosts/";

    private static final Logger log = LoggerFactory.getLogger(ShareServer.class);
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static HttpServer server;
    private static Store store;
    private static volatile State state = State.STOPPED;
    private static volatile String error;
    private static volatile int port;

    private ShareServer() {}

    public static State state() {
        return state;
    }

    /** Why the last start failed, or null. */
    public static String error() {
        return error;
    }

    /** The port of the last start attempt. */
    public static int port() {
        return port;
    }

    /** Runs {@code r} (on the thread that changed it) whenever the state changes. */
    public static void onChange(Runnable r) {
        listeners.add(r);
    }

    /** The URL a profile is shared at: its {@link Store#shareToken}, then its name as {@link Store#shareSlug}. */
    public static String url(int port, String token, String name) {
        return "http://127.0.0.1:" + port + PATH + token + "/" + Store.shareSlug(name);
    }

    /** Starts on the port stored in the settings. */
    public static synchronized void start(Store s) {
        store = s;
        int p;
        synchronized (s) {
            p = s.data.settings.shareServerPort;
        }
        port = p;
        try {
            HttpServer srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), p), 0);
            srv.createContext(PATH, ShareServer::handle);
            srv.createContext("/", ex -> {
                try (ex) {
                    respond(ex, 404, "not found\n");
                }
            });
            srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            srv.start();
            server = srv;
            set(State.RUNNING, null);
            log.info("share server on 127.0.0.1:{}", p);
        } catch (IOException | RuntimeException e) {
            log.warn("share server failed to start on {}: {}", p, e.getMessage());
            set(State.FAILED, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** Stops and starts again on the port now stored in the settings (after it was saved). */
    public static synchronized void restart() {
        stop();
        if (store != null) start(store);
    }

    public static synchronized void stop() {
        if (server == null) return;
        server.stop(0);
        server = null;
        set(State.STOPPED, null);
    }

    private static void set(State s, String err) {
        state = s;
        error = err;
        for (Runnable r : listeners) r.run();
    }

    private static void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!loopbackHost(ex.getRequestHeaders().getFirst("Host"))) {
                respond(ex, 403, "forbidden\n");
                return;
            }
            String method = ex.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD");
                respond(ex, 405, "method not allowed\n");
                return;
            }
            // <token>/<name>: the name is ignored (and may be missing).
            String token = ex.getRequestURI().getPath().substring(PATH.length());
            int slash = token.indexOf('/');
            if (slash >= 0) token = token.substring(0, slash);
            String content;
            synchronized (store) {
                content = store.sharedHostsContentByToken(token);
            }
            if (content == null) {
                respond(ex, 404, "not found\n");
                return;
            }
            respond(ex, 200, HostsRules.resolvePlaceholders(content));
        }
    }

    /** Host header names this server answers to: 127.0.0.1, localhost or [::1], with any port. */
    static boolean loopbackHost(String host) {
        if (host == null) return false;
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 && h.substring(1, end).equals("::1");
        }
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        return h.equals("127.0.0.1") || h.equals("localhost");
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        boolean head = "HEAD".equals(ex.getRequestMethod());
        ex.sendResponseHeaders(status, head ? -1 : b.length);
        if (!head) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(b);
            }
        }
    }
}
