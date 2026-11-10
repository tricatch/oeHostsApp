package tricatch.oehubapp;

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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The forward proxy Chrome uses when the launcher's {@code --proxy-server} is on: LittleProxy on
 * {@code 127.0.0.1:<settings.fwdproxyPort>}, or on every interface when {@code settings.fwdproxyLan}
 * is on (so a phone on the same network can use it). A host listed in the selected oeHosts profiles is
 * connected to the IP recorded there (first profile, first line wins, as in
 * {@link HostsRules#mergeActive}); every other host resolves through normal DNS.
 *
 * <p>Ported from oeHub's {@code tricatch.oe.fwdproxy.ForwardProxyServer} without what a shared,
 * network-facing server needs: no account authentication, no per-user maps, no domain whitelist,
 * no loopback/DNS-rebinding guards and no blocked page. Bound to 127.0.0.1 its only client already
 * reaches everything it could reach through it. Bound to every interface it is an open proxy to
 * anyone on the network, this PC's own loopback services included (which is also what lets a phone
 * reach the embedded reverse proxy through a {@code 127.0.0.1 host} line); the settings screen warns
 * about that and it stays off by default. The self-loop owner registration (and so oeHub's {@code LittleProxyInternals}
 * reflection) is not needed either: the embedded reverse proxy has a single owner.
 */
public final class ForwardProxy {

    public enum State { STOPPED, RUNNING, FAILED }

    private static final Logger log = LoggerFactory.getLogger(ForwardProxy.class);
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static HttpProxyServer server;
    private static Store store;
    private static volatile State state = State.STOPPED;
    private static volatile String error;
    private static volatile int port;
    private static volatile boolean lan;

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

    /** Whether the last start bound every network interface rather than 127.0.0.1. */
    public static boolean lan() {
        return lan;
    }

    /** Starts on the port and binding stored in the settings. */
    public static synchronized void start(Store s) {
        store = s;
        int p;
        boolean all;
        synchronized (s) {
            p = s.data.settings.fwdproxyPort;
            all = s.data.settings.fwdproxyLan;
        }
        startOn(p, all);
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
            log.warn("forward proxy stop: {}", e.getMessage());
        }
        server = null;
        set(State.STOPPED, null);
    }

    private static void startOn(int p, boolean all) {
        port = p;
        lan = all;
        try {
            server = DefaultHttpProxyServer.bootstrap()
                    .withName("oehubapp-fwdproxy")
                    .withAddress(all ? new InetSocketAddress(p) : new InetSocketAddress(InetAddress.getLoopbackAddress(), p))
                    .withAllowLocalOnly(!all)
                    .withFiltersSource(new HttpFiltersSourceAdapter() {
                        @Override
                        public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
                            return new HttpFiltersAdapter(originalRequest, ctx) {
                                @Override
                                public InetSocketAddress proxyToServerResolutionStarted(String hostAndPort) {
                                    return override(hostAndPort);
                                }
                            };
                        }
                    })
                    .start();
            log.info("forward proxy listening on {}:{}", all ? "0.0.0.0" : "127.0.0.1", p);
            set(State.RUNNING, null);
        } catch (Exception e) {
            server = null;
            String msg = rootMessage(e);
            log.warn("forward proxy failed to start on port {}: {}", p, msg);
            set(State.FAILED, msg);
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
