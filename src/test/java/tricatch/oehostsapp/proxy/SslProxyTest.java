package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.store.Store;

import io.github.tricatch.gotpache.cert.CertificateKeyPair;
import io.github.tricatch.gotpache.cert.KeyTool;
import io.github.tricatch.gotpache.cert.RootCertificateCreator;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tricatch.oe.proxy.ReverseProxyServer;
import tricatch.oe.proxy.spi.DefaultRoute;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SslProxyTest {

    @TempDir
    Path dir;

    private HttpServer backend;

    @AfterEach
    void tearDown() {
        ForwardProxy.stop();
        SslProxy.setProxyRunning(null);
        SslProxy.clearRecent();
        ReverseProxyServer.setDefaultRouteResolver(null);
        if (backend != null) backend.stop(0);
    }

    private Store store(boolean on, String domains) throws Exception {
        Store s = Store.open(dir.resolve("data.json"));
        s.data.settings.sslProxy.enabled = on;
        s.data.settings.sslProxy.domains = domains;
        s.data.settings.fwdproxy.port = freePort();
        s.save();
        return s;
    }

    @Test
    void onlySelectedDomainsOnPort443GoToTheReverseProxy() throws Exception {
        SslProxy.setProxyRunning(() -> true);
        SslProxy.attach(store(true, "*.example.test\n!auth.example.test"));

        InetSocketAddress to = SslProxy.connect("api.example.test:443");
        assertNotNull(to);
        assertTrue(to.getAddress().isLoopbackAddress());
        assertEquals(ReverseProxyServer.getConfig().getHttps().getPort(), to.getPort());
        for (String target : List.of("auth.example.test:443", "api.example.test:8443", "other.test:443", "10.0.0.1:443", "[::1]:443")) {
            assertNull(SslProxy.connect(target), target);
        }
    }

    @Test
    void switchedOffOrWithoutTheReverseProxy_everythingStaysATunnel() throws Exception {
        SslProxy.setProxyRunning(() -> true);
        SslProxy.attach(store(false, "*"));
        assertNull(SslProxy.connect("api.example.test:443"));
        assertNull(ReverseProxyServer.getDefaultRouteResolver(), "off: the library stays vhost-only (503 without vhosts)");

        SslProxy.setProxyRunning(() -> false);
        SslProxy.attach(store(true, "*"));
        assertNull(SslProxy.connect("api.example.test:443"));
    }

    @Test
    void theResolverFollowsTheSameListAndTheHostsProfiles() throws Exception {
        Store s = store(true, "mapped.example.test\nplain.example.test");
        Store.Item hosts = s.create(Store.Kind.HOSTS, "en");
        s.updateContent(Store.Kind.HOSTS, hosts.id, "10.1.2.3 mapped.example.test\n");
        s.toggleSelected(Store.Kind.HOSTS, hosts.id);
        ForwardProxy.start(s);

        var resolver = ReverseProxyServer.getDefaultRouteResolver();
        assertNotNull(resolver);
        assertEquals(new DefaultRoute("10.1.2.3", 443, SslProxy.READ_TIMEOUT_MS), resolver.resolve("mapped.example.test"));
        assertEquals(new DefaultRoute("plain.example.test", 443, SslProxy.READ_TIMEOUT_MS), resolver.resolve("plain.example.test"));
        assertNull(resolver.resolve("other.example.test"));
    }

    @Test
    void whatWasConnectedIsListedNewestFirst() throws Exception {
        SslProxy.setProxyRunning(() -> true);
        SslProxy.attach(store(true, "a.test"));
        SslProxy.connect("a.test:443");
        SslProxy.connect("b.test:443");
        SslProxy.connect("c.test:8443");
        SslProxy.connect("10.0.0.1:443");
        assertEquals(List.of(new SslProxy.Recent("b.test", false), new SslProxy.Recent("a.test", true)), SslProxy.recent());

        SslProxy.connect("a.test:443");
        assertEquals("a.test", SslProxy.recent().get(0).host());
        for (int i = 0; i < SslProxy.MAX_RECENT + 5; i++) SslProxy.connect("h" + i + ".test:443");
        assertEquals(SslProxy.MAX_RECENT, SslProxy.recent().size());
        SslProxy.clearRecent();
        assertTrue(SslProxy.recent().isEmpty());
    }

    @Test
    void aChangeClosesTheTunnelsThatExist_andAnUnchangedListKeepsThem() throws Exception {
        var https = ReverseProxyServer.getConfig().getHttps();
        int savedPort = https.getPort();
        String host = "app.example.test";
        backend = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        backend.createContext("/", ex -> {
            ex.sendResponseHeaders(200, 2);
            try (var o = ex.getResponseBody()) {
                o.write("ok".getBytes());
            }
        });
        backend.start();
        CertificateKeyPair ca = new RootCertificateCreator().generateRootCertificate("SSL Proxy Test CA");
        KeyTool tool = new KeyTool();
        tool.writeCertificate(ca.getCertificate(), dir.toString(), "ca.cer");
        tool.writePrivateKey(ca.getPrivateKey(), dir.toString(), "ca.key");
        int port = freePort();
        try {
            https.setPort(port);
            InetAddress lo = InetAddress.getByName("127.0.0.1");
            ReverseProxyServer.setBindAddresses(lo);
            ReverseProxyServer.setCaFiles(dir.resolve("ca.cer"), dir.resolve("ca.key"), "");
            ReverseProxyServer.setDefaultOwner(ProxyRuntime.OWNER);
            ReverseProxyServer.setVirtualHosts(ProxyRuntime.OWNER, "virtual:\n  - domain: " + host + "\n    location:\n      - host: http://127.0.0.1:"
                    + backend.getAddress().getPort() + "\n        path: ['/**']\n");
            ReverseProxyServer.startSslPassServer();
            awaitListening(lo, port);

            SslProxy.setProxyRunning(() -> true);
            Store s = store(true, host);
            ForwardProxy.start(s);
            SSLContext trust = trusting(ca);

            try (SSLSocket tunnel = open(trust, host)) {
                // Verified against the app's CA only: this is the decrypting end, not a tunnel.
                assertEquals("ok", get(tunnel, host));

                SslRules same = SslProxy.effective();
                s.data.settings.sslProxy.domains = "# the same list\n" + host;
                s.save();
                SslProxy.apply(same);
                assertEquals("ok", get(tunnel, host), "nothing changed: the tunnel stays");

                SslRules before = SslProxy.effective();
                s.data.settings.sslProxy.enabled = false;
                s.save();
                SslProxy.apply(before);
                assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());
                assertThrows(IOException.class, () -> get(tunnel, host));
            }
            assertNull(ReverseProxyServer.getDefaultRouteResolver());
            assertNull(SslProxy.connect(host + ":443"));
        } finally {
            https.setPort(savedPort);
            ReverseProxyServer.setBindAddresses();
            ReverseProxyServer.setDefaultOwner(null);
            ReverseProxyServer.clearVirtualHosts(ProxyRuntime.OWNER);
        }
    }

    private static SSLContext trusting(CertificateKeyPair ca) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setCertificateEntry("ca", ca.getCertificate());
        TrustManagerFactory f = TrustManagerFactory.getInstance("PKIX");
        f.init(ks);
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(null, f.getTrustManagers(), null);
        return c;
    }

    private static SSLSocket open(SSLContext trust, String host) throws Exception {
        Socket raw = new Socket("127.0.0.1", ForwardProxy.port());
        raw.setSoTimeout(6000);
        raw.getOutputStream().write(("CONNECT " + host + ":443 HTTP/1.1\r\nHost: " + host + ":443\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        assertTrue(head(raw.getInputStream()).contains(" 200"));
        SSLSocket s = (SSLSocket) trust.getSocketFactory().createSocket(raw, host, 443, true);
        s.startHandshake();
        return s;
    }

    private static String get(SSLSocket s, String host) throws IOException {
        s.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        String head = head(s.getInputStream());
        if (!head.contains(" 200 ")) throw new IOException("no answer");
        return new String(s.getInputStream().readNBytes(2), StandardCharsets.US_ASCII);
    }

    private static String head(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) {
            b.append((char) c);
            if (b.length() >= 4 && b.substring(b.length() - 4).equals("\r\n\r\n")) return b.toString();
        }
        throw new IOException("closed");
    }

    private static void awaitListening(InetAddress at, int port) throws Exception {
        for (int i = 0; ; i++) {
            try (Socket s = new Socket(at, port)) {
                return;
            } catch (IOException e) {
                if (i >= 50) throw e;
                Thread.sleep(100);
            }
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return ss.getLocalPort();
        }
    }
}
