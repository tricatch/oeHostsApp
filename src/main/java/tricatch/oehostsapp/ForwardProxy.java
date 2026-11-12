package tricatch.oehostsapp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The forward proxy Chrome uses when the launcher's {@code --proxy-server} is on: LittleProxy on
 * {@code <settings.fwdproxyBind>:<settings.fwdproxyPort>}, where the address is 127.0.0.1 (the default),
 * one network card's IPv4 address or 0.0.0.0 (every interface); the last two let a phone on that
 * network use it. A host listed in the selected oeHosts profiles is
 * connected to the IP recorded there (first profile, first line wins, as in
 * {@link HostsRules#mergeActive}); every other host resolves through normal DNS.
 *
 * <p>Optionally asks other devices for a username and password ({@code settings.fwdproxyAuth}, Basic
 * proxy authentication); 127.0.0.1 has a listener of its own that never asks (see {@link #startOn}).
 *
 * <p>Ported from oeHub's {@code tricatch.oe.fwdproxy.ForwardProxyServer} without what a shared,
 * network-facing server needs: no account authentication, no per-user maps, no domain whitelist,
 * no loopback/DNS-rebinding guards and no blocked page. Bound to 127.0.0.1 its only client already
 * reaches everything it could reach through it. Bound to every interface it is an open proxy to
 * anyone on that network, this PC's own loopback services included (which is also what lets a phone
 * reach the embedded reverse proxy through a {@code 127.0.0.1 host} line); the settings screen warns
 * about that and it stays off by default. Requests for this PC's own IP on the web service port go to
 * the web service on 127.0.0.1 (see {@link #webService}), which is how a phone opens the certificate page.
 * The self-loop owner registration (and so oeHub's {@code LittleProxyInternals}
 * reflection) is not needed either: the embedded reverse proxy has a single owner.
 */
public final class ForwardProxy {

    public enum State { STOPPED, RUNNING, FAILED }

    /** Bind address: this PC only. */
    public static final String LOOPBACK = "127.0.0.1";
    /** Bind address: every network interface. */
    public static final String ALL = "0.0.0.0";

    private static final Logger log = LoggerFactory.getLogger(ForwardProxy.class);
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** The running listeners: 127.0.0.1 always, plus the network ones the binding asks for. */
    private static final List<HttpProxyServer> servers = new java.util.ArrayList<>();
    private static Store store;
    private static volatile State state = State.STOPPED;
    private static volatile String error;
    private static volatile int port;
    private static volatile String bind = LOOPBACK;
    /** The username and password other devices must send, or null (no authentication). */
    private static volatile String[] credentials;

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
    public static void onChange(Runnable r) {
        listeners.add(r);
    }

    /** The address of the last start attempt: {@link #LOOPBACK}, one IPv4 address, or {@link #ALL}. */
    public static String bind() {
        return bind;
    }

    /** Whether the last start asks other devices for a username and password (Basic authentication). */
    public static boolean authRequired() {
        return credentials != null;
    }

    /** Whether the last start listened beyond this PC (a network card or every interface). */
    public static boolean lan() {
        return !LOOPBACK.equals(bind);
    }

    /** Starts on the port and binding stored in the settings. */
    public static synchronized void start(Store s) {
        store = s;
        int p;
        String b;
        synchronized (s) {
            p = s.data.settings.fwdproxyPort;
            b = s.data.settings.fwdproxyBindAddress();
            credentials = s.data.settings.fwdproxyCredentials();
        }
        startOn(p, b);
    }

    /** Stops and starts again on the port now stored in the settings (after it was saved). */
    public static synchronized void restart() {
        stop();
        if (store != null) start(store);
    }

    public static synchronized void stop() {
        if (servers.isEmpty()) return;
        abortAll();
        set(State.STOPPED, null);
    }

    private static void abortAll() {
        for (HttpProxyServer srv : servers) {
            try {
                srv.abort();
            } catch (Exception e) {
                log.warn("forward proxy stop: {}", e.getMessage());
            }
        }
        servers.clear();
    }

    /**
     * Starts the listeners for binding {@code b}. 127.0.0.1 always gets one of its own, never asking
     * for credentials: the Chrome launcher uses {@code 127.0.0.1:<port>} whatever the binding is. The
     * network side is the chosen address, or for 0.0.0.0 either one wildcard listener (no
     * authentication) or, with authentication, one listener per current IPv4 address so loopback can
     * stay unasked. Basic authentication is LittleProxy's own: a 407 sent from a filter would crash
     * it (it looks for an upstream connection) and would lose its Proxy-Authenticate header.
     */
    private static void startOn(int p, String b) {
        port = p;
        bind = b;
        String[] creds = credentials;
        try {
            if (ALL.equals(b) && creds == null) {
                servers.add(listen(new InetSocketAddress(p), null));
            } else {
                servers.add(listen(new InetSocketAddress(InetAddress.getLoopbackAddress(), p), null));
                List<String> network = new java.util.ArrayList<>();
                if (ALL.equals(b)) {
                    for (PhoneSetup.Lan l : PhoneSetup.addresses()) network.add(l.ip());
                } else if (!LOOPBACK.equals(b)) {
                    network.add(b);
                }
                // An address no longer on this PC (another network, a new DHCP lease) fails here and shows as FAILED.
                for (String ip : network) servers.add(listen(new InetSocketAddress(InetAddress.getByName(ip), p), creds));
            }
            log.info("forward proxy listening on {}:{}{}", b, p, creds != null ? " (Basic authentication)" : "");
            set(State.RUNNING, null);
        } catch (Exception e) {
            abortAll();
            String msg = rootMessage(e);
            log.warn("forward proxy failed to start on port {}: {}", p, msg);
            set(State.FAILED, msg);
        }
    }

    /** One listener; {@code creds} non-null asks its clients for that username and password. */
    private static HttpProxyServer listen(InetSocketAddress address, String[] creds) {
        org.littleshoot.proxy.HttpProxyServerBootstrap boot = DefaultHttpProxyServer.bootstrap()
                .withName("oehostsapp-fwdproxy")
                .withAddress(address)
                .withAllowLocalOnly(address.getAddress().isLoopbackAddress())
                .withFiltersSource(FILTERS);
        if (creds != null) {
            boot.withProxyAuthenticator(new org.littleshoot.proxy.ProxyAuthenticator() {
                @Override
                public boolean authenticate(String userName, String password) {
                    return credentialsMatch(userName, password, creds[0], creds[1]);
                }

                @Override
                public String getRealm() {
                    return "oeHostsApp";
                }
            });
        }
        return boot.start();
    }

    /** Whether the given username and password are the required ones (both always compared, in constant time). */
    static boolean credentialsMatch(String userName, String password, String user, String pass) {
        boolean userOk = MessageDigest.isEqual(String.valueOf(userName).getBytes(StandardCharsets.UTF_8),
                user.getBytes(StandardCharsets.UTF_8));
        boolean passOk = MessageDigest.isEqual(String.valueOf(password).getBytes(StandardCharsets.UTF_8),
                pass.getBytes(StandardCharsets.UTF_8));
        return userOk & passOk;
    }

    /** Shared by every listener: hosts in the selected oeHosts profiles go to their recorded IPs. */
    private static final HttpFiltersSourceAdapter FILTERS = new HttpFiltersSourceAdapter() {
        @Override
        public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
            return new HttpFiltersAdapter(originalRequest, ctx) {
                @Override
                public InetSocketAddress proxyToServerResolutionStarted(String hostAndPort) {
                    return override(hostAndPort);
                }
            };
        }
    };

    /**
     * The address to connect to for {@code host:port}: the oeHosts IP when the host is in the
     * selected profiles, null (LittleProxy resolves it through DNS) otherwise.
     */
    static InetSocketAddress override(String hostAndPort) {
        HostPort hp = HostPort.parse(hostAndPort, 80);
        InetSocketAddress web = webService(hp);
        if (web != null) return web;
        String ip = hostMap().get(hp.host().toLowerCase(Locale.ROOT));
        if (ip == null) {
            log.debug("forward proxy: {} via DNS", hp.host());
            return null;
        }
        try {
            // Keeps the host name on the address, so TLS (SNI) and the Host header stay as requested.
            InetAddress forced = InetAddress.getByAddress(hp.host(), InetAddress.getByName(ip).getAddress());
            log.info("forward proxy: {} -> {}", hp.host(), ip);
            return new InetSocketAddress(forced, hp.port());
        } catch (UnknownHostException e) {
            log.warn("forward proxy: {} maps to {}, which is not an address", hp.host(), ip);
            return null;
        }
    }

    /**
     * A phone using this proxy opens the web service's certificate page at {@code http://<PC IP>:<web
     * port>/} (the QR code). That address is this PC itself, so it is connected to 127.0.0.1, where the
     * web service always listens; this way the page works whatever the web service's binding is.
     * (A {@code http://127.0.0.1/} URL would not do: phone browsers send loopback addresses to the
     * phone itself, never to the proxy.) Null for any other target.
     */
    static InetSocketAddress webService(HostPort hp) {
        int webPort = ShareServer.port();
        if (webPort <= 0 || hp.port() != webPort || !PhoneSetup.isOwnAddress(hp.host())) return null;
        try {
            InetAddress loopback = InetAddress.getByAddress(hp.host(), InetAddress.getLoopbackAddress().getAddress());
            log.debug("forward proxy: {} -> web service on 127.0.0.1", hp.host());
            return new InetSocketAddress(loopback, hp.port());
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** host (lower case) -> IP of the selected oeHosts profiles, placeholders resolved. */
    static Map<String, String> hostMap() {
        Store s = store;
        if (s == null) return Map.of();
        List<String> contents;
        synchronized (s) {
            contents = s.selectedHostsContents();
        }
        Map<String, String> out = new HashMap<>();
        for (String c : contents) {
            for (HostsRules.Rule r : HostsRules.parse(HostsRules.resolvePlaceholders(c))) {
                out.putIfAbsent(r.host().toLowerCase(Locale.ROOT), r.ip());
            }
        }
        return out;
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
        for (Runnable r : listeners) r.run();
    }
}
