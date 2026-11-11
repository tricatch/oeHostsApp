package tricatch.oehubapp;

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

class ShareServerTest {

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() {
        ShareServer.stop();
    }

    @Test
    void servesOnlySharedProfilesWithPlaceholdersFilledIn() throws Exception {
        Store s = Store.open(dir.resolve("data.json"), null);
        Store.Item a = s.create(Store.Kind.HOSTS, "en");
        s.updateContent(Store.Kind.HOSTS, a.id, "# dev\n${PROXY_SVR} api.example.test\n");
        Store.Item b = s.create(Store.Kind.HOSTS, "en");
        s.setShared(a.id, true);
        s.data.settings.shareServerPort = freePort();

        ShareServer.start(s);
        assertEquals(ShareServer.State.RUNNING, ShareServer.state(), ShareServer.error());
        int port = ShareServer.port();
        String ta = s.shareToken(a.id);
        assertTrue(ta.matches("[0-9a-hjkmnp-tv-z]{14}-[0-9a-hjkmnp-tv-z]{8}"), ta);
        assertEquals(ta, s.shareToken(a.id));

        // The URL the dialog copies: token, then the name, which is ignored.
        String url = ShareServer.url(port, ta, "dev api");
        assertEquals("http://127.0.0.1:" + port + "/hosts/" + ta + "/dev_api", url);
        assertEquals(200, get(url).statusCode());
        assertEquals(200, get(bare(port, ta + "/renamed-later")).statusCode());
        assertEquals(200, get(ShareServer.url(port, ta, "개발 서버")).statusCode());

        HttpResponse<String> r = get(bare(port, ta));
        assertEquals(200, r.statusCode());
        assertEquals("# dev\n127.0.0.1 api.example.test\n", r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));

        // The bare id, a wrong or truncated tag, another profile's tag: all 404.
        assertEquals(404, get(bare(port, a.id)).statusCode());
        String tag = ta.substring(ta.lastIndexOf('-') + 1);
        String wrong = (tag.charAt(0) == '0' ? "1" : "0") + tag.substring(1);
        assertEquals(404, get(bare(port, ta.substring(0, ta.indexOf('-')) + "-" + wrong)).statusCode());
        assertEquals(404, get(bare(port, ta.substring(0, ta.length() - 1))).statusCode());
        String tb = s.shareToken(b.id);
        assertEquals(404, get(bare(port, ta.substring(0, ta.indexOf('-')) + tb.substring(tb.indexOf('-')))).statusCode());
        // Not shared (valid token), unknown, outside /hosts/.
        assertEquals(404, get(bare(port, s.shareToken(b.id))).statusCode());
        assertEquals(404, get(bare(port, "nope")).statusCode());
        assertEquals(404, get("http://127.0.0.1:" + port + "/").statusCode());

        // Turning sharing off takes effect on the next request; edits show up likewise.
        s.updateContent(Store.Kind.HOSTS, a.id, "10.0.0.1 api.example.test\n");
        assertEquals("10.0.0.1 api.example.test\n", get(bare(port, ta)).body());
        s.setShared(a.id, false);
        assertEquals(404, get(bare(port, ta)).statusCode());

        // Survives a reload of the data file, with the same token.
        s.setShared(a.id, true);
        assertNotNull(Store.open(dir.resolve("data.json"), null).sharedHostsContentByToken(ta));
        // The key is not part of a backup, and another installation (another key) refuses the token.
        assertFalse(s.exportParts(true, true, true).toString().contains(s.data.shareKey));
        Store other = Store.open(dir.resolve("other.json"), null);
        other.restoreParts(s.exportParts(true, false, false), true, false, false);
        other.shareToken(a.id);
        assertNull(other.sharedHostsContentByToken(ta));

        // A non-loopback Host header (DNS rebinding) is refused.
        assertTrue(raw(port, "GET /hosts/" + ta + " HTTP/1.1\r\nHost: evil.example:" + port
                + "\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 403"));
        assertTrue(raw(port, "GET /hosts/" + ta + " HTTP/1.1\r\nHost: localhost:" + port
                + "\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 200"));
        assertTrue(raw(port, "POST /hosts/" + ta + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 405"));
    }

    @Test
    void slugs() {
        assertEquals("dev_api", Store.shareSlug("dev api"));
        assertEquals("a_b_c.d-e", Store.shareSlug("  a / b?#c.d-e "));
        assertEquals("개발_서버", Store.shareSlug("개발 서버"));
        assertEquals("_", Store.shareSlug("   "));
        assertEquals("_", Store.shareSlug("%%%"));

    }

    @Test
    void loopbackHostNames() {
        assertTrue(ShareServer.loopbackHost("127.0.0.1:36981"));
        assertTrue(ShareServer.loopbackHost("LOCALHOST"));
        assertTrue(ShareServer.loopbackHost("[::1]:36981"));
        assertFalse(ShareServer.loopbackHost("127.0.0.1.evil.example"));
        assertFalse(ShareServer.loopbackHost("[::2]:1"));
        assertFalse(ShareServer.loopbackHost(null));
    }

    @Test
    void reportsAPortInUse() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Store s = Store.open(dir.resolve("data.json"), null);
            s.data.settings.shareServerPort = taken.getLocalPort();
            ShareServer.start(s);
            assertEquals(ShareServer.State.FAILED, ShareServer.state());
            assertNotNull(ShareServer.error());
        }
    }

    /** A share URL without the trailing name. */
    private static String bare(int port, String token) {
        return "http://127.0.0.1:" + port + "/hosts/" + token;
    }

    private static HttpResponse<String> get(String url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String raw(int port, String request) throws Exception {
        try (Socket so = new Socket(InetAddress.getLoopbackAddress(), port)) {
            OutputStream o = so.getOutputStream();
            o.write(request.getBytes(StandardCharsets.US_ASCII));
            o.flush();
            InputStream in = so.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return ss.getLocalPort();
        }
    }
}
