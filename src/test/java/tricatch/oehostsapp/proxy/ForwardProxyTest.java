package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.net.Network;
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
    }

    @Test
    void listensOnEveryInterface() throws Exception {
        java.util.List<Network.Lan> lans = Network.addresses();
        org.junit.jupiter.api.Assumptions.assumeFalse(lans.isEmpty(), "no network card address on this machine");
        Store s = Store.open(dir.resolve("data.json"));
        s.data.settings.fwdproxy.port = freePort();
        ForwardProxy.start(s);
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());
        try (Socket local = new Socket("127.0.0.1", ForwardProxy.port())) {
            assertTrue(local.isConnected());
        }
        try (Socket lan = new Socket(lans.get(0).ip(), ForwardProxy.port())) {
            assertTrue(lan.isConnected());
        }
    }

    @Test
    void answersItsOwnPagesAndProxiesTheRest() throws Exception {
        java.util.List<Network.Lan> lans = Network.addresses();
        org.junit.jupiter.api.Assumptions.assumeFalse(lans.isEmpty(), "no network card address on this machine");
        String ip = lans.get(0).ip();
        Store s = Store.open(dir.resolve("data.json"));
        s.data.settings.fwdproxy.port = freePort();
        ForwardProxy.start(s);
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());
        int port = ForwardProxy.port();
        // A phone opening the QR code through this very proxy: this PC's address and this port.
        String url = PhoneSetup.pageUrl(ip, port);
        String page = statusLine(ip, port, "GET " + url + " HTTP/1.1\r\nHost: " + ip + ":" + port + "\r\n");
        assertTrue(page.contains(" 200 "), page);
        // A tool calling the URL directly (path only).
        String direct = statusLine("127.0.0.1", port, "GET " + ShareServer.ACTIVE_PATH + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n");
        assertTrue(direct.contains(" 200 "), direct);
        // A tunnel to the service itself is refused rather than looped.
        String loop = statusLine("127.0.0.1", port, "CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n");
        assertTrue(loop.contains(" 403 "), loop);

        // Which requests are the service's own.
        assertEquals("/ssl", ForwardProxy.target(get("http://" + ip + ":" + port + "/ssl?x=1")).path());
        assertEquals("/active-hosts", ForwardProxy.target(get("/active-hosts")).path());
        assertEquals("/", ForwardProxy.target(get("http://localhost:" + port)).path());
        assertNull(ForwardProxy.target(get("http://" + ip + ":1/ssl")));
        assertNull(ForwardProxy.target(get("http://example.com:" + port + "/ssl")));
        assertNull(ForwardProxy.target(new io.netty.handler.codec.http.DefaultHttpRequest(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1, io.netty.handler.codec.http.HttpMethod.CONNECT, "example.com:443")));
    }

    private static io.netty.handler.codec.http.HttpRequest get(String uri) {
        return new io.netty.handler.codec.http.DefaultHttpRequest(io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpMethod.GET, uri);
    }

    @Test
    void asksOnceAboutAnotherDeviceAndKeepsTheAnswer() throws Exception {
        ClientAccess.clear();
        java.util.List<ClientAccess.Request> asked = new java.util.ArrayList<>();
        ClientAccess.setAsker(asked::add);
        try {
            InetAddress phone = InetAddress.getByAddress(new byte[]{(byte) 203, 0, 113, 7});
            // This PC itself is never asked.
            assertEquals(ClientAccess.Decision.ALLOWED, ClientAccess.check(InetAddress.getLoopbackAddress(), "x"));
            for (Network.Lan l : Network.addresses()) {
                assertEquals(ClientAccess.Decision.ALLOWED, ClientAccess.check(InetAddress.getByName(l.ip()), "x"), l.ip());
            }
            assertTrue(asked.isEmpty());

            // Another device: asked once, refused while waiting.
            assertEquals(ClientAccess.Decision.PENDING, ClientAccess.check(phone, "Phone UA"));
            assertEquals(ClientAccess.Decision.PENDING, ClientAccess.check(phone, "Phone UA"));
            assertEquals(1, asked.size());
            assertEquals(new ClientAccess.Request("203.0.113.7", "Phone UA"), asked.get(0));

            ClientAccess.decide(asked.get(0), true);
            assertEquals(ClientAccess.Decision.ALLOWED, ClientAccess.check(phone, "Phone UA"));
            assertEquals(java.util.List.of(new ClientAccess.Entry("203.0.113.7", "Phone UA", true)), ClientAccess.decisions());

            // Cleared: asked again; a refusal holds.
            ClientAccess.clear();
            assertEquals(ClientAccess.Decision.PENDING, ClientAccess.check(phone, null));
            assertEquals(2, asked.size());
            ClientAccess.decide(asked.get(1), false);
            assertEquals(ClientAccess.Decision.DENIED, ClientAccess.check(phone, null));
            assertEquals(2, asked.size());
        } finally {
            ClientAccess.setAsker(null);
            ClientAccess.clear();
        }
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
