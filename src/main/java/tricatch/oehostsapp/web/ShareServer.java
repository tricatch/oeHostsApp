package tricatch.oehostsapp.web;

import tricatch.oehostsapp.Listeners;
import tricatch.oehostsapp.hosts.HostsRules;
import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.store.Store;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * The web service: the app's built-in HTTP server. It serves
 * <ul>
 *   <li>the shared oeHosts profiles as plain hosts text, for hosts managers that subscribe to a URL
 *   (SwitchHosts' "Remote" entries): {@code GET /hosts/<id>-<tag>/<name>}, the tag being an HMAC of
 *   the id under a key only this installation has ({@link Store#shareToken}). The trailing name
 *   ({@link Store#shareSlug}) is only there for people and is ignored, so a renamed profile keeps
 *   working under its old URL. Only profiles whose {@link Store.Item#shared} is on are served; a
 *   wrong tag, an unknown id and a profile whose sharing was turned off all answer the same 404, so
 *   ids cannot be probed. oeHub's placeholders are filled in ({@link HostsRules#resolvePlaceholders}).</li>
 *   <li>the selected profiles merged ({@link #ACTIVE_PATH}), for oeHosts-Agent.</li>
 *   <li>the phone setup page and the CA's public certificate ({@link PhoneSetup}).</li>
 * </ul>
 *
 * <p>Who can reach it is decided by the binding alone ({@code settings.web.bind}: 127.0.0.1,
 * one of this PC's IPv4 addresses, or 0.0.0.0). 127.0.0.1 always has a listener of its own, because
 * the share URLs given to programs on this PC use it. A request whose Host header is neither a
 * loopback name nor one of this PC's own IP addresses is refused, so a web page cannot read the
 * profiles through DNS rebinding.
 * Uses the JDK's built-in {@code com.sun.net.httpserver} (module jdk.httpserver), so no dependency.
 */
public final class ShareServer {

    public enum State { STOPPED, RUNNING, FAILED }

    public static final String PATH = "/hosts/";
    /** The merged hosts of the profiles selected in oeHosts, for oeHosts-Agent. */
    public static final String ACTIVE_PATH = "/active-hosts";

    private static final Logger log = LoggerFactory.getLogger(ShareServer.class);
    private static final Listeners listeners = new Listeners();

    private static final List<HttpServer> servers = new ArrayList<>();
    private static volatile Store store;
    private static volatile State state = State.STOPPED;
    private static volatile String error;
    private static volatile int port;
    private static volatile String bind = Network.LOOPBACK;

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

    /** The binding of the last start attempt: 127.0.0.1, one IPv4 address, or 0.0.0.0. */
    public static String bind() {
        return bind;
    }

    /** Whether the last start listened beyond this PC. */
    public static boolean lan() {
        return Network.isLan(bind);
    }

    /** Runs {@code r} (on the thread that changed it) whenever the state changes. */
    public static Listeners.Subscription onChange(Runnable r) {
        return listeners.add(r);
    }

    /** The URL of the selected profiles' merged hosts on this PC (127.0.0.1). */
    public static String activeUrl(int port) {
        return activeUrl(Network.LOOPBACK, port);
    }

    /** The same URL on {@code host}, one of the addresses the web service answers on. */
    public static String activeUrl(String host, int port) {
        return "http://" + host + ":" + port + ACTIVE_PATH;
    }

    /** The URL a profile is shared at on this PC (127.0.0.1). */
    public static String url(int port, String token, String name) {
        return url(Network.LOOPBACK, port, token, name);
    }

    /** The URL a profile is shared at on {@code host}: its {@link Store#shareToken}, then its name as {@link Store#shareSlug}. */
    public static String url(String host, int port, String token, String name) {
        return "http://" + host + ":" + port + PATH + token + "/" + Store.shareSlug(name);
    }

    /**
     * The hosts the URLs may name: 127.0.0.1, and while the web service is open to the network every
     * address of this PC it answers on.
     */
    public static List<String> urlHosts() {
        List<String> out = new ArrayList<>();
        out.add(Network.LOOPBACK);
        if (lan()) for (Network.Lan l : Network.addressesFor(bind)) out.add(l.ip());
        return out;
    }

    /** Starts on the port and binding stored in the settings. */
    public static synchronized void start(Store s) {
        store = s;
        int p;
        String b;
        synchronized (s) {
            p = s.data.settings.web.port;
            b = s.data.settings.web.bind;
        }
        port = p;
        bind = b;
        try {
            if (Network.ALL.equals(b)) {
                servers.add(listen(new InetSocketAddress(p)));
            } else {
                servers.add(listen(new InetSocketAddress(InetAddress.getLoopbackAddress(), p)));
                // An address no longer on this PC fails here and shows as FAILED.
                if (Network.isLan(b)) servers.add(listen(new InetSocketAddress(InetAddress.getByName(b), p)));
            }
            set(State.RUNNING, null);
            log.info("web service on {}:{}", b, p);
        } catch (IOException | RuntimeException e) {
            stopAll();
            log.warn("web service failed to start on {}:{}: {}", b, p, e.getMessage());
            set(State.FAILED, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static HttpServer listen(InetSocketAddress address) throws IOException {
        HttpServer srv = HttpServer.create(address, 0);
        srv.createContext(PATH, ShareServer::handle);
        srv.createContext(ACTIVE_PATH, ShareServer::handleActive);
        srv.createContext("/", ShareServer::handlePage);
        srv.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        srv.start();
        return srv;
    }

    /** Stops and starts again on the port and binding now stored in the settings (after they were saved). */
    public static synchronized void restart() {
        stop();
        if (store != null) start(store);
    }

    public static synchronized void stop() {
        if (servers.isEmpty()) return;
        stopAll();
        set(State.STOPPED, null);
    }

    private static void stopAll() {
        for (HttpServer srv : servers) srv.stop(0);
        servers.clear();
    }

    private static void set(State s, String err) {
        state = s;
        error = err;
        listeners.fire();
    }

    /**
     * Refuses what the server never answers: a foreign Host header (403) or a method other than GET
     * and HEAD (405). Returns whether the request may go on.
     */
    private static boolean admit(HttpExchange ex) throws IOException {
        if (!allowedHost(ex.getRequestHeaders().getFirst("Host"))) {
            respond(ex, 403, "forbidden\n");
            return false;
        }
        String method = ex.getRequestMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            ex.getResponseHeaders().set("Allow", "GET, HEAD");
            respond(ex, 405, "method not allowed\n");
            return false;
        }
        return true;
    }

    private static void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!admit(ex)) return;
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

    /**
     * The profiles selected in oeHosts, merged in tree order as {@code IP host} lines (the first
     * profile that lists a host wins, as in the forward proxy). Follows the selection as it changes.
     */
    private static void handleActive(HttpExchange ex) throws IOException {
        try (ex) {
            if (!admit(ex)) return;
            // The context matches by prefix: only the exact path is this endpoint.
            if (!ACTIVE_PATH.equals(ex.getRequestURI().getPath())) {
                respond(ex, 404, "not found\n");
                return;
            }
            List<String> contents;
            synchronized (store) {
                contents = store.selectedContents(Store.Kind.HOSTS);
            }
            respond(ex, 200, activeHosts(contents));
        }
    }

    /** {@code IP host} lines for {@code contents} (selected profiles in merge order), placeholders resolved. */
    public static String activeHosts(List<String> contents) {
        StringBuilder b = new StringBuilder();
        for (HostsRules.Rule r : HostsRules.active(contents)) b.append(r.ip()).append(' ').append(r.host()).append('\n');
        return b.toString();
    }

    /** The phone setup page and the CA certificate; any other path is 404. */
    private static void handlePage(HttpExchange ex) throws IOException {
        try (ex) {
            if (!admit(ex)) return;
            PhoneSetup.Reply r = PhoneSetup.respond(ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("Accept-Language"));
            if (r == null) {
                respond(ex, 404, "not found\n");
                return;
            }
            if (r.fileName() != null) ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + r.fileName() + "\"");
            send(ex, r.status(), r.contentType(), r.body());
        }
    }

    /**
     * Host header names this server answers to, with any port: 127.0.0.1, localhost, [::1], or an
     * IPv4 address of this PC (a phone or another PC that reached it over the network).
     */
    static boolean allowedHost(String host) {
        String h = hostName(host);
        return h != null && (loopbackName(h) || Network.isOwnAddress(h));
    }

    private static boolean loopbackName(String h) {
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1");
    }

    /** The host of a Host header in lower case, without the port or IPv6 brackets; null if there is none. */
    private static String hostName(String host) {
        if (host == null) return null;
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(1, end) : null;
        }
        int colon = h.indexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
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
