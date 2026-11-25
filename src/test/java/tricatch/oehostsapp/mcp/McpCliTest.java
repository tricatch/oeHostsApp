package tricatch.oehostsapp.mcp;

import tricatch.oehostsapp.AppData;
import tricatch.oehostsapp.store.Store;
import tricatch.oehostsapp.store.Store.Kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private String stdout;
    private String stderr;

    @AfterEach
    void tearDown() {
        McpServer.stop();
    }

    /** A data folder like the app's, with the server running on it. */
    private Store startServer() throws Exception {
        Store s = AppData.open(dir);
        s.data.settings.mcp.enabled = true;
        s.data.settings.mcp.port = freePort();
        s.save();
        McpServer.start(s);
        assertEquals(McpServer.State.RUNNING, McpServer.state(), McpServer.error());
        return s;
    }

    private int run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = McpCli.run(dir, List.of(args), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        stdout = out.toString(StandardCharsets.UTF_8);
        stderr = err.toString(StandardCharsets.UTF_8);
        return code;
    }

    @Test
    void namesTranslateToCommandsAndOptions() {
        assertEquals("content-only", McpCli.option("contentOnly"));
        assertEquals("id", McpCli.option("id"));
        assertEquals("list-traffic", McpCli.command(JSON.createObjectNode().put("name", "list_traffic")));
    }

    @Test
    void callsAToolAndPrintsItsJson() throws Exception {
        Store s = startServer();
        Store.Item item;
        synchronized (s) {
            item = s.createFrom(Kind.HOSTS, "한글 profile", "127.0.0.1 a.example.test\n");
        }
        assertEquals(McpCli.OK, run("list-hosts-profiles"), stderr);
        JsonNode list = JSON.readTree(stdout);
        assertTrue(list.toString().contains(item.id), stdout);
        assertTrue(stdout.contains("한글 profile"), "UTF-8 output");

        // --id VALUE, --id=VALUE, the bare value, and the tool's own name all work.
        assertEquals(McpCli.OK, run("get-hosts-profile", "--id", item.id), stderr);
        assertEquals(item.id, JSON.readTree(stdout).path("id").asText());
        assertEquals(McpCli.OK, run("get-hosts-profile", "--id=" + item.id), stderr);
        assertEquals(McpCli.OK, run("get_hosts_profile", item.id), stderr);
        assertFalse(stdout.contains(s.data.mcpToken));
    }

    @Test
    void toolsAndHelpComeFromTheServer() throws Exception {
        startServer();
        assertEquals(McpCli.OK, run("tools"), stderr);
        JsonNode tools = JSON.readTree(stdout);
        assertTrue(tools.isArray() && tools.size() > 0);
        boolean found = false;
        for (JsonNode t : tools) {
            if (t.path("command").asText().equals("list-traffic")) {
                found = true;
                assertTrue(t.path("options").toString().contains("--content-only"));
            }
        }
        assertTrue(found, stdout);

        assertEquals(McpCli.OK, run("help"), stderr);
        assertTrue(stdout.contains("get-active-hosts"));
        assertTrue(stdout.contains("Exit codes"));
        // An installed skill compares its version with this line and fetches the new one itself.
        assertTrue(stdout.contains("Skill version: " + CliSkill.VERSION + " (latest skill: http://127.0.0.1:"), stdout);
        assertEquals(McpCli.OK, run("list-traffic", "--help"), stderr);
        assertTrue(stdout.contains("--limit N"));
        assertEquals(McpCli.OK, run("help", "get-traffic"), stderr);
        assertTrue(stdout.contains("--id ID"));
    }

    @Test
    void reportsAToolErrorWithExitCode1() throws Exception {
        startServer();
        assertEquals(McpCli.FAILED, run("get-hosts-profile", "--id", "nope"));
        assertTrue(stdout.isEmpty());
        assertFalse(stderr.isBlank());
    }

    @Test
    void refusesWrongUsageWithExitCode2() throws Exception {
        startServer();
        assertEquals(McpCli.USAGE, run("no-such-command"));
        assertTrue(stderr.contains("list-hosts-profiles"), "lists the commands");
        assertEquals(McpCli.USAGE, run("get-hosts-profile"));
        assertTrue(stderr.contains("Missing option --id"));
        assertEquals(McpCli.USAGE, run("list-traffic", "--nope"));
        assertTrue(stderr.contains("--content-only"), "lists the options");
        assertEquals(McpCli.USAGE, run("list-traffic", "--limit", "many"));
        assertEquals(McpCli.USAGE, run("list-traffic", "--limit"));
        assertEquals(McpCli.USAGE, run("list-traffic", "--content-only=maybe"));
        assertEquals(McpCli.USAGE, run("list-traffic", "--host", "a", "--host", "b"));
        assertEquals(McpCli.USAGE, run("list-hosts-profiles", "extra"));
        assertEquals(McpCli.USAGE, run("get-hosts-profile", "a", "b"));
        assertEquals(McpCli.USAGE, run("tools", "x"));
    }

    @Test
    void unavailableWithExitCode3() throws Exception {
        // No data file.
        assertEquals(McpCli.UNAVAILABLE, run("list-vhosts"));
        assertTrue(stderr.contains("--data"));

        // Data but no running server.
        Store s = startServer();
        McpServer.stop();
        assertEquals(McpCli.UNAVAILABLE, run("list-vhosts"));
        assertTrue(stderr.contains("not answering"), stderr);
        // Help still prints, and says why the commands are missing.
        assertEquals(McpCli.UNAVAILABLE, run("help"));
        assertTrue(stdout.contains("Exit codes"));

        // The switch off.
        s.data.settings.mcp.enabled = false;
        s.save();
        assertEquals(McpCli.UNAVAILABLE, run("list-vhosts"));
        assertTrue(stderr.contains("switched off"), stderr);
    }

    @Test
    void anotherTokenIsRefusedWithExitCode4() throws Exception {
        Store s = startServer();
        // The file names a token the running server does not know (another data folder, an old file).
        String file = Files.readString(dir.resolve(AppData.FILE_NAME));
        Files.writeString(dir.resolve(AppData.FILE_NAME), file.replace(s.data.mcpToken, "x" + s.data.mcpToken));
        assertEquals(McpCli.REFUSED, run("list-vhosts"));
        assertFalse(stderr.contains(s.data.mcpToken), "never prints the token");
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }
}
