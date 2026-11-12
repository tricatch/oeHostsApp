package tricatch.oehubapp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ForwardProxyTest {

    @TempDir
    Path dir;

    private HttpServer backend;

    @AfterEach
    void tearDown() {
        ForwardProxy.stop();
        if (backend != null) backend.stop(0);
    }

    @Test
    void parsesHostAndPort() {
        assertEquals(new ForwardProxy.HostPort("a.com", 443), ForwardProxy.HostPort.parse("a.com:443", 80));
        assertEquals(new ForwardProxy.HostPort("a.com", 80), ForwardProxy.HostPort.parse("a.com", 80));
        assertEquals(new ForwardProxy.HostPort("::1", 8080), ForwardProxy.HostPort.parse("[::1]:8080", 80));
        assertEquals(new ForwardProxy.HostPort("::1", 80), ForwardProxy.HostPort.parse("::1", 80));
    }

    @Test
    void routesSelectedHostsToTheirIpOverHttpAndConnect() throws Exception {
        backend = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        backend.createContext("/", ex -> {
            byte[] body = ("host=" + ex.getRequestHeaders().getFirst("Host")).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
        });
        backend.start();
        int backendPort = backend.getAddress().getPort();

        Store s = Store.open(dir.resolve("data.json"), null);
        // Second profile maps the same host elsewhere: the first selected one wins.
        Store.Item a = s.create(Store.Kind.HOSTS, "en");
        s.updateContent(Store.Kind.HOSTS, a.id, "${PROXY_SVR} mapped.oehubapp.test\n");
        s.toggleSelected(Store.Kind.HOSTS, a.id);
        Store.Item b = s.create(Store.Kind.HOSTS, "en");
        s.updateContent(Store.Kind.HOSTS, b.id, "10.255.255.1 mapped.oehubapp.test\n");
        s.toggleSelected(Store.Kind.HOSTS, b.id);
        s.data.settings.fwdproxyPort = freePort();

        ForwardProxy.start(s);
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());

        // Plain HTTP through the proxy.
        HttpClient client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", ForwardProxy.port())))
                .build();
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                URI.create("http://mapped.oehubapp.test:" + backendPort + "/")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("host=mapped.oehubapp.test:" + backendPort, r.body());

        // A CONNECT tunnel (what Chrome uses for https) to the same mapped host.
        try (Socket sock = new Socket("127.0.0.1", ForwardProxy.port())) {
            sock.setSoTimeout(5000);
            OutputStream out = sock.getOutputStream();
            String target = "mapped.oehubapp.test:" + backendPort;
            out.write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
            assertTrue(in.readLine().contains(" 200 "));
            while (!in.readLine().isEmpty()) { /* rest of the CONNECT response */ }
            out.write(("GET / HTTP/1.1\r\nHost: " + target + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertTrue(in.readLine().contains(" 200 "));
            String line;
            String last = null;
            while ((line = in.readLine()) != null) last = line;
            assertEquals("host=" + target, last);
        }

        // Moving to another port: the old one is released.
        int old = ForwardProxy.port();
        s.data.settings.fwdproxyPort = freePort();
        ForwardProxy.restart();
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state());
        try (ServerSocket again = new ServerSocket(old, 1, InetAddress.getLoopbackAddress())) {
            assertTrue(again.isBound());
        }

        // Every interface: still reachable on 127.0.0.1, and mapped the same way.
        s.data.settings.putFwdproxyBind(ForwardProxy.ALL);
        ForwardProxy.restart();
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());
        assertTrue(ForwardProxy.lan());
        client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", ForwardProxy.port())))
                .build();
        r = client.send(HttpRequest.newBuilder(
                URI.create("http://mapped.oehubapp.test:" + backendPort + "/")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("host=mapped.oehubapp.test:" + backendPort, r.body());
    }

    @Test
    void bindsTheChosenAddress() throws Exception {
        Store s = Store.open(dir.resolve("data.json"), null);
        // Older data: the flag alone decides.
        assertEquals(ForwardProxy.LOOPBACK, s.data.settings.fwdproxyBindAddress());
        s.data.settings.fwdproxyLan = true;
        assertEquals(ForwardProxy.ALL, s.data.settings.fwdproxyBindAddress());
        // The address wins, and the flag follows it for older versions.
        s.data.settings.putFwdproxyBind(ForwardProxy.LOOPBACK);
        assertFalse(s.data.settings.fwdproxyLan);
        s.data.settings.fwdproxyPort = freePort();

        // One network card: listening on that address (and 127.0.0.1).
        java.util.List<PhoneSetup.Lan> lans = PhoneSetup.addresses();
        org.junit.jupiter.api.Assumptions.assumeFalse(lans.isEmpty(), "no network card address on this machine");
        String ip = lans.get(0).ip();
        s.data.settings.putFwdproxyBind(ip);
        assertTrue(s.data.settings.fwdproxyLan);
        ForwardProxy.start(s);
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());
        assertEquals(ip, ForwardProxy.bind());
        assertTrue(ForwardProxy.lan());
        try (Socket ok = new Socket(ip, ForwardProxy.port())) {
            assertTrue(ok.isConnected());
        }
        // 127.0.0.1 keeps a listener of its own: the Chrome launcher always uses it.
        try (Socket local = new Socket("127.0.0.1", ForwardProxy.port())) {
            assertTrue(local.isConnected());
        }

        // An address this PC does not have: reported, not silently moved elsewhere.
        s.data.settings.putFwdproxyBind("203.0.113.7");
        ForwardProxy.restart();
        assertEquals(ForwardProxy.State.FAILED, ForwardProxy.state());
        assertNotNull(ForwardProxy.error());
    }

    @Test
    void asksOtherDevicesForBasicAuthentication() throws Exception {
        backend = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        backend.createContext("/", ex -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
        });
        backend.start();
        String target = "auth.oehubapp.test:" + backend.getAddress().getPort();

        Store s = Store.open(dir.resolve("data.json"), null);
        Store.Item a = s.create(Store.Kind.HOSTS, "en");
        s.updateContent(Store.Kind.HOSTS, a.id, "127.0.0.1 auth.oehubapp.test\n");
        s.toggleSelected(Store.Kind.HOSTS, a.id);
        s.data.settings.fwdproxyPort = freePort();
        s.data.settings.fwdproxyAuth = true;
        s.data.settings.fwdproxyUser = "dev";
        s.data.settings.fwdproxyPassword = "p:ss word";
        // Authentication only counts for other devices: connect through a network card address,
        // so the proxy sees a non-loopback client.
        java.util.List<PhoneSetup.Lan> lans = PhoneSetup.addresses();
        org.junit.jupiter.api.Assumptions.assumeFalse(lans.isEmpty(), "no network card address on this machine");
        String ip = lans.get(0).ip();
        s.data.settings.putFwdproxyBind(ForwardProxy.ALL);
        ForwardProxy.start(s);
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());
        assertTrue(ForwardProxy.authRequired());
        int port = ForwardProxy.port();

        String none = statusLine(ip, port, "GET http://" + target + "/ HTTP/1.1\r\nHost: " + target + "\r\n");
        assertTrue(none.contains(" 407 "), none);
        String wrongPassword = statusLine(ip, port, "GET http://" + target + "/ HTTP/1.1\r\nHost: " + target
                + "\r\nProxy-Authorization: " + basic("dev", "nope") + "\r\n");
        assertTrue(wrongPassword.contains(" 407 "), wrongPassword);
        String wrongId = statusLine(ip, port, "GET http://" + target + "/ HTTP/1.1\r\nHost: " + target
                + "\r\nProxy-Authorization: " + basic("other", "p:ss word") + "\r\n");
        assertTrue(wrongId.contains(" 407 "), wrongId);
        String ok = statusLine(ip, port, "GET http://" + target + "/ HTTP/1.1\r\nHost: " + target
                + "\r\nProxy-Authorization: " + basic("dev", "p:ss word") + "\r\n");
        assertTrue(ok.contains(" 200 "), ok);
        String tunnelDenied = statusLine(ip, port, "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n");
        assertTrue(tunnelDenied.contains(" 407 "), tunnelDenied);
        String tunnel = statusLine(ip, port, "CONNECT " + target + " HTTP/1.1\r\nHost: " + target
                + "\r\nProxy-Authorization: " + basic("dev", "p:ss word") + "\r\n");
        assertTrue(tunnel.contains(" 200 "), tunnel);

        // 127.0.0.1, the Chrome launcher's address, is never asked.
        String local = statusLine("127.0.0.1", port, "GET http://" + target + "/ HTTP/1.1\r\nHost: " + target + "\r\n");
        assertTrue(local.contains(" 200 "), local);

        // One network card plus authentication: the same rules, and 127.0.0.1 still answers.
        s.data.settings.putFwdproxyBind(ip);
        ForwardProxy.restart();
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());
        assertTrue(statusLine(ip, port, "GET http://" + target + "/ HTTP/1.1\r\nHost: " + target + "\r\n").contains(" 407 "));
        assertTrue(statusLine("127.0.0.1", port, "GET http://" + target + "/ HTTP/1.1\r\nHost: " + target + "\r\n").contains(" 200 "));
    }

    @Test
    void sendsThisPcsAddressOnTheWebServicePortToTheWebService() throws Exception {
        java.util.List<PhoneSetup.Lan> lans = PhoneSetup.addresses();
        org.junit.jupiter.api.Assumptions.assumeFalse(lans.isEmpty(), "no network card address on this machine");
        String ip = lans.get(0).ip();
        Store s = Store.open(dir.resolve("data.json"), null);
        // The web service on this PC only; the proxy open to the network, as for a phone.
        s.data.settings.shareServerPort = freePort();
        ShareServer.start(s);
        s.data.settings.fwdproxyPort = freePort();
        s.data.settings.putFwdproxyBind(ip);
        ForwardProxy.start(s);
        try {
            assertEquals(ShareServer.State.RUNNING, ShareServer.state(), ShareServer.error());
            assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());
            String url = PhoneSetup.pageUrl(ip, ShareServer.port());
            // Directly the page is not there (127.0.0.1 only)...
            assertThrows(java.io.IOException.class, () -> HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()));
            // ...but through the proxy, like the phone after its Wi-Fi proxy is set, it is.
            String page = statusLine(ip, ForwardProxy.port(), "GET " + url + " HTTP/1.1\r\nHost: " + ip + ":" + ShareServer.port() + "\r\n");
            assertTrue(page.contains(" 200 "), page);
            // Another port on this PC is not redirected.
            assertNull(ForwardProxy.webService(ForwardProxy.HostPort.parse(ip + ":1", 80)));
            assertNull(ForwardProxy.webService(ForwardProxy.HostPort.parse("example.com:" + ShareServer.port(), 80)));
        } finally {
            ShareServer.stop();
        }
    }

    @Test
    void checksBasicCredentials() {
        assertTrue(ForwardProxy.credentialsMatch("dev", "a:b", "dev", "a:b"));
        assertFalse(ForwardProxy.credentialsMatch("dev", "a", "dev", "a:b"));
        assertFalse(ForwardProxy.credentialsMatch("Dev", "a:b", "dev", "a:b"));
        assertFalse(ForwardProxy.credentialsMatch(null, null, "dev", "a:b"));
        // Turned on without an ID or password: nothing is asked.
        Store.Settings st = new Store.Settings();
        st.fwdproxyAuth = true;
        st.fwdproxyUser = "dev";
        assertNull(st.fwdproxyCredentials());
        st.fwdproxyPassword = "x";
        assertArrayEquals(new String[]{"dev", "x"}, st.fwdproxyCredentials());
        st.fwdproxyAuth = false;
        assertNull(st.fwdproxyCredentials());
    }

    private static String basic(String user, String password) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** Sends one request (headers without the final blank line) and returns the response's status line. */
    private static String statusLine(String host, int port, String head) throws Exception {
        try (Socket sock = new Socket(host, port)) {
            sock.setSoTimeout(5000);
            OutputStream out = sock.getOutputStream();
            out.write((head + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII)).readLine();
        }
    }

    @Test
    void picksThePhonePageLanguage() {
        assertEquals("ko", PhoneSetup.lang("ko-KR,ko;q=0.9,en;q=0.8"));
        assertEquals("en", PhoneSetup.lang("en-US,ko;q=0.5"));
        assertEquals("en", PhoneSetup.lang("ja-JP"));
        assertEquals("en", PhoneSetup.lang(null));
        assertTrue(PhoneSetup.looksVirtual("vEthernet (WSL)"));
        assertFalse(PhoneSetup.looksVirtual("Intel(R) Wi-Fi 6E AX211 160MHz"));
        assertTrue(PhoneSetup.isOwnAddress("127.0.0.1"));
        assertTrue(PhoneSetup.isOwnAddress("localhost"));
        assertFalse(PhoneSetup.isOwnAddress("203.0.113.7"));
        assertFalse(PhoneSetup.isOwnAddress("example.com"));
    }

    @Test
    void reportsAPortInUse() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Store s = Store.open(dir.resolve("data.json"), null);
            s.data.settings.fwdproxyPort = taken.getLocalPort();
            ForwardProxy.start(s);
            assertEquals(ForwardProxy.State.FAILED, ForwardProxy.state());
            assertNotNull(ForwardProxy.error());
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return ss.getLocalPort();
        }
    }
}
