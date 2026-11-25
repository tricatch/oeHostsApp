package tricatch.oehostsapp.mcp;

import tricatch.oehostsapp.store.Store;
import tricatch.oehostsapp.store.Store.Kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The change tools, run without a window (straight on the store). */
class McpWriteToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private Store store;
    private McpProtocol protocol;

    @BeforeEach
    void setUp() throws Exception {
        assertNull(AppControl.current(), "no window in tests");
        store = Store.open(dir.resolve("data.json"));
        protocol = new McpProtocol(store);
    }

    private JsonNode call(String tool, Object args) throws Exception {
        String a = args instanceof String s ? s : JSON.writeValueAsString(args);
        JsonNode r = protocol.handle(JSON.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                + tool + "\",\"arguments\":" + a + "}}"));
        return r.get("result");
    }

    private JsonNode ok(String tool, Object args) throws Exception {
        JsonNode r = call(tool, args);
        assertFalse(r.path("isError").asBoolean(), () -> tool + ": " + r.path("content").get(0).path("text").asText());
        return r.path("structuredContent");
    }

    private String error(String tool, Object args) throws Exception {
        JsonNode r = call(tool, args);
        assertTrue(r.path("isError").asBoolean(), tool + " should fail");
        return r.path("content").get(0).path("text").asText();
    }

    @Test
    void profilesAreCreatedChangedSelectedAndDeleted() throws Exception {
        JsonNode folder = ok("create_folder", java.util.Map.of("kind", "hosts", "name", "Team"));
        String folderId = folder.path("id").asText();
        JsonNode p = ok("create_hosts_profile", java.util.Map.of("name", "dev", "content", "127.0.0.1 a.test\r\n", "folderId", folderId));
        String id = p.path("id").asText();
        assertEquals("Team", p.path("folder").asText());
        assertEquals("127.0.0.1 a.test\n", store.find(Kind.HOSTS, id).content, "line ends as the editor keeps them");
        assertFalse(p.path("selected").asBoolean());

        // Content the screen would refuse is refused, and nothing changes.
        assertTrue(error("update_hosts_profile", java.util.Map.of("id", id, "content", "not a rule")).contains("Line 1:"));
        assertEquals("127.0.0.1 a.test\n", store.find(Kind.HOSTS, id).content);

        ok("update_hosts_profile", java.util.Map.of("id", id, "content", "10.0.0.1 b.test", "name", "dev2"));
        assertEquals("dev2", store.find(Kind.HOSTS, id).name);
        assertTrue(ok("select_hosts_profile", java.util.Map.of("id", id, "selected", true)).path("selected").asBoolean());
        assertEquals("10.0.0.1", ok("get_active_hosts", "{\"host\":\"b.test\"}").path("resolvedIp").asText());
        assertTrue(ok("share_hosts_profile", java.util.Map.of("id", id, "shared", true)).path("shared").asBoolean());

        JsonNode list = ok("list_hosts_profiles", "{}");
        assertEquals(folderId, list.path("folders").get(0).path("id").asText());
        assertEquals(folderId, list.path("profiles").get(0).path("folderId").asText());

        assertTrue(ok("move_hosts_profile", java.util.Map.of("id", id)).path("folderId").isNull());
        ok("delete_folder", java.util.Map.of("kind", "hosts", "id", folderId));
        ok("delete_hosts_profile", java.util.Map.of("id", id));
        assertNull(store.find(Kind.HOSTS, id));
        assertTrue(error("delete_hosts_profile", java.util.Map.of("id", id)).contains("list_hosts_profiles"));
    }

    @Test
    void anInvalidProfileCannotBeTurnedOn() throws Exception {
        Store.Item bad = store.createFrom(Kind.HOSTS, "bad", "oops");
        assertTrue(error("select_hosts_profile", java.util.Map.of("id", bad.id, "selected", true)).contains("problems"));
        assertFalse(store.find(Kind.HOSTS, bad.id).selected);
    }

    @Test
    void vhostsAreValidatedLikeTheEditor() throws Exception {
        // The example a new vhost starts with is valid YAML for the editor.
        Store.Item example = store.create(Kind.VHOSTS, "en");
        String yaml = example.content;
        store.delete(Kind.VHOSTS, example.id);

        String vhostError = error("create_vhost", java.util.Map.of("name", "broken", "content", "vhosts: [oops"));
        assertTrue(vhostError.contains("problems"), vhostError);
        assertTrue(store.data.vhosts.isEmpty());
        String id = ok("create_vhost", java.util.Map.of("name", "app", "content", yaml)).path("id").asText();
        assertTrue(ok("select_vhost", java.util.Map.of("id", id, "selected", true)).path("selected").asBoolean());
        assertTrue(ok("get_served_vhosts", "{}").path("selectedVhosts").toString().contains("app"));
    }

    @Test
    void settingsAreChangedWithTheScreensChecks() throws Exception {
        assertTrue(error("set_launcher_options", "{\"proxyServer\":true}").contains("cannot both"),
                "hostResolverRules is on by default");
        assertTrue(error("set_launcher_options", "{\"openUrl\":\"example.test\"}").contains("openUrl"));
        JsonNode launcher = ok("set_launcher_options", "{\"hostResolverRules\":false,\"proxyServer\":true,\"incognito\":true}");
        assertTrue(launcher.path("proxyServer").asBoolean());
        assertTrue(store.data.launcher.incognito);

        JsonNode presets = ok("set_presets", java.util.Map.of("list", "user_agent", "text", "# mine\nPhone | Mozilla/5.0 (iPhone)\nbroken line\n"));
        assertEquals(1, presets.path("entries").size());
        assertEquals("# mine\nPhone | Mozilla/5.0 (iPhone)\nbroken line\n", store.data.settings.userAgentPresets.text);

        assertTrue(error("set_ssl_proxy", java.util.Map.of("domains", "not a domain!")).contains("1"));
        assertEquals("", store.data.settings.sslProxy.domains);

        assertTrue(error("set_proxy_service_port", "{\"port\":70000}").contains("1-65535"));
        assertTrue(error("set_reverse_proxy_bind", "{\"bind\":\"10.255.255.254\"}").contains("bind must be one of"));

        JsonNode settings = ok("get_settings", "{}");
        assertEquals("Phone", settings.path("presets").path("userAgent").path("entries").get(0).path("name").asText());
        assertFalse(settings.toString().contains(store.mcpToken()), "the MCP token is never offered");
    }

    @Test
    void windowOnlyToolsSayWhenThereIsNoWindow() throws Exception {
        assertTrue(error("set_capture", "{\"capturing\":false}").contains("not ready"));
        assertTrue(error("launch_chrome", "{\"mode\":\"as_set\"}").contains("not ready"));
    }
}
