package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.Listeners;
import tricatch.oehostsapp.Messages;
import tricatch.oehostsapp.hosts.HostsRules;
import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.store.Store;
import tricatch.oehostsapp.web.PhoneSetup;
import tricatch.oehostsapp.web.ShareServer;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The proxy service: LittleProxy on {@code <settings.fwdproxy.port>} on every network interface.
 * It does two things on that one port:
 * <ul>
 *   <li>proxies: Chrome (the launcher's {@code --proxy-server}) and phones whose Wi-Fi proxy is
 *   this PC. A host listed in the selected oeHosts profiles is connected to the IP recorded there
 *   ({@link HostsRules#active}); every other host resolves through normal DNS.</li>
 *   <li>answers requests addressed to itself with {@link ShareServer}'s pages: the shared profiles,
 *   {@code /active-hosts} and the phone setup page. A request is addressed to itself when it names
 *   a path only ({@code GET /active-hosts}, a hosts manager or oeHosts-Agent calling the URL), or
 *   when it names this PC's own address and this port ({@code GET http://<PC IP>:<port>/ssl}, a phone
 *   opening the QR code through this very proxy).</li>
 * </ul>
 *
 * <p>Who may use it is decided per device, not by a binding or a password: a client on this PC is
 * always let in, any other device is asked about on screen first ({@link ClientAccess}), and its
 * requests are refused (403) until it is allowed.
 *
 * <p>Ported from oeHub's {@code tricatch.oe.fwdproxy.ForwardProxyServer} without what a shared,
 * account-based server needs: no account authentication, no per-user maps, no domain whitelist and
 * no blocked page. An allowed device reaches this PC's own loopback services through it (which is
 * also what lets a phone reach the embedded reverse proxy through a {@code 127.0.0.1 host} line).
 * The self-loop owner registration (and so oeHub's {@code LittleProxyInternals} reflection) is not
 * needed either: the embedded reverse proxy has a single owner.
 */
public final class ForwardProxy {

    public enum State { STOPPED, RUNNING, FAILED }

    private static final Logger log = LoggerFactory.getLogger(ForwardProxy.class);
    private static final Listeners listeners = new Listeners();

    private static HttpProxyServer server;
    private static volatile Store store;
    private static volatile State state = State.STOPPED;
    private static volatile String error;
    private static volatile int port;
    /** {@link #hostMap} as of store revision {@link #mapRevision}. */
    private static volatile Map<String, String> map = Map.of();
    private static volatile long mapRevision = -1;

    private ForwardProxy() {}

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

    /** Runs {@code r} (on the calling thread of the change) whenever the state changes. */
    public static Listeners.Subscription onChange(Runnable r) {
        return listeners.add(r);
    }

    /** Starts on the port stored in the settings. */
    public static synchronized void start(Store s) {
        store = s;
        ShareServer.init(s);
        ClientAccess.attach(s);
        int p;
        synchronized (s) {
            p = s.data.settings.fwdproxy.port;
        }
        port = p;
        try {
            server = DefaultHttpProxyServer.bootstrap()
                    .withName("oehostsapp-fwdproxy")
                    .withAddress(new InetSocketAddress(p))
                    .withAllowLocalOnly(false)
                    .withFiltersSource(FILTERS)
                    .start();
            log.info("proxy service listening on {}:{}", Network.ALL, p);
            set(State.RUNNING, null);
        } catch (Exception e) {
            server = null;
            String msg = rootMessage(e);
            log.warn("proxy service failed to start on port {}: {}", p, msg);
            set(State.FAILED, msg);
        }
    }

    /** Stops and starts again on the port now stored in the settings (after it was saved). */
    public static synchronized void restart() {
        stop();
        if (store != null) start(store);
    }

    public static synchronized void stop() {
        if (server == null) return;
        try {
            server.abort();
        } catch (Exception e) {
            log.warn("proxy service stop: {}", e.getMessage());
        }
        server = null;
        set(State.STOPPED, null);
    }

    /**
     * Per request: refuses devices that are not allowed (yet), answers requests addressed to the
     * service itself, and sends hosts of the selected oeHosts profiles to their recorded IPs.
     */
    private static final HttpFiltersSourceAdapter FILTERS = new HttpFiltersSourceAdapter() {
        @Override
        public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
            SocketAddress remote = ctx == null ? null : ctx.channel().remoteAddress();
            InetAddress client = remote instanceof InetSocketAddress a ? a.getAddress() : null;
            return new HttpFiltersAdapter(originalRequest, ctx) {
                @Override
                public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                    if (!(httpObject instanceof HttpRequest req)) return null;
                    ClientAccess.Decision d = ClientAccess.check(client, req.headers().get(HttpHeaderNames.USER_AGENT));
                    if (d != ClientAccess.Decision.ALLOWED) {
                        log.info("proxy service: {} refused ({})", client == null ? "?" : client.getHostAddress(), d);
                        return refused(req, d);
                    }
                    Target t = target(req);
                    if (t == null) return null;
                    if (req.method() == HttpMethod.CONNECT) return plain(req, 403, "forbidden\n");
                    return serve(req, t.path());
                }

                @Override
                public InetSocketAddress proxyToServerResolutionStarted(String hostAndPort) {
                    return override(hostAndPort);
                }

                @Override
                public HttpObject serverToProxyResponse(HttpObject httpObject) {
                    if (httpObject instanceof HttpResponse res) ensureDate(res);
                    return httpObject;
                }
            };
        }
    };

    /** A request addressed to the service itself, with its path (query dropped). */
    record Target(String path) {}

    /**
     * The service's own page a request asks for, or null when it is to be proxied: a path only
     * (origin form), or an absolute URL / CONNECT target naming this PC and this port.
     */
    static Target target(HttpRequest req) {
        String uri = req.uri();
        if (uri.startsWith("/")) return new Target(pathOf(uri));
        String hostPort;
        String path = "/";
        if (req.method() == HttpMethod.CONNECT) {
            hostPort = uri;
        } else {
            int scheme = uri.indexOf("://");
            if (scheme < 0) return null;
            String rest = uri.substring(scheme + 3);
            int slash = rest.indexOf('/');
            hostPort = slash < 0 ? rest : rest.substring(0, slash);
            if (slash >= 0) path = pathOf(rest.substring(slash));
        }
        HostPort hp = HostPort.parse(hostPort, req.method() == HttpMethod.CONNECT ? 443 : 80);
        String h = hp.host().toLowerCase(Locale.ROOT);
        boolean self = h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1") || Network.isOwnAddress(h);
        return self && hp.port() == port ? new Target(path) : null;
    }

    private static String pathOf(String uri) {
        int q = uri.indexOf('?');
        return q < 0 ? uri : uri.substring(0, q);
    }

    /** {@link ShareServer}'s answer for {@code path}. */
    private static HttpResponse serve(HttpRequest req, String path) {
        ShareServer.Reply r = ShareServer.handle(req.method().name(), path, req.headers().get(HttpHeaderNames.HOST),
                req.headers().get(HttpHeaderNames.ACCEPT_LANGUAGE));
        FullHttpResponse res = response(req, r.status(), r.contentType(), r.body());
        r.headers().forEach((k, v) -> res.headers().set(k, v));
        return res;
    }

    /** The 403 for a device that is not allowed: waiting for the answer on this PC, or refused there. */
    private static HttpResponse refused(HttpRequest req, ClientAccess.Decision d) {
        Map<String, String> m = Messages.of(PhoneSetup.lang(req.headers().get(HttpHeaderNames.ACCEPT_LANGUAGE)));
        String key = d == ClientAccess.Decision.DENIED ? "app.access.page.denied" : "app.access.page.pending";
        return plain(req, 403, m.getOrDefault(key, key) + "\n");
    }

    private static HttpResponse plain(HttpRequest req, int status, String body) {
        return response(req, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static FullHttpResponse response(HttpRequest req, int status, String type, byte[] body) {
        boolean head = req.method() == HttpMethod.HEAD;
        FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status),
                head ? Unpooled.EMPTY_BUFFER : Unpooled.wrappedBuffer(body));
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, type);
        res.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        res.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        res.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ensureDate(res);
        return res;
    }

    /**
     * Sets an RFC 1123 Date when the response has none. LittleProxy 2.9.1 fills a missing Date with a
     * formatter in the JVM's default locale, so on a Korean system the day and month went out as
     * Korean text that the header encoding turned into "?" ("?, 14 11? 2026 ..."). Netty's
     * DateFormatter is locale-independent.
     */
    static void ensureDate(HttpResponse res) {
        if (!res.headers().contains(HttpHeaderNames.DATE)) {
            res.headers().set(HttpHeaderNames.DATE, DateFormatter.format(new java.util.Date()));
        }
    }

    /**
     * The address to connect to for {@code host:port}: the oeHosts IP when the host is in the
     * selected profiles, null (LittleProxy resolves it through DNS) otherwise.
     */
    static InetSocketAddress override(String hostAndPort) {
        HostPort hp = HostPort.parse(hostAndPort, 80);
        String ip = hostMap().get(hp.host().toLowerCase(Locale.ROOT));
        if (ip == null) {
            log.debug("proxy service: {} via DNS", hp.host());
            return null;
        }
        try {
            // Keeps the host name on the address, so TLS (SNI) and the Host header stay as requested.
            InetAddress forced = InetAddress.getByAddress(hp.host(), InetAddress.getByName(ip).getAddress());
            log.info("proxy service: {} -> {}", hp.host(), ip);
            return new InetSocketAddress(forced, hp.port());
        } catch (UnknownHostException e) {
            log.warn("proxy service: {} maps to {}, which is not an address", hp.host(), ip);
            return null;
        }
    }

    /**
     * host (lower case) -> IP of the selected oeHosts profiles. Rebuilt only after the store
     * changed, not for every request.
     */
    static Map<String, String> hostMap() {
        Store s = store;
        if (s == null) return Map.of();
        if (s.revision() == mapRevision) return map;
        long rev;
        List<String> contents;
        synchronized (s) {
            rev = s.revision();
            contents = s.selectedContents(Store.Kind.HOSTS);
        }
        Map<String, String> out = new HashMap<>();
        for (HostsRules.Rule r : HostsRules.active(contents)) out.put(r.host().toLowerCase(Locale.ROOT), r.ip());
        map = Map.copyOf(out);
        mapRevision = rev;
        return map;
    }

    /** "host:port", "[v6]:port", "[v6]" or a bare host / v6 literal. */
    record HostPort(String host, int port) {
        static HostPort parse(String s, int defaultPort) {
            if (s.startsWith("[")) {
                int close = s.indexOf(']');
                if (close < 0) return new HostPort(s, defaultPort);
                String rest = s.substring(close + 1);
                return new HostPort(s.substring(1, close), rest.startsWith(":") ? portOr(rest.substring(1), defaultPort) : defaultPort);
            }
            int i = s.lastIndexOf(':');
            if (i < 0 || i != s.indexOf(':')) return new HostPort(s, defaultPort);
            return new HostPort(s.substring(0, i), portOr(s.substring(i + 1), defaultPort));
        }

        private static int portOr(String p, int d) {
            try {
                return Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return d;
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    private static void set(State s, String err) {
        state = s;
        error = err;
        listeners.fire();
    }
}
