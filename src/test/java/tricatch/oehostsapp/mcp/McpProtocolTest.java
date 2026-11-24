package tricatch.oehostsapp.mcp;

import tricatch.oehostsapp.store.Store;
import tricatch.oehostsapp.store.Store.Kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private Store store;
    private McpProtocol protocol;

    @BeforeEach
    void setUp() throws Exception {
        store = Store.open(dir.resolve("data.json"));
        protocol = new McpProtocol(store);
    }

    private JsonNode send(String json) throws Exception {
        return protocol.handle(JSON.readTree(json));
    }

    private JsonNode call(String tool, String args) throws Exception {
        JsonNode r = send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + args + "}}");
        assertEquals(7, r.get("id").asInt());
        return r.get("result");
    }

    @Test
    void initializeAgreesOnAVersionAndOffersTools() throws Exception {
        JsonNode r = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}").get("result");
        assertEquals("2025-06-18", r.get("protocolVersion").asText());
        assertTrue(r.path("capabilities").has("tools"));
        assertEquals("oehostsapp", r.path("serverInfo").path("name").asText());

        JsonNode unknown = send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
        assertEquals(McpProtocol.VERSIONS.get(0), unknown.path("result").path("protocolVersion").asText());
    }

    @Test
    void notificationsAndResponsesGetNoReply() throws Exception {
        assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertNull(send("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}"));
    }

    @Test
    void errorsForUnknownMethodsToolsAndMalformedMessages() throws Exception {
        assertEquals(McpProtocol.METHOD_NOT_FOUND,
                send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}").path("error").path("code").asInt());
        assertEquals(McpProtocol.INVALID_PARAMS, send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"delete_everything\"}}").path("error").path("code").asInt());
        assertEquals(McpProtocol.INVALID_REQUEST, send("[1]").path("error").path("code").asInt());
        assertEquals(McpProtocol.INVALID_REQUEST, send("{\"jsonrpc\":\"2.0\",\"id\":1}").path("error").path("code").asInt());
        assertTrue(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").path("result").isObject());
    }

    @Test
    void everyToolIsListedReadOnly() throws Exception {
        JsonNode tools = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").path("result").path("tools");
        List<String> names = new ArrayList<>();
        for (JsonNode t : tools) {
            names.add(t.path("name").asText());
            assertEquals("object", t.path("inputSchema").path("type").asText());
            assertTrue(t.path("annotations").path("readOnlyHint").asBoolean());
        }
        assertEquals(new McpTools(store).names(), names);
        assertTrue(names.containsAll(List.of("list_hosts_profiles", "get_hosts_profile", "get_active_hosts",
                "list_vhosts", "get_vhost", "get_served_vhosts", "get_proxy_status")));
    }

    @Test
    void hostsToolsShowProfilesAndWhatIsInForce() throws Exception {
        Store.Item a = store.createFrom(Kind.HOSTS, "dev", "10.0.0.1 api.example.test\n${PROXY_SVR} web.example.test\n");
        Store.Item b = store.createFrom(Kind.HOSTS, "stage", "10.0.0.2 API.example.test\nnot-an-ip x\n");
        store.createFrom(Kind.HOSTS, "off", "10.9.9.9 other.example.test\n");
        store.toggleSelected(Kind.HOSTS, a.id);
        store.toggleSelected(Kind.HOSTS, b.id);

        JsonNode list = call("list_hosts_profiles", "{}");
        assertFalse(list.path("isError").asBoolean());
        JsonNode profiles = list.path("structuredContent").path("profiles");
        assertEquals(3, profiles.size());
        assertEquals("dev", profiles.get(0).path("name").asText());
        assertTrue(profiles.get(0).path("selected").asBoolean());
        assertEquals(2, profiles.get(0).path("rules").asInt());
        assertTrue(list.path("content").get(0).path("text").asText().contains("\"stage\""));

        JsonNode one = call("get_hosts_profile", "{\"id\":\"" + b.id + "\"}").path("structuredContent");
        assertTrue(one.path("content").asText().contains("not-an-ip"));
        assertEquals(1, one.path("problems").size());
        assertEquals(2, one.path("problems").get(0).path("line").asInt());

        JsonNode active = call("get_active_hosts", "{}").path("structuredContent");
        assertEquals(List.of("dev", "stage"), texts(active.path("selectedProfiles")));
        assertEquals(2, active.path("rules").size());
        assertEquals("127.0.0.1", active.path("rules").get(1).path("ip").asText());
        assertEquals("10.0.0.2", active.path("overridden").get(0).path("ip").asText());

        JsonNode lookup = call("get_active_hosts", "{\"host\":\"api.EXAMPLE.test\"}").path("structuredContent");
        assertEquals("10.0.0.1", lookup.path("resolvedIp").asText());
        JsonNode none = call("get_active_hosts", "{\"host\":\"other.example.test\"}").path("structuredContent");
        assertTrue(none.path("resolvedIp").isNull());
    }

    @Test
    void vhostToolsShowDocumentsAndTheServedYaml() throws Exception {
        Store.Item v = store.createFrom(Kind.VHOSTS, "app",
                "virtual:\n  - domain: app.example.test\n    location:\n      - host: http://${LOCAL_SVR}:8080\n        path: [/]\n");
        store.toggleSelected(Kind.VHOSTS, v.id);
        assertEquals("app", call("list_vhosts", "{}").path("structuredContent").path("vhosts").get(0).path("name").asText());
        assertEquals(0, call("get_vhost", "{\"id\":\"" + v.id + "\"}").path("structuredContent").path("problems").size());
        JsonNode served = call("get_served_vhosts", "{}").path("structuredContent");
        assertTrue(served.path("yaml").asText().contains("http://127.0.0.1:8080"), served.toString());
    }

    @Test
    void badArgumentsAreToolErrors() throws Exception {
        JsonNode missing = call("get_hosts_profile", "{}");
        assertTrue(missing.path("isError").asBoolean());
        JsonNode unknown = call("get_vhost", "{\"id\":\"nope\"}");
        assertTrue(unknown.path("isError").asBoolean());
        assertTrue(unknown.path("content").get(0).path("text").asText().contains("list_vhosts"));
        assertTrue(call("get_active_hosts", "{\"host\":5}").path("isError").asBoolean());
    }

    @Test
    void statusCarriesNoSecrets() throws Exception {
        store.mcpToken();
        store.shareToken("x");
        String text = call("get_proxy_status", "{}").path("content").get(0).path("text").asText();
        assertTrue(text.contains("reverseProxy") && text.contains("proxyService") && text.contains("sslProxy"));
        assertFalse(text.contains(store.data.mcpToken));
        assertFalse(text.contains(store.data.shareKey));
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}
