package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.Listeners;
import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.store.Store;

import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.exception.BadGatewayException;
import tricatch.oe.proxy.exception.GatewayTimeoutException;
import tricatch.oe.proxy.spi.ClientHints;
import tricatch.oe.proxy.spi.ErrorPageRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The oe-proxy reverse proxy, embedded in this process the way oeHub embeds it (oeHub's
 * {@code ReverseProxySettings.init} + {@code ReverseProxyServer.startSslPassServer}): it starts
 * with the app and serves the merged YAML of the selected vhosts ({@link Vhosts#served}) on https
 * port 443.
 *
 * <p>Single owner: every request resolves to {@link #OWNER} through {@code setDefaultOwner}. This
 * app never names an identifier header ({@code setIdentifierHeaderName}), so the library reads none
 * and oeHub's oeOID extension header {@code X-OeHub-Oid} is ignored. The routes are read lazily
 * through the {@code VirtualHostsLoader} SPI; {@link #reload} drops the cached table after an edit
 * and the next request reads the store again.
 *
 * <p>Starts and restarts run one after another on a thread of their own, so a slow CA generation
 * never holds up the window and two restarts never overlap.
 */
public final class ProxyRuntime {

    /** The only owner id; the library's standalone mode uses the same value. */
    public static final long OWNER = 0L;

    public enum State { STARTING, RUNNING, FAILED }

    private static final Logger log = LoggerFactory.getLogger(ProxyRuntime.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Listeners listeners = new Listeners();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "oe-proxy-start");
        t.setDaemon(true);
        return t;
    });

    private static volatile State state = State.STARTING;
    private static volatile String error;
    private static volatile Path dataDir;
    private static volatile Store store;
    /** Whether startSslPassServer has succeeded once (later starts go through its own restart). */
    private static volatile boolean started;
    /** The binding of the last (re)start: 127.0.0.1, one IPv4 address, or 0.0.0.0. */
    private static volatile String bind = Network.ALL;

    private ProxyRuntime() {}

    public static State state() {
        return state;
    }

    /** Why the start failed (only meaningful in {@link State#FAILED}). */
    public static String error() {
        return error;
    }

    public static int port() {
        return ReverseProxyServer.HTTPS_PORT;
    }

    /** The binding of the last (re)start: 127.0.0.1, one IPv4 address, or 0.0.0.0. */
    public static String bind() {
        return bind;
    }

    /** {@code r} runs (on the starting thread) whenever the state changes. */
    public static Listeners.Subscription onChange(Runnable r) {
        return listeners.add(r);
    }

    /** Folder of the CA files ({@code <data>/root-ca}); null before {@link #start}. */
    public static Path caDir() {
        Path d = dataDir;
        return d == null ? null : d.resolve("root-ca");
    }

    /** The CA certificate users install in their trust store; null before {@link #start}. */
    public static Path caCert() {
        Path d = caDir();
        return d == null ? null : d.resolve(Ca.CERT_FILE);
    }

    /** Starts the proxy in the background. */
    public static void start(Path dataDir, Store store) {
        ProxyRuntime.dataDir = dataDir;
        ProxyRuntime.store = store;
        worker.execute(ProxyRuntime::run);
    }

    /**
     * Restarts the proxy in the background (after a new CA or binding, or to retry a failed start).
     * A server that once started is restarted in place, which also reloads the CA.
     */
    public static void restart() {
        worker.execute(() -> {
            if (!started) {
                run();
                return;
            }
            set(State.STARTING, null);
            try {
                applyBinding();
                ReverseProxyServer.startSslPassServer();
                set(State.RUNNING, null);
            } catch (Exception e) {
                set(State.FAILED, message(e));
            }
        });
    }

    /** Drops the cached routes; the next request reloads them from the store. */
    public static void reload() {
        log.info("routes changed; reloading on the next request");
        ReverseProxyServer.invalidateVirtualHosts(OWNER);
    }

    private static void run() {
        try {
            Path caDir = caDir();
            Ca.ensure(caDir);
            Path cert = caDir.resolve(Ca.CERT_FILE), key = caDir.resolve(Ca.KEY_FILE);

            // The proxy, Chrome and the backends share this PC, and the vhosts are the user's own:
            // allow external backends (oeHub's example routes one to a staging server) and trust
            // their internal certificates.
            ReverseProxyServer.setInternalOnlyUpstream(false);
            ReverseProxyServer.setTrustInternalCertEnabled(true);
            // The key is written unencrypted (blank password), as oeHub's setup wizard does.
            ReverseProxyServer.setCaFiles(cert, key, "");
            ReverseProxyServer.setDefaultOwner(OWNER);
            ReverseProxyServer.setVirtualHostsLoader((owner, clientIp) -> loadVirtualHosts(store));
            ReverseProxyServer.setErrorPageRenderer(new LoggingErrors(store));

            // SSLPassServer binds on an executor thread and only logs a failure there, so check the
            // port first to be able to show why the proxy is not running.
            String[] addresses = applyBinding();
            log.info("proxy starting on {}:{} (CA {})", String.join(", ", addresses), port(), cert.toAbsolutePath());
            set(State.STARTING, null);
            probePort(port(), addresses);
            ReverseProxyServer.startSslPassServer();
            started = true;
            set(State.RUNNING, null);
        } catch (Exception e) {
            set(State.FAILED, message(e));
        }
    }

    /**
     * Applies the stored binding (settings.reverseProxy.bind) to the library before a (re)start:
     * every interface, 127.0.0.1 only, or one network card's address together with 127.0.0.1 (the
     * hosts profiles' {@code 127.0.0.1 domain} lines reach the proxy there). Returns the addresses
     * to check before starting.
     */
    private static String[] applyBinding() throws IOException {
        String b;
        synchronized (store) {
            b = store.data.settings.reverseProxy.bind;
        }
        bind = b;
        InetAddress lo = InetAddress.getLoopbackAddress();
        if (Network.ALL.equals(b)) {
            ReverseProxyServer.setBindAddresses();
            return new String[]{"0.0.0.0", "::"};
        }
        if (Network.LOOPBACK.equals(b)) {
            ReverseProxyServer.setBindAddresses(lo);
            return new String[]{Network.LOOPBACK};
        }
        ReverseProxyServer.setBindAddresses(lo, InetAddress.getByName(b));
        return new String[]{Network.LOOPBACK, b};
    }

    /** The merged YAML of the selected vhosts, placeholders filled in; null when none is selected. */
    static String loadVirtualHosts(Store store) {
        String yaml = servedYaml(store);
        if (yaml == null) log.info("routes loaded: none (no vhost is switched on in the oeProxy list)");
        else log.info("routes loaded: {}", routeSummary(yaml));
        return yaml;
    }

    private static String servedYaml(Store store) {
        List<String> contents;
        synchronized (store) {
            contents = store.selectedContents(Store.Kind.VHOSTS);
        }
        String yaml = Vhosts.served(contents);
        return yaml.isBlank() ? null : yaml;
    }

    /** "domain -> host path, path; ..." of the merged YAML, for the console log. */
    static String routeSummary(String yaml) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode root = YAML.readTree(yaml);
            for (JsonNode v : root.path("virtual")) {
                List<String> locs = new ArrayList<>();
                for (JsonNode l : v.path("location")) {
                    List<String> paths = new ArrayList<>();
                    for (JsonNode p : l.path("path")) paths.add(p.asText());
                    locs.add(l.path("host").asText() + " " + String.join(",", paths));
                }
                out.add(v.path("domain").asText() + " -> " + String.join(" | ", locs));
            }
        } catch (Exception e) {
            return "(unreadable YAML: " + e.getMessage() + ")";
        }
        return out.size() + " domain(s): " + String.join("; ", out);
    }

    /**
     * Logs every error the proxy answers with, so a 404 or 502 can be traced from the console.
     * Returning null keeps the library's plain pages.
     */
    private record LoggingErrors(Store store) implements ErrorPageRenderer {
        @Override
        public String notFoundVhost(String host, String path, ClientHints hints) {
            String yaml = servedYaml(store);
            log.warn("404 no route: host={} path={}; current routes: {}", host, path,
                    yaml == null ? "none (no vhost is switched on)" : routeSummary(yaml));
            return null;
        }

        @Override
        public String noVhosts(String clientIp, String identifierHeader, boolean ipIdentifierEnabled, ClientHints hints) {
            log.warn("503 no routes for client {}: no vhost is switched on in the oeProxy list", clientIp);
            return null;
        }

        @Override
        public String badGateway(BadGatewayException ex, ClientHints hints) {
            log.warn("502 backend unreachable: {}", ex.getMessage());
            return null;
        }

        @Override
        public String gatewayTimeout(GatewayTimeoutException ex, ClientHints hints) {
            log.warn("504 backend timeout: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Fails when another program already listens on the port. IPv4 and IPv6 are tried one at a
     * time: on macOS an IPv4-only listener (oeHub runs with -Djava.net.preferIPv4Stack=true) and
     * this JVM's dual-stack [::] socket can both bind, and IPv4 connections to 127.0.0.1 then reach
     * the other program while this proxy looks healthy.
     */
    private static void probePort(int port, String[] addresses) throws IOException {
        for (String addr : addresses) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(InetAddress.getByName(addr), port));
            } catch (BindException e) {
                if ("::".equals(addr) && !hasIpv6()) continue;
                throw new IOException("port " + port + " is in use on " + ("::".equals(addr) ? "IPv6" : "IPv4")
                        + " by another program (" + e.getMessage() + ")", e);
            } catch (SocketException e) {
                // the address family is not available on this machine (IPv6 disabled, IPv4-only JVM)
            }
        }
    }

    private static boolean hasIpv6() {
        try {
            return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                    .flatMap(i -> Collections.list(i.getInetAddresses()).stream())
                    .anyMatch(a -> a instanceof Inet6Address);
        } catch (SocketException e) {
            return false;
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static void set(State s, String err) {
        if (s == State.RUNNING) log.info("proxy running on port {}", port());
        else if (s == State.FAILED) log.error("proxy failed to start: {}", err);
        state = s;
        error = err;
        listeners.fire();
    }
}
