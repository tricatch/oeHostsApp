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
        s.data.settings.fwdproxyLan = true;
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
