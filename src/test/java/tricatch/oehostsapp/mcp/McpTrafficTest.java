package tricatch.oehostsapp.mcp;

import tricatch.oehostsapp.store.Store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class McpTrafficTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private Store store;
    private McpProtocol protocol;
    private Supplier<Traffic.Snapshot> source;

    @BeforeEach
    void setUp() throws Exception {
        store = Store.open(dir.resolve("data.json"));
        store.data.settings.mcp.traffic = true;
        protocol = new McpProtocol(store);
        long t = 1_700_000_000_000L;
        Traffic.Entry login = new Traffic.Entry("r1", true, false, false, t, t + 42, t + 50,
                "POST", "app.example.test", "/login?next=/home", "https://app.example.test/login?next=/home", null, 200, null,
                List.of("POST /login?next=/home HTTP/1.1", "Host: app.example.test", "Cookie: SESSION=old-session",
                        "Content-Type: application/x-www-form-urlencoded"),
                List.of("HTTP/1.1 200 OK", "Content-Type: application/json", "Content-Encoding: gzip",
                        "Set-Cookie: SESSION=new-session; Path=/; HttpOnly"),
                "user=kim&password=hunter2".getBytes(StandardCharsets.UTF_8),
                gzip("{\"ok\":true,\"access_token\":\"tok-1\",\"name\":\"Kim\"}"),
                false, false, false, 0, 0, List.of());
        Traffic.Entry tunnel = new Traffic.Entry("f2", false, true, false, t + 100, 0, t + 900,
                "CONNECT", "www.example.com", "443", "www.example.com:443", "192.168.0.23", null, null,
                List.of("CONNECT www.example.com:443 HTTP/1.1"), List.of(), null, null,
                false, false, false, 1200, 5400, List.of());
        Traffic.Entry failed = new Traffic.Entry("f3", false, false, false, t + 200, 0, t + 300,
                "GET", "down.example.test", "/", "http://down.example.test/", "127.0.0.1", 502, "dns",
                List.of("GET http://down.example.test/ HTTP/1.1"), List.of(), null, null,
                false, false, false, 80, 0, List.of());
        source = () -> new Traffic.Snapshot(true, List.of(login, tunnel, failed));
        Traffic.setSource(source);
    }

    @AfterEach
    void tearDown() {
        Traffic.clearSource(source);
    }

    private JsonNode call(String tool, String args) throws Exception {
        return protocol.handle(JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + args + "}}")).get("result");
    }

    @Test
    void listsNewestFirstWithFilters() throws Exception {
        JsonNode all = call("list_traffic", "{}").path("structuredContent");
        assertTrue(all.path("capturing").asBoolean());
        assertEquals(3, all.path("total").asInt());
        JsonNode e = all.path("entries");
        assertEquals("f3", e.get(0).path("id").asText());
        assertEquals("r1", e.get(2).path("id").asText());
        assertEquals("reverse-proxy", e.get(2).path("source").asText());
        assertEquals(42, e.get(2).path("durationMs").asInt());
        assertEquals("192.168.0.23", e.get(1).path("client").asText(), "other devices are included");

        assertEquals(2, call("list_traffic", "{\"contentOnly\":true}").path("structuredContent").path("entries").size());
        assertEquals("f3", call("list_traffic", "{\"status\":\"failed\"}").path("structuredContent").path("entries").get(0).path("id").asText());
        assertEquals("f3", call("list_traffic", "{\"status\":\"5xx\"}").path("structuredContent").path("entries").get(0).path("id").asText());
        assertEquals("r1", call("list_traffic", "{\"host\":\"APP.example\",\"method\":\"post\"}").path("structuredContent")
                .path("entries").get(0).path("id").asText());
        assertEquals(1, call("list_traffic", "{\"limit\":1}").path("structuredContent").path("entries").size());
        assertTrue(call("list_traffic", "{\"limit\":0}").path("isError").asBoolean());
        assertTrue(call("list_traffic", "{\"status\":\"teapot\"}").path("isError").asBoolean());
    }

    @Test
    void detailHidesSecretsButKeepsTheRest() throws Exception {
        JsonNode r = call("get_traffic", "{\"id\":\"r1\"}");
        String text = r.path("content").get(0).path("text").asText();
        for (String secret : List.of("old-session", "new-session", "hunter2", "tok-1")) assertFalse(text.contains(secret), secret);
        JsonNode d = r.path("structuredContent");
        assertTrue(d.path("requestHeaders").toString().contains("SESSION=hmac:"));
        assertTrue(d.path("responseHeaders").toString().contains("Path=/; HttpOnly"));
        assertTrue(d.path("requestBody").path("text").asText().startsWith("user=kim&password=hmac:"));
        String res = d.path("responseBody").path("text").asText();
        assertTrue(res.contains("\"name\" : \"Kim\"") && res.contains("hmac:"), res);
        assertFalse(d.path("untrusted").asText().isEmpty());

        JsonNode tunnel = call("get_traffic", "{\"id\":\"f2\"}").path("structuredContent");
        assertEquals("tunnel", tunnel.path("type").asText());
        assertFalse(tunnel.has("requestBody"));
        assertTrue(call("get_traffic", "{\"id\":\"zz\"}").path("isError").asBoolean());
    }

    @Test
    void offUnlessTurnedOn() throws Exception {
        store.data.settings.mcp.traffic = false;
        JsonNode r = call("list_traffic", "{}");
        assertTrue(r.path("isError").asBoolean());
        assertTrue(r.path("content").get(0).path("text").asText().contains("Offer Network traffic"));
        assertTrue(call("get_traffic", "{\"id\":\"r1\"}").path("isError").asBoolean());
    }

    @Test
    void noNetworkTabMeansNothing() throws Exception {
        Traffic.clearSource(source);
        JsonNode r = call("list_traffic", "{}").path("structuredContent");
        assertFalse(r.path("capturing").asBoolean());
        assertEquals(0, r.path("total").asInt());
    }

    private static byte[] gzip(String s) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(s.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
