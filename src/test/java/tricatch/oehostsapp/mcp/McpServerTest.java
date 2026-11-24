package tricatch.oehostsapp.mcp;

import tricatch.oehostsapp.store.Store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        McpServer.stop();
    }

    private Store startServer() throws Exception {
        Store s = Store.open(dir.resolve("data.json"));
        s.data.settings.mcp.enabled = true;
        s.data.settings.mcp.port = freePort();
        McpServer.start(s);
        assertEquals(McpServer.State.RUNNING, McpServer.state(), McpServer.error());
        return s;
    }

    private HttpResponse<String> post(String token, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(McpServer.url(McpServer.port())))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        for (int i = 0; i < headers.length; i += 2) b.setHeader(headers[i], headers[i + 1]);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void offUnlessSwitchedOn() throws Exception {
        Store s = Store.open(dir.resolve("data.json"));
        McpServer.start(s);
        assertEquals(McpServer.State.OFF, McpServer.state());
    }

    @Test
    void answersOnlyWithTheToken() throws Exception {
        Store s = startServer();
        HttpResponse<String> ok = post(s.data.mcpToken, PING);
        assertEquals(200, ok.statusCode());
        assertTrue(ok.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(ok.body().contains("\"result\""));

        assertEquals(401, post(null, PING).statusCode());
        HttpResponse<String> wrong = post("x" + s.data.mcpToken, PING);
        assertEquals(401, wrong.statusCode());
        assertTrue(wrong.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"));

        // A new token locks the old one out at once.
        String old = s.data.mcpToken;
        synchronized (s) {
            s.newMcpToken();
        }
        assertEquals(401, post(old, PING).statusCode());
        assertEquals(200, post(s.data.mcpToken, PING).statusCode());
    }

    @Test
    void notificationIsAcceptedWithoutBody() throws Exception {
        Store s = startServer();
        HttpResponse<String> r = post(s.data.mcpToken, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, r.statusCode());
        assertEquals("", r.body());
    }

    @Test
    void refusesBrowserPagesAndOtherHosts() throws Exception {
        Store s = startServer();
        assertEquals(403, post(s.data.mcpToken, PING, "Origin", "https://evil.example").statusCode());
        assertEquals(200, post(s.data.mcpToken, PING, "Origin", "http://localhost:3000").statusCode());
        // A name that resolves to 127.0.0.1 (DNS rebinding) is still not a loopback name.
        String raw = raw("POST /mcp HTTP/1.1\r\nHost: rebind.example:" + McpServer.port()
                + "\r\nAuthorization: Bearer " + s.data.mcpToken + "\r\nContent-Type: application/json\r\nContent-Length: "
                + PING.length() + "\r\nConnection: close\r\n\r\n" + PING);
        assertTrue(raw.startsWith("HTTP/1.1 403"), raw);
    }

    @Test
    void refusesOtherMethodsTypesAndVersions() throws Exception {
        Store s = startServer();
        HttpResponse<String> get = client.send(HttpRequest.newBuilder(URI.create(McpServer.url(McpServer.port())))
                .header("Authorization", "Bearer " + s.data.mcpToken).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, get.statusCode());
        assertEquals(415, post(s.data.mcpToken, PING, "Content-Type", "text/plain").statusCode());
        assertEquals(400, post(s.data.mcpToken, PING, "MCP-Protocol-Version", "1999-01-01").statusCode());
        assertEquals(200, post(s.data.mcpToken, PING, "MCP-Protocol-Version", "2025-06-18").statusCode());
        assertEquals(400, post(s.data.mcpToken, "{not json").statusCode());
    }

    @Test
    void headerChecks() {
        assertTrue(McpServer.loopbackHost("127.0.0.1:36981"));
        assertTrue(McpServer.loopbackHost("localhost"));
        assertTrue(McpServer.loopbackHost("[::1]:36981"));
        assertFalse(McpServer.loopbackHost("192.168.0.10:36981"));
        assertFalse(McpServer.loopbackHost(null));
        assertTrue(McpServer.allowedOrigin(null));
        assertTrue(McpServer.allowedOrigin("http://[::1]:8080"));
        assertFalse(McpServer.allowedOrigin("null"));
        assertFalse(McpServer.allowedOrigin("http://127.0.0.1.evil.example"));
        assertTrue(McpServer.authorized("bearer abc", "abc"));
        assertFalse(McpServer.authorized("Basic abc", "abc"));
        assertFalse(McpServer.authorized("Bearer ", "abc"));
    }

    private static String raw(String request) throws Exception {
        try (Socket so = new Socket(InetAddress.getLoopbackAddress(), McpServer.port())) {
            OutputStream out = so.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = so.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }
}
