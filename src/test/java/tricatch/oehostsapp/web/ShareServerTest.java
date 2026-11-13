package tricatch.oehostsapp.web;

import tricatch.oehostsapp.net.Network;
import tricatch.oehostsapp.Messages;
import tricatch.oehostsapp.proxy.ProxyRuntime;
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

class ShareServerTest {

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() {
        ShareServer.stop();
    }

    @Test
    void servesOnlySharedProfilesWithPlaceholdersFilledIn() throws Exception {
        Store s = Store.open(dir.resolve("data.json"));
        Store.Item a = s.create(Store.Kind.HOSTS, "en");
        s.updateContent(Store.Kind.HOSTS, a.id, "# dev\n${PROXY_SVR} api.example.test\n");
        Store.Item b = s.create(Store.Kind.HOSTS, "en");
        s.setShared(a.id, true);
        s.data.settings.web.port = freePort();

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
        assertEquals(404, get("http://127.0.0.1:" + port + "/elsewhere").statusCode());

        // Turning sharing off takes effect on the next request; edits show up likewise.
        s.updateContent(Store.Kind.HOSTS, a.id, "10.0.0.1 api.example.test\n");
        assertEquals("10.0.0.1 api.example.test\n", get(bare(port, ta)).body());
        s.setShared(a.id, false);
        assertEquals(404, get(bare(port, ta)).statusCode());

        // Survives a reload of the data file, with the same token.
        s.setShared(a.id, true);
        assertNotNull(Store.open(dir.resolve("data.json")).sharedHostsContentByToken(ta));
        // The key is not part of a backup, and another installation (another key) refuses the token.
        assertFalse(s.exportParts(true, true, true).toString().contains(s.data.shareKey));
        Store other = Store.open(dir.resolve("other.json"));
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
    void allowedHostNames() {
        assertTrue(ShareServer.allowedHost("127.0.0.1:36981"));
        assertTrue(ShareServer.allowedHost("LOCALHOST"));
        assertTrue(ShareServer.allowedHost("[::1]:36981"));
        assertFalse(ShareServer.allowedHost("127.0.0.1.evil.example"));
        assertFalse(ShareServer.allowedHost("[::2]:1"));
        assertFalse(ShareServer.allowedHost(null));
        // This PC's own network address (a phone reaching it over Wi-Fi), but no other address.
        for (Network.Lan l : Network.addresses()) assertTrue(ShareServer.allowedHost(l.ip() + ":36981"), l.ip());
        assertFalse(ShareServer.allowedHost("203.0.113.7:36981"));
    }

    @Test
    void servesTheSelectedProfilesMerged() throws Exception {
        Store s = Store.open(dir.resolve("data.json"));
        Store.Item a = s.create(Store.Kind.HOSTS, "en");
        s.updateContent(Store.Kind.HOSTS, a.id, "# dev\n${PROXY_SVR} api.example.test\n10.0.0.1 db.example.test\n");
        Store.Item b = s.create(Store.Kind.HOSTS, "en");
        s.updateContent(Store.Kind.HOSTS, b.id, "10.0.0.2 db.example.test\n10.0.0.3 web.example.test\n");
        Store.Item c = s.create(Store.Kind.HOSTS, "en");
        s.updateContent(Store.Kind.HOSTS, c.id, "10.0.0.9 other.example.test\n");
        s.data.settings.web.port = freePort();
        ShareServer.start(s);
        assertEquals(ShareServer.State.RUNNING, ShareServer.state(), ShareServer.error());
        int port = ShareServer.port();
        String url = ShareServer.activeUrl(port);
        assertEquals("http://127.0.0.1:" + port + "/active-hosts", url);

        // Nothing selected: an empty list, not an error.
        HttpResponse<String> r = get(url);
        assertEquals(200, r.statusCode());
        assertEquals("", r.body());

        // Selected profiles merged in tree order, the first one that lists a host winning; not
        // shared and not selected ones stay out. Follows the selection without a restart.
        s.toggleSelected(Store.Kind.HOSTS, a.id);
        s.toggleSelected(Store.Kind.HOSTS, b.id);
        r = get(url);
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
        assertEquals("127.0.0.1 api.example.test\n10.0.0.1 db.example.test\n10.0.0.3 web.example.test\n", r.body());

        // Only the exact path.
        assertEquals(404, get(url + "x").statusCode());
        assertEquals(404, get(url + "/x").statusCode());

        // The same Host rules as the other pages: this PC's names and addresses, nothing else.
        assertEquals(200, rawStatus(port, ShareServer.ACTIVE_PATH, "localhost:" + port));
        for (Network.Lan l : Network.addresses()) {
            assertEquals(200, rawStatus(port, ShareServer.ACTIVE_PATH, l.ip() + ":" + port), l.ip());
        }
        assertEquals(403, rawStatus(port, ShareServer.ACTIVE_PATH, "evil.example:" + port));
        assertEquals("http://192.168.0.10:" + port + "/active-hosts", ShareServer.activeUrl("192.168.0.10", port));
    }

    /** GET {@code path} on 127.0.0.1 with the given Host header (HttpClient will not set one); returns the status. */
    private static int rawStatus(int port, String path, String host) throws Exception {
        try (java.net.Socket sock = new java.net.Socket(InetAddress.getLoopbackAddress(), port)) {
            sock.setSoTimeout(5000);
            sock.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            String line = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream(),
                    java.nio.charset.StandardCharsets.US_ASCII)).readLine();
            return Integer.parseInt(line.split(" ")[1]);
        }
    }

    @Test
    void servesThePhoneSetupPageAndTheCa() throws Exception {
        Store s = Store.open(dir.resolve("data.json"));
        s.data.settings.web.port = freePort();
        ShareServer.start(s);
        assertEquals(ShareServer.State.RUNNING, ShareServer.state(), ShareServer.error());
        String base = "http://127.0.0.1:" + ShareServer.port();
        HttpClient client = HttpClient.newHttpClient();
        try {
            // No CA yet: the page opens, the download is a 404.
            PhoneSetup.caCert = () -> null;
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create(base + PhoneSetup.PAGE_PATH))
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            assertTrue(page.body().contains(PhoneSetup.CA_PATH));
            assertTrue(page.body().contains(Messages.of("ko").get("app.phone.page.download")));
            assertEquals(404, get(base + PhoneSetup.CA_PATH).statusCode());
            // The root is not the setup page.
            assertEquals(404, get(base + "/").statusCode());

            // With a CA: its public certificate in DER, nothing else.
            Path caDir = dir.resolve("ca");
            new tricatch.oe.proxy.standalone.CaGenerator().generate(caDir, "Test CA", true);
            PhoneSetup.caCert = () -> caDir.resolve("ca.cer");
            HttpResponse<byte[]> ca = client.send(HttpRequest.newBuilder(URI.create(base + PhoneSetup.CA_PATH)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, ca.statusCode());
            assertEquals("application/x-x509-ca-cert", ca.headers().firstValue("Content-Type").orElse(""));
            assertTrue(ca.headers().firstValue("Content-Disposition").orElse("").contains("oehostsapp-ca.crt"));
            java.security.cert.X509Certificate x = (java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(ca.body()));
            assertTrue(x.getSubjectX500Principal().getName().contains("Test CA"));
            assertEquals(404, get(base + "/ca.pfx").statusCode());
        } finally {
            PhoneSetup.caCert = ProxyRuntime::caCert;
        }
    }

    @Test
    void bindsTheChosenAddressAndAlwaysLoopback() throws Exception {
        java.util.List<Network.Lan> lans = Network.addresses();
        org.junit.jupiter.api.Assumptions.assumeFalse(lans.isEmpty(), "no network card address on this machine");
        String ip = lans.get(0).ip();
        Store s = Store.open(dir.resolve("data.json"));
        assertEquals("127.0.0.1", s.data.settings.web.bind);
        s.data.settings.web.port = freePort();
        s.data.settings.web.bind = ip;
        ShareServer.start(s);
        assertEquals(ShareServer.State.RUNNING, ShareServer.state(), ShareServer.error());
        assertTrue(ShareServer.lan());
        int port = ShareServer.port();
        // Over the network address (the QR code's URL) and over 127.0.0.1 (share URLs on this PC).
        assertEquals(200, get(PhoneSetup.pageUrl(ip, port)).statusCode());
        assertEquals(200, get("http://127.0.0.1:" + port + PhoneSetup.PAGE_PATH).statusCode());
        // The selected profiles follow the binding like the other pages: the network address and 127.0.0.1.
        assertEquals(200, get(ShareServer.activeUrl(ip, port)).statusCode());
        assertEquals(200, get(ShareServer.activeUrl(port)).statusCode());

        // Every interface.
        s.data.settings.web.bind = "0.0.0.0";
        ShareServer.restart();
        assertEquals(ShareServer.State.RUNNING, ShareServer.state(), ShareServer.error());
        assertEquals(200, get(PhoneSetup.pageUrl(ip, port)).statusCode());
        assertEquals(200, get("http://127.0.0.1:" + port + PhoneSetup.PAGE_PATH).statusCode());

        // An address this PC does not have: reported, not moved elsewhere.
        s.data.settings.web.bind = "203.0.113.7";
        ShareServer.restart();
        assertEquals(ShareServer.State.FAILED, ShareServer.state());
    }

    @Test
    void reportsAPortInUse() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Store s = Store.open(dir.resolve("data.json"));
            s.data.settings.web.port = taken.getLocalPort();
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
