package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.store.Settings;
import tricatch.oehostsapp.store.Store;
import tricatch.oehostsapp.web.PhoneSetup;
import tricatch.oehostsapp.web.ShareServer;

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

        Store s = Store.open(dir.resolve("data.json"));
        // Second profile maps the same host elsewhere: the first selected one wins.
        Store.Item a = s.create(Store.Kind.HOSTS, "en");
        s.updateContent(Store.Kind.HOSTS, a.id, "${PROXY_SVR} mapped.oehostsapp.test\n");
        s.toggleSelected(Store.Kind.HOSTS, a.id);
        Store.Item b = s.create(Store.Kind.HOSTS, "en");
        s.updateContent(Store.Kind.HOSTS, b.id, "10.255.255.1 mapped.oehostsapp.test\n");
        s.toggleSelected(Store.Kind.HOSTS, b.id);
        s.data.settings.fwdproxy.port = freePort();

        ForwardProxy.start(s);
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());

        // Plain HTTP through the proxy.
        HttpClient client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", ForwardProxy.port())))
                .build();
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                URI.create("http://mapped.oehostsapp.test:" + backendPort + "/")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("host=mapped.oehostsapp.test:" + backendPort, r.body());

        // A CONNECT tunnel (what Chrome uses for https) to the same mapped host.
        try (Socket sock = new Socket("127.0.0.1", ForwardProxy.port())) {
            sock.setSoTimeout(5000);
            OutputStream out = sock.getOutputStream();
            String target = "mapped.oehostsapp.test:" + backendPort;
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
        s.data.settings.fwdproxy.port = freePort();
        ForwardProxy.restart();
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state());
        try (ServerSocket again = new ServerSocket(old, 1, InetAddress.getLoopbackAddress())) {
            assertTrue(again.isBound());
        }

        // Every interface: still reachable on 127.0.0.1, and mapped the same way.
        s.data.settings.fwdproxy.bind = Network.ALL;
        ForwardProxy.restart();
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());
        assertTrue(ForwardProxy.lan());
        client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", ForwardProxy.port())))
                .build();
        r = client.send(HttpRequest.newBuilder(
                URI.create("http://mapped.oehostsapp.test:" + backendPort + "/")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("host=mapped.oehostsapp.test:" + backendPort, r.body());
    }

    @Test
    void bindsTheChosenAddress() throws Exception {
        Store s = Store.open(dir.resolve("data.json"));
        // This PC only by default.
        assertEquals(Network.LOOPBACK, s.data.settings.fwdproxy.bind);
        s.data.settings.fwdproxy.port = freePort();

        // One network card: listening on that address (and 127.0.0.1).
        java.util.List<Network.Lan> lans = Network.addresses();
        org.junit.jupiter.api.Assumptions.assumeFalse(lans.isEmpty(), "no network card address on this machine");
        String ip = lans.get(0).ip();
        s.data.settings.fwdproxy.bind = ip;
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
        s.data.settings.fwdproxy.bind = "203.0.113.7";
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
        String target = "auth.oehostsapp.test:" + backend.getAddress().getPort();

        Store s = Store.open(dir.resolve("data.json"));
        Store.Item a = s.create(Store.Kind.HOSTS, "en");
        s.updateContent(Store.Kind.HOSTS, a.id, "127.0.0.1 auth.oehostsapp.test\n");
        s.toggleSelected(Store.Kind.HOSTS, a.id);
        s.data.settings.fwdproxy.port = freePort();
        s.data.settings.fwdproxy.auth = true;
        s.data.settings.fwdproxy.user = "dev";
        s.data.settings.fwdproxy.password = "p:ss word";
        // Authentication only counts for other devices: connect through a network card address,
        // so the proxy sees a non-loopback client.
        java.util.List<Network.Lan> lans = Network.addresses();
        org.junit.jupiter.api.Assumptions.assumeFalse(lans.isEmpty(), "no network card address on this machine");
        String ip = lans.get(0).ip();
        s.data.settings.fwdproxy.bind = Network.ALL;
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
        s.data.settings.fwdproxy.bind = ip;
        ForwardProxy.restart();
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());
        assertTrue(statusLine(ip, port, "GET http://" + target + "/ HTTP/1.1\r\nHost: " + target + "\r\n").contains(" 407 "));
        assertTrue(statusLine("127.0.0.1", port, "GET http://" + target + "/ HTTP/1.1\r\nHost: " + target + "\r\n").contains(" 200 "));
    }

    @Test
    void sendsThisPcsAddressOnTheWebServicePortToTheWebService() throws Exception {
        java.util.List<Network.Lan> lans = Network.addresses();
        org.junit.jupiter.api.Assumptions.assumeFalse(lans.isEmpty(), "no network card address on this machine");
        String ip = lans.get(0).ip();
        Store s = Store.open(dir.resolve("data.json"));
        // The web service on this PC only; the proxy open to the network, as for a phone.
        s.data.settings.web.port = freePort();
        ShareServer.start(s);
        s.data.settings.fwdproxy.port = freePort();
        s.data.settings.fwdproxy.bind = ip;
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
        Settings.ForwardProxy st = new Settings.ForwardProxy();
        st.auth = true;
        st.user = "dev";
        assertNull(st.credentials());
        st.password = "x";
        assertArrayEquals(new String[]{"dev", "x"}, st.credentials());
        st.auth = false;
        assertNull(st.credentials());
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
    void reportsAPortInUse() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Store s = Store.open(dir.resolve("data.json"));
            s.data.settings.fwdproxy.port = taken.getLocalPort();
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
