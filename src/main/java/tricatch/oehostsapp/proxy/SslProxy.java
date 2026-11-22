package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.Listeners;
import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.store.Store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.spi.DefaultRoute;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The SSL proxy: shows the HTTPS of the selected domains ({@link SslRules}) on the Network tab.
 *
 * <pre>
 * not selected: client -CONNECT-> proxy service -tunnel-> origin
 * selected:     client -CONNECT-> proxy service -tunnel-> 127.0.0.1:443 oe-proxy -TLS-> origin
 * </pre>
 *
 * The proxy service only changes where a tunnel ends ({@link #connect}); oe-proxy ends the TLS with
 * a certificate of the app's CA and, for a domain without a vhost, asks {@link #route} where to go.
 * Both answer from the same stored list. A vhost of the domain wins over the hosts profiles.
 *
 * <p>It also keeps the domains that lately went through the proxy service (in memory only), which
 * the settings page offers for selection: a tunnel leaves no other trace.
 */
public final class SslProxy {

    /** How long a selected domain's server may stay silent (server-sent events, long polling). */
    static final int READ_TIMEOUT_MS = 300_000;
    static final int MAX_RECENT = 100;
    private static final int HTTPS = 443;

    /** A domain that was connected to on port 443 and whether that last tunnel was decrypted. */
    public record Recent(String host, boolean decrypted) {}

    private static final Logger log = LoggerFactory.getLogger(SslProxy.class);
    private static final Listeners recentListeners = new Listeners();
    /** Domain -> decrypted, the latest last. */
    private static final Map<String, Boolean> recent = new LinkedHashMap<>();

    private static volatile Store store;
    /** {@link #effective} as of store revision {@link #rulesRevision}. */
    private static volatile SslRules rules = SslRules.NONE;
    private static volatile long rulesRevision = -1;
    private static volatile BooleanSupplier proxyRunning = SslProxy::reverseProxyRunning;

    private SslProxy() {}

    private static boolean reverseProxyRunning() {
        return ProxyRuntime.state() == ProxyRuntime.State.RUNNING;
    }

    /** Tests: stands in for the reverse proxy's state; null restores the real one. */
    static void setProxyRunning(BooleanSupplier running) {
        proxyRunning = running == null ? SslProxy::reverseProxyRunning : running;
    }

    /** Takes the settings from {@code s}; called whenever the proxy service starts. */
    static void attach(Store s) {
        store = s;
        rulesRevision = -1;
        syncResolver();
    }

    /** The rules in force: the stored list while the switch is on, else {@link SslRules#NONE}. */
    public static SslRules effective() {
        Store s = store;
        if (s == null) return SslRules.NONE;
        if (s.revision() == rulesRevision) return rules;
        long rev;
        boolean on;
        String text;
        synchronized (s) {
            rev = s.revision();
            on = s.data.settings.sslProxy.enabled;
            text = s.data.settings.sslProxy.domains;
        }
        SslRules r = on ? SslRules.parse(text) : SslRules.NONE;
        if (!r.selectsAny()) r = SslRules.NONE;
        rules = r;
        rulesRevision = rev;
        return r;
    }

    /** Whether any domain is being decrypted (the Network tab then has something to show without a vhost). */
    public static boolean active() {
        return effective().selectsAny();
    }

    /**
     * Puts saved settings in force. When the rules differ from {@code before} the proxy service
     * restarts, since a tunnel keeps the end it was opened with; an unchanged list drops nothing.
     * Blocks for the restart: not on the event thread.
     */
    public static void apply(SslRules before) {
        syncResolver();
        SslRules now = effective();
        if (now.equals(before)) return;
        log.info("SSL proxy: {}; restarting the proxy service to close the open tunnels",
                now.selectsAny() ? "domains changed" : "off");
        ForwardProxy.restart();
    }

    // Registered only while something is selected: with a resolver, a loopback request without any
    // vhost gets a 404 instead of the 503 "no vhost is switched on".
    private static void syncResolver() {
        ReverseProxyServer.setDefaultRouteResolver(active() ? SslProxy::route : null);
    }

    /**
     * Where the proxy service connects a CONNECT to {@code hostAndPort} instead: the reverse proxy
     * for a selected domain on port 443 while it runs, or null to leave the tunnel as it is.
     */
    static InetSocketAddress connect(String hostAndPort) {
        ForwardProxy.HostPort target = ForwardProxy.HostPort.parse(hostAndPort, HTTPS);
        if (target.port() != HTTPS) return null;
        String host = SslRules.normalize(target.host());
        if (host == null) return null;
        boolean decrypt = effective().matches(host) && proxyRunning.getAsBoolean();
        remember(host, decrypt);
        return decrypt ? reverseProxy() : null;
    }

    private static InetSocketAddress reverseProxy() {
        try {
            // 127.0.0.1 is bound whatever the reverse proxy's PC address setting is.
            InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            return new InetSocketAddress(loopback, ReverseProxyServer.getConfig().getHttps().getPort());
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e); // four bytes are always an address
        }
    }

    /** Whether a connection to {@code to} lands in the embedded reverse proxy (a decrypted domain, or a vhost's). */
    static boolean endsAtReverseProxy(InetSocketAddress to) {
        InetAddress a = to == null ? null : to.getAddress();
        if (a == null || to.getPort() != ReverseProxyServer.getConfig().getHttps().getPort()) return false;
        if (!a.isLoopbackAddress() && !a.isAnyLocalAddress() && !Network.isOwnAddress(a.getHostAddress())) return false;
        return proxyRunning.getAsBoolean();
    }

    /** oe-proxy's question for a domain without a vhost: the hosts profiles' IP, else DNS. */
    private static DefaultRoute route(String host) {
        if (!effective().matches(host)) return null;
        String ip = ForwardProxy.hostMap().get(host);
        return new DefaultRoute(ip != null ? ip : host, HTTPS, READ_TIMEOUT_MS);
    }

    // ── Recently connected domains ──────────────────────────────

    private static void remember(String host, boolean decrypted) {
        synchronized (recent) {
            recent.remove(host);
            recent.put(host, decrypted);
            if (recent.size() > MAX_RECENT) recent.remove(recent.keySet().iterator().next());
        }
        recentListeners.fire();
    }

    /** The domains connected to lately, the newest first. */
    public static List<Recent> recent() {
        List<Recent> out = new ArrayList<>();
        synchronized (recent) {
            recent.forEach((host, decrypted) -> out.add(new Recent(host, decrypted)));
        }
        Collections.reverse(out);
        return out;
    }

    public static void clearRecent() {
        synchronized (recent) {
            recent.clear();
        }
        recentListeners.fire();
    }

    /** Runs {@code r} (on the proxy's thread) for every connection that is recorded; be quick. */
    public static Listeners.Subscription onRecentChange(Runnable r) {
        return recentListeners.add(r);
    }
}
