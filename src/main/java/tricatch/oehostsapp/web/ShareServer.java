package tricatch.oehostsapp.web;

import tricatch.oehostsapp.hosts.HostsRules;
import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.store.Store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pages the proxy service answers itself (requests addressed to it rather than through it; see
 * {@code ForwardProxy}). It serves
 * <ul>
 *   <li>the shared oeHosts profiles as plain hosts text, for hosts managers that subscribe to a URL
 *   (their "Remote" entries): {@code GET /hosts/<id>-<tag>/<name>}, the tag being an HMAC of
 *   the id under a key only this installation has ({@link Store#shareToken}). The trailing name
 *   ({@link Store#shareSlug}) is only there for people and is ignored, so a renamed profile keeps
 *   working under its old URL. Only profiles whose {@link Store.Item#shared} is on are served; a
 *   wrong tag, an unknown id and a profile whose sharing was turned off all answer the same 404, so
 *   ids cannot be probed. oeHub's placeholders are filled in ({@link HostsRules#resolvePlaceholders}).</li>
 *   <li>the selected profiles merged ({@link #ACTIVE_PATH}), for oeHosts-Agent.</li>
 *   <li>the phone setup page and the CA's public certificate ({@link PhoneSetup}).</li>
 * </ul>
 *
 * <p>Independent of the server that carries it: {@link #handle} turns a request into a {@link Reply}.
 * A request whose Host header is neither a loopback name nor one of this PC's own IP addresses is
 * refused, so a web page cannot read the profiles through DNS rebinding.
 */
public final class ShareServer {

    public static final String PATH = "/hosts/";
    /** The merged hosts of the profiles selected in oeHosts, for oeHosts-Agent. */
    public static final String ACTIVE_PATH = "/active-hosts";

    /** An HTTP answer: status, content type, body and extra headers (empty body for HEAD is the carrier's job). */
    public record Reply(int status, String contentType, byte[] body, Map<String, String> headers) {}

    private static volatile Store store;

    private ShareServer() {}

    /** The store the pages read (set once at start). */
    public static void init(Store s) {
        store = s;
    }

    /** The URL of the selected profiles' merged hosts on this PC (127.0.0.1). */
    public static String activeUrl(int port) {
        return activeUrl(Network.LOOPBACK, port);
    }

    /** The same URL on {@code host}, one of the addresses the proxy service answers on. */
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

    /** The hosts the URLs may name: 127.0.0.1, then every IPv4 address of this PC (the service listens on all of them). */
    public static List<String> urlHosts() {
        List<String> out = new ArrayList<>();
        out.add(Network.LOOPBACK);
        for (Network.Lan l : Network.addresses()) out.add(l.ip());
        return out;
    }

    /**
     * The answer to {@code method path} (the path without the query) with the given Host and
     * Accept-Language headers. Only GET and HEAD are answered; a foreign Host is 403, an unknown
     * path 404.
     */
    public static Reply handle(String method, String path, String host, String acceptLanguage) {
        if (!allowedHost(host)) return text(403, "forbidden\n");
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            Reply r = text(405, "method not allowed\n");
            r.headers().put("Allow", "GET, HEAD");
            return r;
        }
        if (path.startsWith(PATH)) return shared(path.substring(PATH.length()));
        if (ACTIVE_PATH.equals(path)) {
            List<String> contents;
            synchronized (store) {
                contents = store.selectedContents(Store.Kind.HOSTS);
            }
            return text(200, activeHosts(contents));
        }
        PhoneSetup.Reply p = PhoneSetup.respond(path, acceptLanguage);
        if (p == null) return text(404, "not found\n");
        Map<String, String> headers = new LinkedHashMap<>();
        if (p.fileName() != null) headers.put("Content-Disposition", "attachment; filename=\"" + p.fileName() + "\"");
        return new Reply(p.status(), p.contentType(), p.body(), headers);
    }

    /** {@code <token>/<name>}: the name is ignored (and may be missing). */
    private static Reply shared(String rest) {
        String token = rest;
        int slash = token.indexOf('/');
        if (slash >= 0) token = token.substring(0, slash);
        String content;
        synchronized (store) {
            content = store.sharedHostsContentByToken(token);
        }
        if (content == null) return text(404, "not found\n");
        return text(200, HostsRules.resolvePlaceholders(content));
    }

    /**
     * {@code IP host} lines for {@code contents} (selected profiles in merge order), placeholders
     * resolved: the first profile that lists a host wins, as in the proxy.
     */
    public static String activeHosts(List<String> contents) {
        StringBuilder b = new StringBuilder();
        for (HostsRules.Rule r : HostsRules.active(contents)) b.append(r.ip()).append(' ').append(r.host()).append('\n');
        return b.toString();
    }

    /**
     * Host header names these pages answer to, with any port: 127.0.0.1, localhost, [::1], or an
     * IPv4 address of this PC (a phone or another PC that reached it over the network).
     */
    public static boolean allowedHost(String host) {
        String h = hostName(host);
        return h != null && (loopbackName(h) || Network.isOwnAddress(h));
    }

    static boolean loopbackName(String h) {
        return h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1");
    }

    /** The host of a Host header in lower case, without the port or IPv6 brackets; null if there is none. */
    public static String hostName(String host) {
        if (host == null) return null;
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(1, end) : null;
        }
        int colon = h.indexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    private static Reply text(int status, String body) {
        return new Reply(status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), new LinkedHashMap<>());
    }
}
