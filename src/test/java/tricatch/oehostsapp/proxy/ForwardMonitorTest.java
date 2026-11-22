package tricatch.oehostsapp.proxy;

import tricatch.oehostsapp.Listeners;
import tricatch.oehostsapp.proxy.ForwardMonitor.Exchange;
import tricatch.oehostsapp.proxy.ForwardMonitor.Failure;
import tricatch.oehostsapp.store.Store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tricatch.oe.proxy.ReverseProxyServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ForwardMonitorTest {

    @TempDir
    Path dir;

    @FunctionalInterface
    private interface Backend {
        void serve(InputStream in, OutputStream out) throws Exception;
    }

    private final List<ServerSocket> servers = new CopyOnWriteArrayList<>();
    /** Every exchange that was reported, once, in the order it first was. */
    private final List<Exchange> seen = new CopyOnWriteArrayList<>();
    private Listeners.Subscription capture;
    private Store store;
    private InetAddress lo;

    @BeforeEach
    void start() throws Exception {
        lo = InetAddress.getByName("127.0.0.1");
        store = Store.open(dir.resolve("data.json"));
        store.data.settings.fwdproxy.port = freePort();
        store.save();
        ForwardProxy.start(store);
        assertEquals(ForwardProxy.State.RUNNING, ForwardProxy.state(), ForwardProxy.error());
        capture = ForwardMonitor.start(x -> {
            synchronized (seen) {
                if (seen.stream().noneMatch(y -> y == x)) seen.add(x);
            }
        });
    }

    @AfterEach
    void stop() throws Exception {
        capture.close();
        ForwardProxy.stop();
        SslProxy.setProxyRunning(null);
        SslProxy.clearRecent();
        ReverseProxyServer.setDefaultRouteResolver(null);
        for (ServerSocket s : servers) s.close();
    }

    @Test
    void plainHttpIsRecordedWholeAndAsTheClientSentIt() throws Exception {
        byte[] gz = gzip("hello gzip");
        int port = serve((in, out) -> {
            for (String head; !(head = head(in)).isEmpty(); ) {
                if (head.startsWith("POST")) {
                    byte[] body = in.readNBytes(5);
                    out.write("HTTP/1.1 201 Created\r\nContent-Length: 5\r\n\r\n".getBytes());
                    out.write(body);
                } else {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Encoding: gzip\r\nSet-Cookie: s=1\r\nContent-Length: "
                            + gz.length + "\r\n\r\n").getBytes());
                    out.write(gz);
                }
                out.flush();
            }
        });
        String base = "http://127.0.0.1:" + port;
        try (Socket c = client()) {
            send(c, "GET " + base + "/a/b?q=1 HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nProxy-Connection: keep-alive\r\nCookie: a=b\r\n\r\n");
            head(c.getInputStream());
            c.getInputStream().readNBytes(gz.length);
            send(c, "POST " + base + "/post HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nContent-Length: 5\r\n\r\nhello");
            head(c.getInputStream());
            c.getInputStream().readNBytes(5);
            await(() -> seen.size() == 2 && seen.get(0).ended() && seen.get(1).ended());

            Exchange get = seen.get(0);
            assertFalse(get.tunnel());
            assertEquals("GET", get.method());
            assertEquals("127.0.0.1", get.host());
            assertEquals(port, get.port());
            assertEquals("/a/b?q=1", get.path());
            assertEquals(base + "/a/b?q=1", get.url());
            assertEquals("127.0.0.1", get.client());
            assertEquals("GET " + base + "/a/b?q=1 HTTP/1.1", get.requestHeaders().get(0));
            assertEquals("keep-alive", ProxyMonitor.header(get.requestHeaders(), "Proxy-Connection"), "before the proxy rewrote it");
            assertEquals("a=b", ProxyMonitor.header(get.requestHeaders(), "Cookie"));
            assertEquals(200, get.status());
            assertEquals("s=1", ProxyMonitor.header(get.responseHeaders(), "Set-Cookie"));
            assertEquals("hello gzip", ProxyMonitor.bodyText(get.responseBody(), get.responseHeaders()));
            assertEquals(gz.length, get.received());
            assertNull(get.failure());

            Exchange post = seen.get(1);
            assertEquals(201, post.status());
            assertArrayEquals("hello".getBytes(), post.requestBody());
            assertArrayEquals("hello".getBytes(), post.responseBody());
        }
    }

    @Test
    void whoeverSeesItEndedSeesTheBody() throws Exception {
        byte[] body = new byte[900_000]; // big enough for the copy at the end to take a moment
        int port = serve((in, out) -> {
            head(in);
            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes());
            out.write(body);
            out.flush();
            in.read();
        });
        for (int i = 0; i < 30; i++) {
            try (Socket c = client()) {
                send(c, "GET http://127.0.0.1:" + port + "/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
                int n = i;
                await(() -> seen.size() > n);
                Exchange x = seen.get(n);
                java.util.concurrent.CompletableFuture<Boolean> atOnce = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    long until = System.nanoTime() + 5_000_000_000L;
                    while (!x.ended() && System.nanoTime() < until) Thread.onSpinWait();
                    return x.ended() && x.responseBody() != null;
                });
                head(c.getInputStream());
                assertEquals(body.length, c.getInputStream().readNBytes(body.length).length);
                assertTrue(atOnce.get(), "round " + i);
            }
        }
    }

    @Test
    void anInterimResponseIsNotTheAnswer() throws Exception {
        int port = serve((in, out) -> {
            head(in);
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes());
            out.flush();
            Thread.sleep(150);
            out.write("HTTP/1.1 202 Accepted\r\nContent-Length: 2\r\n\r\nok".getBytes());
            out.flush();
            in.read();
        });
        try (Socket c = client()) {
            send(c, "GET http://127.0.0.1:" + port + "/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            await(() -> seen.size() == 1 && seen.get(0).ended());
            assertEquals(202, seen.get(0).status());
            assertEquals("ok", new String(seen.get(0).responseBody()));
        }
    }

    @Test
    void aBodyOverTheLimitIsCountedButNotKept() throws Exception {
        byte[] big = new byte[ForwardMonitor.BODY_LIMIT + 10];
        int port = serve((in, out) -> {
            head(in);
            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + big.length + "\r\n\r\n").getBytes());
            out.write(big);
            out.flush();
            in.read();
        });
        try (Socket c = client()) {
            send(c, "GET http://127.0.0.1:" + port + "/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            head(c.getInputStream());
            assertEquals(big.length, c.getInputStream().readNBytes(big.length).length);
            await(() -> seen.size() == 1 && seen.get(0).ended());
            assertNull(seen.get(0).responseBody());
            assertTrue(seen.get(0).responseBodyTooLarge());
            assertEquals(big.length, seen.get(0).received());
        }
    }

    @Test
    void aStreamShowsItsHeadersBeforeItEnds() throws Exception {
        int port = serve((in, out) -> {
            head(in);
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nfirst\r\n".getBytes());
            out.flush();
            in.read(); // open until the client leaves
        });
        try (Socket c = client()) {
            send(c, "GET http://127.0.0.1:" + port + "/events HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
            await(() -> seen.size() == 1 && seen.get(0).status() != null && seen.get(0).received() == 5);
            assertFalse(seen.get(0).ended());
        }
        await(() -> seen.get(0).ended());
        assertEquals("first", new String(seen.get(0).responseBody()), "what arrived before it was cut off");
    }

    @Test
    void aTunnelIsItsTargetItsTimeAndItsByteCounts() throws Exception {
        int port = echo();
        try (Socket c = client()) {
            connect(c, "127.0.0.1:" + port);
            c.getOutputStream().write(new byte[100_000]);
            assertEquals(100_000, c.getInputStream().readNBytes(100_000).length);
            await(() -> seen.size() == 1 && seen.get(0).received() == 100_000);
            Exchange t = seen.get(0);
            assertTrue(t.tunnel());
            assertFalse(t.endsAtReverseProxy());
            assertEquals("CONNECT", t.method());
            assertEquals("127.0.0.1", t.host());
            assertEquals(port, t.port());
            assertEquals(":" + port, t.path());
            assertEquals(100_000, t.sent());
            assertEquals(100_000, t.received(), "without the proxy's own 200");
            assertNull(t.status());
            assertNull(t.responseBody());
            assertFalse(t.ended());
        }
        await(() -> seen.get(0).ended());
    }

    @Test
    void restartingTheServiceEndsTheOpenTunnels() throws Exception {
        int port = echo();
        try (Socket c = client()) {
            connect(c, "127.0.0.1:" + port);
            await(() -> seen.size() == 1);
            ForwardProxy.restart();
            await(() -> seen.get(0).ended());
        }
    }

    @Test
    void aFailedConnectionSaysWhy() throws Exception {
        try (Socket c = client()) {
            send(c, "CONNECT 127.0.0.1:" + freePort() + " HTTP/1.1\r\nHost: x\r\n\r\n");
            assertTrue(head(c.getInputStream()).contains(" 502 "));
        }
        try (Socket c = client()) {
            send(c, "CONNECT does-not-exist.invalid:443 HTTP/1.1\r\nHost: x\r\n\r\n");
            assertTrue(head(c.getInputStream()).contains(" 502 "));
        }
        await(() -> seen.size() == 2 && seen.get(0).ended() && seen.get(1).ended());
        assertEquals(502, seen.get(0).status());
        assertEquals(Failure.CONNECT, seen.get(0).failure());
        assertEquals(502, seen.get(1).status());
        assertEquals(Failure.DNS, seen.get(1).failure());
    }

    @Test
    void whatTheServiceAnswersItselfIsNotTraffic() throws Exception {
        int self = ForwardProxy.port();
        for (String request : List.of("GET /active-hosts HTTP/1.1", "GET http://127.0.0.1:" + self + "/ssl HTTP/1.1",
                "CONNECT 127.0.0.1:" + self + " HTTP/1.1")) {
            try (Socket c = client()) {
                send(c, request + "\r\nHost: 127.0.0.1:" + self + "\r\n\r\n");
                head(c.getInputStream());
            }
        }
        Thread.sleep(300);
        assertTrue(seen.isEmpty());
    }

    @Test
    void aTunnelIntoTheReverseProxyIsReportedOnlyWhenItEnds() throws Exception {
        var https = ReverseProxyServer.getConfig().getHttps();
        int saved = https.getPort();
        try {
            int port = echo();
            https.setPort(port);
            SslProxy.setProxyRunning(() -> true);
            try (Socket c = client()) {
                connect(c, "127.0.0.1:" + port);
                send(c, "abc");
                c.getInputStream().readNBytes(3);
                Thread.sleep(200);
                assertTrue(seen.isEmpty(), "its requests are the reverse proxy's rows");
            }
            await(() -> seen.size() == 1);
            assertTrue(seen.get(0).endsAtReverseProxy());
            assertTrue(seen.get(0).ended());
            assertEquals(3, seen.get(0).sent());
        } finally {
            https.setPort(saved);
        }
    }

    @Test
    void stoppedCaptureReportsAndCollectsNothing() throws Exception {
        int port = echo();
        try (Socket c = client()) {
            connect(c, "127.0.0.1:" + port);
            await(() -> seen.size() == 1);
            capture.close();
            send(c, "abc");
            c.getInputStream().readNBytes(3);
            assertEquals(0, seen.get(0).sent());
            try (Socket later = client()) {
                connect(later, "127.0.0.1:" + port);
            }
        }
        Thread.sleep(300);
        assertEquals(1, seen.size());
    }

    // ── Helpers ────────────────────────────────────────────────

    private int serve(Backend backend) throws Exception {
        ServerSocket s = new ServerSocket(0, 50, lo);
        servers.add(s);
        Thread.ofPlatform().daemon().start(() -> {
            while (!s.isClosed()) {
                try {
                    Socket c = s.accept();
                    Thread.ofVirtual().start(() -> {
                        try (c) {
                            backend.serve(c.getInputStream(), c.getOutputStream());
                        } catch (Exception ignored) {
                            // closed by the test
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        });
        return s.getLocalPort();
    }

    private int echo() throws Exception {
        return serve((in, out) -> {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) >= 0; ) {
                out.write(buf, 0, n);
                out.flush();
            }
        });
    }

    private Socket client() throws Exception {
        Socket c = new Socket(lo, ForwardProxy.port());
        c.setSoTimeout(15000);
        return c;
    }

    private static void send(Socket c, String text) throws IOException {
        c.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));
        c.getOutputStream().flush();
    }

    private static void connect(Socket c, String target) throws IOException {
        send(c, "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n");
        assertTrue(head(c.getInputStream()).contains(" 200"));
    }

    private static String head(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) {
            b.append((char) c);
            if (b.length() >= 4 && b.substring(b.length() - 4).equals("\r\n\r\n")) return b.toString();
        }
        return "";
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(b)) {
            z.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return b.toByteArray();
    }

    private static void await(BooleanSupplier done) throws Exception {
        for (int i = 0; i < 100 && !done.getAsBoolean(); i++) Thread.sleep(100);
        assertTrue(done.getAsBoolean());
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }
}
